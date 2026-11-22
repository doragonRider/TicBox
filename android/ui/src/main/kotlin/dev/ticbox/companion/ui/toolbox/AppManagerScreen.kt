package dev.ticbox.companion.ui.toolbox

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxAppAction
import dev.ticbox.companion.api.ToolboxAppRequest
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxCommandResult
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppBottomSheet
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.InlineValueRow
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppManagerUiState(
    /** Null until the watch has answered. */
    val apps: List<ToolboxApp>? = null,
    val loading: Boolean = false,
    val problem: UiText? = null,
    val system: Boolean = false,
    val query: String = "",
    /** The app being backed up and how far along, while that runs. */
    val backup: InstallProgress? = null,
) {
    val visible: List<ToolboxApp>
        get() =
            apps
                .orEmpty()
                .filter { it.system == system }
                .filter { app ->
                    val text = query.trim()
                    text.isEmpty() ||
                        app.label.contains(text, ignoreCase = true) ||
                        app.packageName.contains(text, ignoreCase = true)
                }
                .sortedBy { it.label.lowercase() }
}

class AppManagerViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
    private val documents: ToolboxDocuments,
) : SessionViewModel(dependencies) {
    private val current = MutableStateFlow(AppManagerUiState())
    val state: StateFlow<AppManagerUiState> = current.asStateFlow()
    private var loading: Job? = null
    private var backupTarget: ToolboxApp? = null

    /** What the watch already said on this connection, or else asks it. */
    fun show() {
        val known = toolbox.currentTarget()?.let { toolbox.memory()?.apps(it) }
        current.update { it.copy(apps = known, loading = false, problem = null) }
        if (known == null) refresh()
    }

    /** What's on the page stays there meanwhile, and if the watch doesn't answer. */
    fun refresh() {
        loading?.cancel()
        current.update { it.copy(loading = true, problem = null) }
        loading = viewModelScope.launch {
            try {
                val backend = toolbox.backend()
                val target = backend.state.value.target
                val apps = reportingTimeouts { backend.apps() }
                toolbox.backend(target)
                target?.let { toolbox.memory()?.rememberApps(it, apps) }
                current.update { it.copy(apps = apps, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (current.value.apps != null) messages.show(problem.toUiText())
                current.update { it.copy(loading = false, problem = problem.toUiText()) }
            }
        }
    }

    fun showSystem(system: Boolean) = current.update { it.copy(system = system) }

    fun setQuery(query: String) = current.update { it.copy(query = query) }

    fun act(app: ToolboxApp, action: ToolboxAppAction, keepData: Boolean = false) =
        command(ACTION) {
            val result = reportingTimeouts {
                toolbox
                    .backend(app.target ?: throw ToolboxException("connection_replaced"))
                    .app(ToolboxAppRequest(app.packageName, action, keepData = keepData))
            }
            // A failure may just mean the list was out of date, which shows once it's read again.
            if (action != ToolboxAppAction.START || !result.successful) {
                toolbox.memory()?.forgetApps()
                withContext(Dispatchers.Main) { refresh() }
            }
            result.summary()
        }

    fun setPermission(app: ToolboxApp, permission: String, grant: Boolean) =
        command(ACTION) {
            reportingTimeouts {
                toolbox
                    .backend(app.target ?: throw ToolboxException("connection_replaced"))
                    .permission(app.packageName, permission, grant)
            }
                .summary()
        }

    fun setAppOp(app: ToolboxApp, operation: String, mode: String) =
        command(ACTION) {
            reportingTimeouts {
                toolbox
                    .backend(app.target ?: throw ToolboxException("connection_replaced"))
                    .appOp(app.packageName, operation, mode)
            }
                .summary()
        }

    /** Which app the system's save dialog is for. Survives the activity going away meanwhile. */
    fun prepareBackup(app: ToolboxApp) {
        backupTarget = app
    }

    fun backUp(uri: Uri) {
        val app = backupTarget ?: return
        backupTarget = null
        command(BACKUP) {
            current.update { it.copy(backup = InstallProgress(app.label, null)) }
            try {
                val backend =
                    toolbox.backend(app.target ?: throw ToolboxException("connection_replaced"))
                documents.writeTo(uri) { output ->
                    backend.backupAppData(app.packageName, output) { progress ->
                        val fraction = fractionOf(progress.completedBytes, progress.totalBytes)
                        current.update {
                            it.copy(backup = InstallProgress(app.label, fraction))
                        }
                    }
                }
                uiText(R.string.apps_backup_saved, app.label)
            } finally {
                current.update { it.copy(backup = null) }
            }
        }
    }

    /** Icons come over the companion link, so an app simply has none while that's down. */
    suspend fun icon(packageName: String): ImageBitmap? {
        val icons = toolbox.memory()?.icons
        icons?.get(packageName)?.let {
            return it
        }
        val client = session.value?.services?.deviceSettings ?: return null
        return try {
            val bytes = client.appIcon(packageName) ?: return null
            withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                ?.also { icons?.put(packageName, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACTION = "toolbox_app_action"
        const val BACKUP = "toolbox_app_backup"
    }
}

private fun ToolboxCommandResult.summary(): UiText =
    if (successful) {
        uiText(R.string.action_done)
    } else {
        val said =
            (stderr.takeIf { it.isNotEmpty() } ?: stdout)
                .toString(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
        if (said != null) uiText(R.string.apps_action_failed_said, said)
        else uiText(R.string.apps_action_failed, exitCode)
    }

private sealed interface AppDialog {
    data class Confirm(
        val title: Int,
        val body: Int,
        val confirm: Int,
        val action: ToolboxAppAction,
    ) : AppDialog

    data object Uninstall : AppDialog

    data object Permission : AppDialog

    data object AppOp : AppDialog
}

@Composable
fun AppManagerScreen(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val context = LocalContext.current
    val documents =
        remember(context) { ToolboxDocuments(context.applicationContext.contentResolver) }
    val viewModel = companionViewModel(graph) { AppManagerViewModel(it, toolbox, documents) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val backend = access?.selected
    var details by remember { mutableStateOf<ToolboxApp?>(null) }
    var dialog by remember { mutableStateOf<Pair<ToolboxApp, AppDialog>?>(null) }
    val visible = remember(state) { state.visible }
    val backupSaver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-tar")
        ) { uri ->
            uri?.let(viewModel::backUp)
        }
    LaunchedEffect(connected, backend, access?.target) {
        details = null
        dialog = null
        if (connected) viewModel.show()
    }
    PageScaffold(
        stringResource(R.string.tool_apps_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Sync,
                stringResource(R.string.preferences_refresh),
                viewModel::refresh,
                enabled = connected && !state.loading,
            )
        },
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        PageList(padding, spacing = 8.dp) {
            item(key = "tabs") { AppTabs(state.system, connected, viewModel::showSystem) }
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    enabled = connected,
                    singleLine = true,
                    leadingIcon = { AppIcon(AppIcons.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                    shape = RoundedCornerShape(28.dp),
                )
            }
            state.backup?.let { backup ->
                item(key = "backup") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.apps_backing_up, backup.name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ProgressBar(backup.fraction)
                    }
                }
            }
            when {
                !connected ->
                    item(key = "offline") {
                        ToolStart(
                            AppIcons.Apps,
                            stringResource(R.string.apps_offline_title),
                            stringResource(R.string.apps_offline_body),
                            enabled = false,
                        )
                    }
                state.problem != null && state.apps == null ->
                    item(key = "problem") {
                        EmptyState(
                            AppIcons.Warning,
                            stringResource(R.string.apps_problem_title),
                            state.problem?.asString().orEmpty(),
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = viewModel::refresh,
                        )
                    }
                state.apps == null ->
                    item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                visible.isEmpty() ->
                    item(key = "empty") {
                        EmptyState(
                            AppIcons.Apps,
                            stringResource(R.string.apps_empty_title),
                            stringResource(R.string.apps_empty_body),
                        )
                    }
                else ->
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(app, viewModel, onClick = { details = app })
                    }
            }
        }
    }
    details?.let { app ->
        AppSheet(
            app,
            viewModel,
            connected,
            canClearCache = access?.has(ToolboxCapability.CLEAR_APP_CACHE) == true,
            canBackUp = access?.has(ToolboxCapability.PRIVATE_APP_DATA) == true,
            protectedHost =
                app.packageName in
                    (if (backend == ToolboxBackendKind.ADB) access?.adb else access?.rootBridge)
                        ?.capabilities
                        ?.protectedPackages
                        .orEmpty(),
            onDismiss = { details = null },
            onDialog = { dialog = app to it },
            onBackUp = {
                viewModel.prepareBackup(app)
                backupSaver.launch("${app.packageName}.tar")
            },
        )
    }
    dialog?.let { (app, open) ->
        AppDialogs(app, open, viewModel, onDismiss = { dialog = null })
    }
}

@Composable
private fun AppTabs(system: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val tabs =
        listOf(
            Triple(false, R.string.apps_tab_user, AppIcons.Person),
            Triple(true, R.string.apps_tab_system, AppIcons.Settings),
        )
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (value, label, icon) ->
            ToolButton(
                icon,
                stringResource(label),
                onClick = { onChange(value) },
                modifier =
                    Modifier.weight(1f).fillMaxHeight().semantics { selected = system == value },
                enabled = enabled,
                tonal = system != value,
            )
        }
    }
}

