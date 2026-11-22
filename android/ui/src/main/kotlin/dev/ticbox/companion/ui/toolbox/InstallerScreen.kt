package dev.ticbox.companion.ui.toolbox

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ToolboxBundle
import dev.ticbox.companion.api.ToolboxBundleResult
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxInstallOptions
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InstallResultKind {
    INSTALLED,
    FAILED,

    /** The connection dropped after the commit may have begun, so it may still have worked. */
    UNKNOWN,

    /** The app is on the watch, but the data that came with it didn't all arrive. */
    INSTALLED_WITHOUT_DATA,
}

data class InstallResult(
    val name: String,
    val kind: InstallResultKind,
    val message: UiText?,
    val output: String?,
)

data class InstallerUiState(
    val target: dev.ticbox.companion.api.ToolboxTarget? = null,
    val files: List<PickedFile> = emptyList(),
    /**
     * The picked files that turned out to be a whole app in one archive, whatever they're named.
     */
    val bundles: Map<Uri, ToolboxBundle> = emptyMap(),
    val options: ToolboxInstallOptions = ToolboxInstallOptions(),
    /** Null while no install is running; the fraction is null until progress is known. */
    val progress: InstallProgress? = null,
    val results: List<InstallResult> = emptyList(),
)

data class InstallProgress(val name: String, val fraction: Float?, val copyingData: Boolean = false)

/** What goes to the watch as one install, and is named by the app when the archive says which. */
internal class InstallBatch(val files: List<PickedFile>, val bundle: ToolboxBundle? = null) {
    val name: String
        get() = bundle?.label ?: files.first().name
}

/**
 * The APK installer gives each file its own install. The split installer sends all of them as one
 * app in one session, so it either commits as a whole or not at all. A package file is a whole app
 * already, so it's an install of its own on both pages.
 */
internal fun installBatches(
    files: List<PickedFile>,
    bundles: Map<Uri, ToolboxBundle>,
    split: Boolean,
): List<InstallBatch> {
    val (packaged, plain) = files.partition { it.uri in bundles }
    val apks = if (split && plain.isNotEmpty()) listOf(plain) else plain.map { listOf(it) }
    return apks.map { InstallBatch(it) } +
        packaged.map { InstallBatch(listOf(it), bundles.getValue(it.uri)) }
}

