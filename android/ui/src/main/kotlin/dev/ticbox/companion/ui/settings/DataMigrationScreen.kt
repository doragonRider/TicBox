package dev.ticbox.companion.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationManifest
import dev.ticbox.companion.api.MigrationSection
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.CommandRunner
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.InlineValueRow
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.toolbox.ToolboxDocuments
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How far an import has got. */
sealed interface ImportStep {
    val uri: Uri

    /** The file is encrypted and needs its password. */
    data class Locked(override val uri: Uri, val wrongPassword: Boolean = false) : ImportStep

    data class Ready(
        override val uri: Uri,
        val manifest: MigrationManifest,
        val chosen: Set<MigrationSection>,
    ) : ImportStep
}

/**
 * Export and import of the app's data. Passwords live only in this view model: never in saved
 * state, messages or logs, and they are cleared once used.
 */
class DataMigrationViewModel(
    private val migration: DataMigration,
    private val resolver: ContentResolver,
    scope: CoroutineScope,
    messages: UserMessages,
) : ViewModel() {
    private val commands = CommandRunner(scope, messages)
    val pending = commands.pending

    private val exportChoice = MutableStateFlow(DEFAULT_EXPORT)
    val exportSections: StateFlow<Set<MigrationSection>> = exportChoice.asStateFlow()
    private val exportSecret = MutableStateFlow("")
    val exportPassword: StateFlow<String> = exportSecret.asStateFlow()
    private val importSecret = MutableStateFlow("")
    val importPassword: StateFlow<String> = importSecret.asStateFlow()
    private val step = MutableStateFlow<ImportStep?>(null)
    val importStep: StateFlow<ImportStep?> = step.asStateFlow()

    fun setExport(section: MigrationSection, included: Boolean) = exportChoice.update {
        if (included) it + section else it - section
    }

    fun setExportPassword(value: String) {
        exportSecret.value = value
    }

    fun setImportPassword(value: String) {
        importSecret.value = value
    }

    /** An encrypted file gets the extension that says so, a plain one stays a ZIP. */
    fun fileName(date: LocalDate): String =
        "TicBox-$date" + if (exportSecret.value.isEmpty()) ".zip" else ".tbbackup"

    fun export(uri: Uri) =
        commands.launch(EXPORT) {
            val sections = exportChoice.value
            val password = exportSecret.value.toCharArray()
            try {
                ToolboxDocuments(resolver).writeTo(uri) { migration.export(it, sections, password) }
            } finally {
                password.fill(' ')
            }
            uiText(R.string.migration_exported)
        }

    fun choose(uri: Uri) {
        importSecret.value = ""
        step.value = null
        inspect(uri)
    }

    fun unlock() {
        val current = step.value as? ImportStep.Locked ?: return
        if (importSecret.value.isNotEmpty()) inspect(current.uri)
    }

    fun setImport(section: MigrationSection, included: Boolean) = step.update { current ->
        if (current !is ImportStep.Ready) current
        else
            current.copy(
                chosen = if (included) current.chosen + section else current.chosen - section
            )
    }

    fun cancelImport() {
        step.value = null
        importSecret.value = ""
    }

    /** The app restarts when this succeeds, and also when it fails after the watch was let go. */
    fun import() {
        val ready = step.value as? ImportStep.Ready ?: return
        commands.launch(IMPORT) {
            val password = importSecret.value.toCharArray().takeIf { it.isNotEmpty() }
            try {
                migration.import(open(ready.uri), password, ready.chosen)
            } finally {
                password?.fill(' ')
            }
            null
        }
    }

    private fun inspect(uri: Uri) =
        commands.launch(INSPECT) {
            val password = importSecret.value.toCharArray().takeIf { it.isNotEmpty() }
            try {
                val manifest = migration.inspect(open(uri), password)
                step.value = ImportStep.Ready(uri, manifest, manifest.sections)
                null
            } catch (problem: MigrationException) {
                when (problem.code) {
                    "migration_password_required" -> step.value = ImportStep.Locked(uri)
                    "migration_wrong_password" -> step.value = ImportStep.Locked(uri, true)
                    else -> {
                        step.value = null
                        throw problem
                    }
                }
                null
            } finally {
                password?.fill(' ')
            }
        }

    private fun open(uri: Uri): () -> InputStream = {
        resolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
    }

    companion object {
        const val EXPORT = "migration_export"
        const val INSPECT = "migration_inspect"
        const val IMPORT = "migration_import"

        /** The pairing identity only belongs in a file meant for this same phone. */
        val DEFAULT_EXPORT =
            setOf(
                MigrationSection.HEALTH,
                MigrationSection.SETTINGS,
                MigrationSection.SOS,
                MigrationSection.ADB,
            )
    }
}

private val MigrationSection.title: Int
    get() =
        when (this) {
            MigrationSection.HEALTH -> R.string.migration_section_health
            MigrationSection.SETTINGS -> R.string.migration_section_settings
            MigrationSection.SOS -> R.string.migration_section_sos
            MigrationSection.ADB -> R.string.migration_section_adb
            MigrationSection.PAIRING -> R.string.migration_section_pairing
        }

private val MigrationSection.summary: Int
    get() =
        when (this) {
            MigrationSection.HEALTH -> R.string.migration_section_health_summary
            MigrationSection.SETTINGS -> R.string.migration_section_settings_summary
            MigrationSection.SOS -> R.string.migration_section_sos_summary
            MigrationSection.ADB -> R.string.migration_section_adb_summary
            MigrationSection.PAIRING -> R.string.migration_section_pairing_summary
        }