@Composable
private fun AppIconImage(packageName: String, viewModel: AppManagerViewModel) {
    val icon by
        produceState<ImageBitmap?>(null, packageName) { value = viewModel.icon(packageName) }
    Crossfade(icon, label = "app_icon") { image ->
        if (image != null) {
            Image(image, contentDescription = null, Modifier.size(40.dp))
        } else {
            AppIcon(
                AppIcons.Apps,
                contentDescription = null,
                modifier = Modifier.size(40.dp).padding(8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppRow(app: ToolboxApp, viewModel: AppManagerViewModel, onClick: () -> Unit) {
    ToolListItem(
        headline = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supporting = {
            Text(
                if (app.enabled) app.packageName
                else stringResource(R.string.apps_row_disabled, app.packageName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leading = { AppIconImage(app.packageName, viewModel) },
        onClick = onClick,
    )
}

@Composable
private fun AppSheet(
    app: ToolboxApp,
    viewModel: AppManagerViewModel,
    connected: Boolean,
    canClearCache: Boolean,
    canBackUp: Boolean,
    protectedHost: Boolean,
    onDismiss: () -> Unit,
    onDialog: (AppDialog) -> Unit,
    onBackUp: () -> Unit,
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val enabled = connected && !protectedHost && AppManagerViewModel.ACTION !in pending
    AppBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (protectedHost)
                FootnoteText(
                    stringResource(R.string.toolbox_host_protected),
                    Modifier.padding(24.dp),
                )
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppIconImage(app.packageName, viewModel)
                Column(Modifier.weight(1f)) {
                    Text(app.label.breaksAtDots(), style = MaterialTheme.typography.titleMedium)
                    // Apps without a name of their own go by their package name.
                    if (app.label != app.packageName) {
                        Text(
                            app.packageName.breaksAtDots(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            InlineValueRow(
                stringResource(R.string.apps_detail_version),
                "${app.versionName ?: "–"} (${app.versionCode})",
            )
            InlineValueRow(stringResource(R.string.apps_detail_uid), app.uid.toString())
            app.dataDirectory?.let {
                InlineValueRow(stringResource(R.string.apps_detail_data), it.breaksAtDots())
            }
            app.apkPaths.firstOrNull()?.let {
                InlineValueRow(stringResource(R.string.apps_detail_apk), it.breaksAtDots())
            }
            val act: (ToolboxAppAction) -> Unit = { action ->
                onDismiss()
                viewModel.act(app, action)
            }
            ActionRow(R.string.apps_open, AppIcons.OpenExternal, enabled) {
                act(ToolboxAppAction.START)
            }
            ActionRow(R.string.apps_force_stop, AppIcons.Stop, enabled) {
                act(ToolboxAppAction.FORCE_STOP)
            }
            if (app.enabled) {
                ActionRow(R.string.apps_disable, AppIcons.Block, enabled) {
                    act(ToolboxAppAction.DISABLE)
                }
            } else {
                ActionRow(R.string.apps_enable, AppIcons.Check, enabled) {
                    act(ToolboxAppAction.ENABLE)
                }
            }
            ActionRow(R.string.apps_suspend, AppIcons.Paused, enabled) {
                act(ToolboxAppAction.SUSPEND)
            }
            ActionRow(R.string.apps_unsuspend, AppIcons.Playing, enabled) {
                act(ToolboxAppAction.UNSUSPEND)
            }
            ActionRow(R.string.apps_permission, AppIcons.Privacy, enabled) {
                onDismiss()
                onDialog(AppDialog.Permission)
            }
            ActionRow(R.string.apps_app_op, AppIcons.Tune, enabled) {
                onDismiss()
                onDialog(AppDialog.AppOp)
            }
            ActionRow(
                R.string.apps_clear_cache,
                AppIcons.Sync,
                enabled && canClearCache,
                summary = if (canClearCache) null else stringResource(R.string.toolbox_needs_root),
            ) {
                act(ToolboxAppAction.CLEAR_CACHE)
            }
            ActionRow(
                R.string.apps_back_up,
                AppIcons.Archive,
                connected && !protectedHost && canBackUp && AppManagerViewModel.BACKUP !in pending,
                summary = if (canBackUp) null else stringResource(R.string.toolbox_needs_root),
            ) {
                onDismiss()
                onBackUp()
            }
            ActionRow(R.string.apps_clear_data, AppIcons.Delete, enabled) {
                onDismiss()
                onDialog(
                    AppDialog.Confirm(
                        R.string.apps_clear_data_title,
                        R.string.apps_clear_data_body,
                        R.string.apps_clear_data,
                        ToolboxAppAction.CLEAR_DATA,
                    )
                )
            }
            ActionRow(R.string.apps_uninstall, AppIcons.Delete, enabled) {
                onDismiss()
                onDialog(AppDialog.Uninstall)
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: Int,
    @DrawableRes icon: Int,
    enabled: Boolean,
    summary: String? = null,
    onClick: () -> Unit,
) {
    NavigationRow(
        stringResource(label),
        onClick = onClick,
        icon = icon,
        summary = summary,
        enabled = enabled,
        trailingIcon = null,
    )
}

@Composable
private fun AppDialogs(
    app: ToolboxApp,
    dialog: AppDialog,
    viewModel: AppManagerViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is AppDialog.Confirm ->
            ConfirmDialog(
                stringResource(dialog.title, app.label),
                stringResource(dialog.body),
                stringResource(dialog.confirm),
                onConfirm = {
                    onDismiss()
                    viewModel.act(app, dialog.action)
                },
                onDismiss = onDismiss,
            )
        AppDialog.Uninstall -> UninstallDialog(app, viewModel, onDismiss)
        AppDialog.Permission -> PermissionDialog(app, viewModel, onDismiss)
        AppDialog.AppOp -> AppOpDialog(app, viewModel, onDismiss)
    }
}

@Composable
private fun UninstallDialog(
    app: ToolboxApp,
    viewModel: AppManagerViewModel,
    onDismiss: () -> Unit,
) {
    var keepData by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_uninstall_title, app.label)) },
        text = {
            Row(
                Modifier.fillMaxWidth().clickable { keepData = !keepData },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = keepData, onCheckedChange = { keepData = it })
                Text(stringResource(R.string.apps_keep_data))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.act(app, ToolboxAppAction.UNINSTALL, keepData)
                }
            ) {
                Text(stringResource(R.string.apps_uninstall))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PermissionDialog(
    app: ToolboxApp,
    viewModel: AppManagerViewModel,
    onDismiss: () -> Unit,
) {
    var permission by remember { mutableStateOf("android.permission.") }
    val valid = permission.isNotBlank() && !permission.endsWith('.')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_permission)) },
        text = {
            OutlinedTextField(
                value = permission,
                onValueChange = { permission = it },
                singleLine = true,
                label = { Text(stringResource(R.string.apps_permission_name)) },
            )
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.setPermission(app, permission.trim(), grant = false)
                    },
                    enabled = valid,
                ) {
                    Text(stringResource(R.string.apps_permission_revoke))
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.setPermission(app, permission.trim(), grant = true)
                    },
                    enabled = valid,
                ) {
                    Text(stringResource(R.string.apps_permission_grant))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val APP_OP_MODES = listOf("allow", "ignore", "deny", "default")

@Composable
private fun AppOpDialog(app: ToolboxApp, viewModel: AppManagerViewModel, onDismiss: () -> Unit) {
    var operation by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(APP_OP_MODES.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_app_op)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = operation,
                    onValueChange = { operation = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.apps_app_op_name)) },
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    APP_OP_MODES.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = mode == option,
                            onClick = { mode = option },
                            shape = SegmentedButtonDefaults.itemShape(index, APP_OP_MODES.size),
                            icon = {},
                        ) {
                            Text(
                                option,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.setAppOp(app, operation.trim(), mode)
                },
                enabled = operation.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