sealed class InstallerViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
    private val documents: ToolboxDocuments,
    private val split: Boolean,
) : SessionViewModel(dependencies) {
    private val current = MutableStateFlow(InstallerUiState())
    val state: StateFlow<InstallerUiState> = current.asStateFlow()
    private var pickingTarget: dev.ticbox.companion.api.ToolboxTarget? = null

    fun preparePick() {
        pickingTarget = toolbox.access.value?.target
    }

    /** Each file is looked into right away, so what's wrong with one shows before any install. */
    fun add(uris: List<Uri>) =
        command(READ) {
            val target = pickingTarget ?: throw ToolboxException("connection_replaced")
            pickingTarget = null
            if (current.value.files.isNotEmpty() && current.value.target != target)
                throw ToolboxException("connection_replaced")
            val backend = toolbox.backend(target)
            current.update { it.copy(target = backend.state.value.target) }
            current.update { it.copy(results = emptyList()) }
            val described = uris.map { documents.describe(it)?.takeIf { file -> file.size > 0 } }
            if (null in described) messages.show(uiText(R.string.installer_unreadable))
            val known = current.value.files.map { it.uri }.toSet()
            described
                .filterNotNull()
                .distinctBy { it.uri }
                .filter { it.uri !in known }
                .forEach { read(it, backend) }
            null
        }

    private suspend fun read(file: PickedFile, backend: WatchToolboxBackend) {
        try {
            val bundle = reportingTimeouts { backend.readBundle(documents.apk(file)) }
            current.update { state ->
                state.copy(
                    files = state.files + file,
                    bundles =
                        if (bundle == null) state.bundles else state.bundles + (file.uri to bundle),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            current.update { it.copy(results = it.results + problem.toReadFailure(file.name)) }
        }
    }

    fun remove(file: PickedFile) = current.update {
        it.copy(
            files = it.files - file,
            bundles = it.bundles - file.uri,
            target = if (it.files.size == 1) null else it.target,
        )
    }

    fun setOptions(options: ToolboxInstallOptions) = current.update { it.copy(options = options) }

    fun install() =
        command(INSTALL) {
            val backend =
                toolbox.backend(
                    current.value.target ?: throw ToolboxException("connection_replaced")
                )
            val snapshot = current.value
            current.update { it.copy(results = emptyList()) }
            try {
                installBatches(snapshot.files, snapshot.bundles, split).forEach { batch ->
                    val result = install(backend, batch, snapshot.options)
                    current.update { state ->
                        // What's done leaves the list, so trying again doesn't resend gigabytes.
                        val done =
                            if (result.kind == InstallResultKind.INSTALLED) batch.files
                            else emptyList()
                        state.copy(
                            files = state.files - done.toSet(),
                            bundles = state.bundles - done.map { it.uri }.toSet(),
                            results = state.results + result,
                        )
                    }
                }
            } finally {
                // Whatever came of it, the watch's apps and files may not be what they were.
                toolbox.memory()?.forgetLists()
                current.update { it.copy(progress = null) }
            }
            null
        }

    private suspend fun install(
        backend: WatchToolboxBackend,
        batch: InstallBatch,
        options: ToolboxInstallOptions,
    ): InstallResult {
        val name = batch.name
        val bundle = batch.bundle
        val show = { progress: ToolboxProgress ->
            val fraction = fractionOf(progress.completedBytes, progress.totalBytes)
            val copying = bundle != null && progress.item >= bundle.apks.size
            current.update { it.copy(progress = InstallProgress(name, fraction, copying)) }
        }
        current.update { it.copy(progress = InstallProgress(name, null)) }
        return try {
            reportingTimeouts {
                    if (bundle != null) {
                        backend.installBundle(bundle, options, show)
                    } else {
                        ToolboxBundleResult(
                            backend.install(batch.files.map(documents::apk), options, show)
                        )
                    }
                }
                .toInstallResult(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            problem.toInstallResult(name)
        }
    }

    companion object {
        const val INSTALL = "toolbox_install"
        const val READ = "toolbox_install_read"
    }
}

class ApkInstallerViewModel(
    dependencies: CompanionDependencies,
    toolbox: ToolboxViewModel,
    documents: ToolboxDocuments,
) : InstallerViewModel(dependencies, toolbox, documents, split = false)

class SplitInstallerViewModel(
    dependencies: CompanionDependencies,
    toolbox: ToolboxViewModel,
    documents: ToolboxDocuments,
) : InstallerViewModel(dependencies, toolbox, documents, split = true)

private fun ToolboxBundleResult.toInstallResult(name: String): InstallResult =
    when {
        !install.success ->
            InstallResult(
                name,
                InstallResultKind.FAILED,
                null,
                install.output.trim().ifEmpty { null },
            )
        dataFailure != null ->
            InstallResult(
                name,
                InstallResultKind.INSTALLED_WITHOUT_DATA,
                toolboxMessage(dataFailure.orEmpty()),
                null,
            )
        else -> InstallResult(name, InstallResultKind.INSTALLED, null, null)
    }

/** Nothing was sent yet, so unlike an install this can only have failed. */
private fun Exception.toReadFailure(name: String): InstallResult {
    val reason =
        (this as? ToolboxException)?.let { toolboxMessage(it.code) }
            ?: uiText(R.string.installer_unreadable)
    return InstallResult(name, InstallResultKind.FAILED, reason, null)
}

/** Only a refusal is known to have failed; a silent connection may have finished the commit. */
private fun Exception.toInstallResult(name: String): InstallResult {
    val code = (this as? ToolboxException)?.code
    return if (code in KNOWN_FAILURES) {
        InstallResult(name, InstallResultKind.FAILED, toolboxMessage(code.orEmpty()), null)
    } else {
        InstallResult(name, InstallResultKind.UNKNOWN, null, null)
    }
}

private val KNOWN_FAILURES =
    setOf(
        "permission_denied",
        "invalid_request",
        "not_connected",
        "source_short_read",
        "source_empty_read",
        "source_size_mismatch",
        "package_encrypted",
        "package_unreadable",
        "package_no_space",
    )

/**
 * Kept by the toolbox graph, so a running install and its result are still here after a look at
 * another tool.
 */
@Composable
fun InstallerScreen(graph: ViewModelStoreOwner, split: Boolean, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val context = LocalContext.current
    val documents =
        remember(context) { ToolboxDocuments(context.applicationContext.contentResolver) }
    val viewModel: InstallerViewModel =
        if (split) {
            companionViewModel<SplitInstallerViewModel>(graph) {
                SplitInstallerViewModel(it, toolbox, documents)
            }
        } else {
            companionViewModel<ApkInstallerViewModel>(graph) {
                ApkInstallerViewModel(it, toolbox, documents)
            }
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val running = InstallerViewModel.INSTALL in pending
    val reading = InstallerViewModel.READ in pending
    val busy = running || reading
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            viewModel.add(it)
        }
    val choose = {
        viewModel.preparePick()
        picker.launch(PACKAGE_TYPES)
    }
    PageScaffold(
        stringResource(if (split) R.string.tool_split_title else R.string.tool_apk_title),
        onBack = onBack,
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        PageList(padding, spacing = 12.dp) {
            if (state.files.isEmpty() && state.results.isEmpty()) {
                item(key = "start") {
                    ToolStart(
                        AppIcons.Archive,
                        stringResource(
                            if (split) R.string.installer_split_start_title
                            else R.string.installer_start_title
                        ),
                        stringResource(
                            if (split) R.string.installer_split_start_body
                            else R.string.installer_start_body
                        ),
                        actionLabel = stringResource(R.string.installer_choose),
                        enabled = connected && !reading,
                        onAction = choose,
                    )
                }
            }
            items(state.results) { InstallResultCard(it) }
            if (reading)
                item(key = "reading") { Working(stringResource(R.string.installer_reading)) }
            if (state.files.isNotEmpty()) {
                item(key = "files") {
                    ListGroup(title = stringResource(R.string.installer_files)) {
                        state.files.forEach { file ->
                            FileRow(file, state.bundles[file.uri], removable = connected && !busy) {
                                viewModel.remove(file)
                            }
                        }
                        NavigationRow(
                            stringResource(R.string.installer_add_more),
                            onClick = choose,
                            icon = AppIcons.Add,
                            enabled = connected && !busy,
                            trailingIcon = null,
                        )
                    }
                }
                item(key = "options") {
                    ListGroup(title = stringResource(R.string.installer_options)) {
                        InstallOptions(state.options, connected && !busy, viewModel::setOptions)
                    }
                }
                item(key = "install") {
                    Column(verticalArrangement = GrowingGaps(8.dp)) {
                        RevealValue(state.progress) { Progress(it) }
                        Button(
                            onClick = viewModel::install,
                            enabled = connected && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.installer_install))
                        }
                    }
                }
            } else if (state.results.isNotEmpty()) {
                item(key = "again") {
                    Button(
                        onClick = choose,
                        enabled = connected && !reading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.installer_choose))
                    }
                }
            }
        }
    }
}