private val MigrationSection.icon: Int
    get() =
        when (this) {
            MigrationSection.HEALTH -> AppIcons.Heart
            MigrationSection.SETTINGS -> AppIcons.Settings
            MigrationSection.SOS -> AppIcons.Sos
            MigrationSection.ADB -> AppIcons.Key
            MigrationSection.PAIRING -> AppIcons.Watch
        }

/** What a migration error code says to the user. */
fun migrationMessage(code: String): UiText =
    uiText(
        when (code) {
            "migration_unavailable" -> R.string.migration_error_unavailable
            "migration_password_required" -> R.string.migration_error_password_required
            "migration_wrong_password" -> R.string.migration_error_wrong_password
            "migration_not_a_backup" -> R.string.migration_error_not_a_backup
            "migration_damaged" -> R.string.migration_error_damaged
            "migration_newer" -> R.string.migration_error_newer
            "migration_older" -> R.string.migration_error_older
            "migration_section_missing" -> R.string.migration_error_section_missing
            "migration_storage_full" -> R.string.migration_error_storage_full
            "migration_nothing_selected" -> R.string.migration_error_nothing_selected
            "migration_io" -> R.string.migration_error_io
            else -> R.string.migration_error_failed
        }
    )

@Composable
fun DataMigrationScreen(onBack: () -> Unit) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val viewModel = companionViewModel {
        DataMigrationViewModel(it.dataMigration, resolver, it.applicationScope, it.messages)
    }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val exporting = DataMigrationViewModel.EXPORT in pending
    val reading = DataMigrationViewModel.INSPECT in pending
    val importing = DataMigrationViewModel.IMPORT in pending
    val plainTarget =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            uri?.let(viewModel::export)
        }
    val sealedTarget =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let(viewModel::export)
        }
    val source =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::choose)
        }
    PageScaffold(stringResource(R.string.migration_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            ExportPart(viewModel, exporting) { name, sealed ->
                if (sealed) sealedTarget.launch(name) else plainTarget.launch(name)
            }
            ListGroup(title = stringResource(R.string.migration_import_group)) {
                NavigationRow(
                    stringResource(R.string.migration_choose_file),
                    onClick = {
                        source.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    icon = AppIcons.Download,
                    summary = stringResource(R.string.migration_choose_file_summary),
                    enabled = !importing,
                    trailingIcon = null,
                )
            }
            BusyBar(reading || importing)
            val step by viewModel.importStep.collectAsStateWithLifecycle()
            RevealValue(step as? ImportStep.Locked, spacing = 20.dp) {
                UnlockPart(viewModel, it, reading)
            }
            RevealValue(step as? ImportStep.Ready, spacing = 20.dp) {
                ImportPart(viewModel, it, importing)
            }
        }
    }
}

@Composable
private fun ExportPart(
    viewModel: DataMigrationViewModel,
    exporting: Boolean,
    launch: (String, Boolean) -> Unit,
) {
    val sections by viewModel.exportSections.collectAsStateWithLifecycle()
    val password by viewModel.exportPassword.collectAsStateWithLifecycle()
    ListGroup(title = stringResource(R.string.migration_export_group)) {
        MigrationSection.entries.forEach { section ->
            SwitchRow(
                stringResource(section.title),
                section in sections,
                { viewModel.setExport(section, it) },
                summary = stringResource(section.summary),
                icon = section.icon,
                enabled = !exporting,
            )
        }
    }
    OutlinedTextField(
        password,
        viewModel::setExportPassword,
        Modifier.fillMaxWidth(),
        enabled = !exporting,
        label = { Text(stringResource(R.string.migration_password)) },
        supportingText = { Text(stringResource(R.string.migration_password_supporting)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    )
    Button(
        onClick = { launch(viewModel.fileName(LocalDate.now()), password.isNotEmpty()) },
        enabled = sections.isNotEmpty() && !exporting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.migration_export_action))
    }
    BusyBar(exporting)
}

@Composable
private fun UnlockPart(
    viewModel: DataMigrationViewModel,
    step: ImportStep.Locked,
    reading: Boolean,
) {
    val password by viewModel.importPassword.collectAsStateWithLifecycle()
    OutlinedTextField(
        password,
        viewModel::setImportPassword,
        Modifier.fillMaxWidth(),
        enabled = !reading,
        label = { Text(stringResource(R.string.migration_import_password)) },
        supportingText = {
            Text(
                stringResource(
                    if (step.wrongPassword) R.string.migration_error_wrong_password
                    else R.string.migration_error_password_required
                )
            )
        },
        isError = step.wrongPassword,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.unlock() }),
    )
    Button(
        onClick = viewModel::unlock,
        enabled = password.isNotEmpty() && !reading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun ImportPart(
    viewModel: DataMigrationViewModel,
    step: ImportStep.Ready,
    importing: Boolean,
) {
    val manifest = step.manifest
    ListGroup(title = stringResource(R.string.migration_file_group)) {
        InlineValueRow(
            stringResource(R.string.migration_file_created),
            formatDateTime(manifest.createdAtMillis, currentLocale()),
        )
        InlineValueRow(stringResource(R.string.migration_file_version), manifest.appVersion)
        MigrationSection.entries
            .filter { it in manifest.sections }
            .forEach { section ->
                SwitchRow(
                    stringResource(section.title),
                    section in step.chosen,
                    { viewModel.setImport(section, it) },
                    summary = stringResource(section.summary),
                    icon = section.icon,
                    enabled = !importing,
                )
            }
    }
    FootnoteText(stringResource(R.string.migration_import_note))
    Button(
        onClick = viewModel::import,
        enabled = step.chosen.isNotEmpty() && !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.migration_import_action))
    }
    OutlinedButton(
        onClick = viewModel::cancelImport,
        enabled = !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}