// No two stores agree on a type for these files, and most report something generic. What's inside
// decides, so anything may be picked and the wrong kind of file is told as such.
private val PACKAGE_TYPES = arrayOf("*/*")

@Composable
private fun FileRow(
    file: PickedFile,
    bundle: ToolboxBundle?,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        headlineContent = {
            FadeSwitch(bundle?.label ?: file.name) {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            FadeSwitch(bundle, key = { it != null }) { shown ->
                if (shown == null) Text(context.fileSizeText(file.size)) else BundleSummary(shown)
            }
        },
        leadingContent = { AppIcon(AppIcons.File, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onRemove, enabled = removable) {
                AppIcon(AppIcons.Close, stringResource(R.string.installer_remove, file.name))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun BundleSummary(bundle: ToolboxBundle) {
    val context = LocalContext.current
    Column {
        Text(
            stringResource(
                R.string.installer_bundle_summary,
                bundle.apks.size,
                bundle.apks.size + bundle.skippedApks,
                context.fileSizeText(bundle.apkBytes),
            )
        )
        if (bundle.data.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.installer_bundle_data,
                    context.fileSizeText(bundle.dataBytes),
                )
            )
        }
    }
}

@Composable
private fun InstallOptions(
    options: ToolboxInstallOptions,
    enabled: Boolean,
    onChange: (ToolboxInstallOptions) -> Unit,
) {
    SwitchRow(
        stringResource(R.string.installer_option_replace),
        checked = options.replace,
        onCheckedChange = { onChange(options.copy(replace = it)) },
        enabled = enabled,
    )
    SwitchRow(
        stringResource(R.string.installer_option_downgrade),
        checked = options.allowDowngrade,
        onCheckedChange = { onChange(options.copy(allowDowngrade = it)) },
        enabled = enabled,
    )
    SwitchRow(
        stringResource(R.string.installer_option_permissions),
        checked = options.grantRuntimePermissions,
        onCheckedChange = { onChange(options.copy(grantRuntimePermissions = it)) },
        enabled = enabled,
    )
}

@Composable
private fun Progress(progress: InstallProgress) {
    Working(
        stringResource(
            if (progress.copyingData) R.string.installer_copying_data
            else R.string.installer_installing,
            progress.name,
        ),
        progress.fraction,
    )
}

@Composable
private fun Working(label: String, fraction: Float? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FadingText(label, style = MaterialTheme.typography.bodyMedium)
        ProgressBar(fraction)
    }
}

@Composable
private fun InstallResultCard(result: InstallResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (result.kind) {
            InstallResultKind.INSTALLED ->
                StatusBanner(
                    stringResource(R.string.installer_result_installed, result.name),
                    tone = BannerTone.Success,
                )
            InstallResultKind.FAILED ->
                StatusBanner(
                    result.message?.asString()
                        ?: stringResource(R.string.installer_result_failed_body),
                    tone = BannerTone.Error,
                    title = stringResource(R.string.installer_result_failed, result.name),
                )
            InstallResultKind.UNKNOWN ->
                StatusBanner(
                    stringResource(R.string.installer_result_unknown_body),
                    tone = BannerTone.Warning,
                    title = stringResource(R.string.installer_result_unknown, result.name),
                )
            InstallResultKind.INSTALLED_WITHOUT_DATA ->
                StatusBanner(
                    stringResource(
                        R.string.installer_result_no_data_body,
                        result.message?.asString().orEmpty(),
                    ),
                    tone = BannerTone.Warning,
                    title = stringResource(R.string.installer_result_no_data, result.name),
                )
        }
        if (result.kind != InstallResultKind.INSTALLED) {
            result.output?.let { CommandOutput(it) }
        }
    }
}

/** What the watch's package manager or a command said, as it said it. */
@Composable
internal fun CommandOutput(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
