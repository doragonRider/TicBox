package dev.ticbox.companion.ui.toolbox

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileKind
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppBottomSheet
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.InlineValueRow
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar

/** One of the dialogs a file can ask for. Only one is ever open. */
private sealed interface FileDialog {
    data object NewFolder : FileDialog

    data class Rename(val file: ToolboxFile) : FileDialog

    data class Delete(val file: ToolboxFile) : FileDialog

    data class Chmod(val file: ToolboxFile) : FileDialog

    data class Chown(val file: ToolboxFile) : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val context = LocalContext.current
    val documents =
        remember(context) {
            ToolboxDocuments(
                context.applicationContext.contentResolver,
                java.io.File(context.noBackupFilesDir, "toolbox-transfers"),
            )
        }
    val viewModel = companionViewModel(graph) { FileManagerViewModel(it, toolbox, documents) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val backend = access?.selected
    var searching by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ToolboxFile?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    val busy = FileManagerViewModel.ACTION in pending
    // A folder gone back to is where it was left, and one gone into starts at its top.
    val positions = remember { HashMap<String, LazyListState>() }
    val pull = rememberPullToRefreshState()
    val refreshing = state.loading && state.files != null

    LaunchedEffect(connected, backend, access?.target) {
        details = null
        dialog = null
        viewModel.cancelClip()
        viewModel.cancelReplace()
        if (connected) viewModel.show()
    }
    BackHandler(enabled = connected && state.path != INTERNAL_STORAGE) {
        if (!viewModel.goUp()) onBack()
    }
    val upload =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::upload)
        }
    val download =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let(viewModel::download)
        }

    val saveAgain =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let(viewModel::saveTransferTo)
        }

    PageScaffold(
        stringResource(R.string.tool_files_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Search,
                stringResource(R.string.files_search),
                onClick = {
                    searching = !searching
                    if (!searching) viewModel.setQuery("")
                },
                enabled = connected,
            )
        },
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize()
                .pullToRefresh(
                    refreshing,
                    pull,
                    enabled = connected,
                    onRefresh = viewModel::refresh,
                )
        ) {
            FadeSwitch(state, key = { it.path }) { shown ->
                val visible = remember(shown) { shown.visible }
                PageList(
                    padding,
                    state = positions.getOrPut(shown.path) { LazyListState() },
                    spacing = 8.dp,
                ) {
                    item(key = "header") {
                        FilesHeader(
                            shown,
                            visible.size,
                            connected,
                            onOpen = viewModel::open,
                            onSort = viewModel::setSort,
                            onShowHidden = viewModel::setShowHidden,
                            onUpload = {
                                viewModel.prepareUpload()
                                upload.launch(arrayOf("*/*"))
                            },
                            onNewFolder = { dialog = FileDialog.NewFolder },
                            onRefresh = viewModel::refresh,
                        )
                    }
                    if (searching && connected) {
                        item(key = "search") {
                            OutlinedTextField(
                                value = shown.query,
                                onValueChange = viewModel::setQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.files_search_hint)) },
                                shape = RoundedCornerShape(28.dp),
                            )
                        }
                    }
                    shown.transfer?.let { transfer ->
                        item(key = "transfer") {
                            TransferCard(
                                transfer,
                                viewModel::pauseTransfer,
                                viewModel::resumeTransfer,
                                viewModel::cancelTransfer,
                                { saveAgain.launch(transfer.name) },
                            )
                        }
                    }
                    shown.clip?.let { clip ->
                        item(key = "clip") {
                            ClipCard(
                                clip,
                                pasteEnabled = connected && !busy,
                                cancelEnabled = connected,
                                onPaste = viewModel::paste,
                                onCancel = viewModel::cancelClip,
                            )
                        }
                    }
                    when {
                        !connected ->
                            item(key = "offline") {
                                ToolStart(
                                    AppIcons.Disconnect,
                                    stringResource(R.string.files_offline_title),
                                    stringResource(R.string.files_offline_body),
                                    enabled = false,
                                )
                            }
                        shown.problem != null ->
                            item(key = "problem") {
                                EmptyState(
                                    AppIcons.Warning,
                                    stringResource(R.string.files_problem_title),
                                    shown.problem.asString(),
                                    actionLabel = stringResource(R.string.action_try_again),
                                    onAction = viewModel::refresh,
                                )
                            }
                        shown.files == null ->
                            item(key = "loading") {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        visible.isEmpty() ->
                            item(key = "empty") {
                                EmptyState(
                                    AppIcons.Folder,
                                    stringResource(R.string.files_empty_title),
                                    stringResource(R.string.files_empty_body),
                                )
                            }
                        else ->
                            items(visible, key = { it.path }) { file ->
                                FileRow(
                                    file,
                                    onOpen = {
                                        if (file.kind == ToolboxFileKind.FILE) {
                                            details = file
                                        } else {
                                            positions.remove(file.path)
                                            viewModel.open(file.path)
                                        }
                                    },
                                    onMore = { details = file },
                                )
                            }
                    }
                }
            }
            PullToRefreshDefaults.Indicator(
                pull,
                refreshing,
                Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            )
        }
    }

    details?.let { file ->
        FileSheet(
            file,
            canChown = access?.has(ToolboxCapability.FILE_OWNERSHIP) == true,
            enabled = connected && !busy,
            onDismiss = { details = null },
            onDownload = {
                viewModel.prepareDownload(file)
                download.launch(file.name)
            },
            onCopy = { viewModel.clip(file, move = false) },
            onMove = { viewModel.clip(file, move = true) },
            onRename = { dialog = FileDialog.Rename(file) },
            onDelete = { dialog = FileDialog.Delete(file) },
            onChmod = { dialog = FileDialog.Chmod(file) },
            onChown = { dialog = FileDialog.Chown(file) },
        )
    }
    dialog?.let { open ->
        FileDialogs(open, viewModel, onDismiss = { dialog = null })
    }
    state.replacing?.let { file ->
        ConfirmDialog(
            stringResource(R.string.files_replace_title),
            stringResource(R.string.files_replace_body, file.name),
            stringResource(R.string.files_replace),
            onConfirm = viewModel::confirmReplace,
            onDismiss = viewModel::cancelReplace,
        )
    }
}

@Composable
private fun FilesHeader(
    state: FileManagerUiState,
    shown: Int,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onSort: (FileSort) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
) {
    var places by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { places = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (state.path.startsWith(INTERNAL_STORAGE)) {
                                    R.string.files_internal_storage
                                } else {
                                    R.string.files_root
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else disabled,
                        )
                        Text(
                            sortSummary(state, shown),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else disabled,
                        )
                    }
                    AppIcon(
                        AppIcons.Dropdown,
                        stringResource(R.string.files_places),
                        tint = if (enabled) MaterialTheme.colorScheme.onSurface else disabled,
                    )
                }
                DropdownMenu(places, onDismissRequest = { places = false }) {
                    PlaceItem(R.string.files_internal_storage) {
                        places = false
                        onOpen(INTERNAL_STORAGE)
                    }
                    PlaceItem(R.string.files_root) {
                        places = false
                        onOpen(ROOT_PATH)
                    }
                }
            }
            Box {
                ActionIcon(
                    AppIcons.Sort,
                    stringResource(R.string.files_sort),
                    onClick = { sorting = true },
                    enabled = enabled,
                )
                DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                    FileSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sort.label)) },
                            onClick = {
                                sorting = false
                                onSort(sort)
                            },
                            trailingIcon = {
                                if (state.sort == sort) {
                                    AppIcon(AppIcons.Check, contentDescription = null)
                                }
                            },
                        )
                    }
                }
            }
            Box {
                ActionIcon(
                    AppIcons.More,
                    stringResource(R.string.action_more),
                    onClick = { more = true },
                    enabled = enabled,
                )
                DropdownMenu(more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_upload)) },
                        leadingIcon = { AppIcon(AppIcons.Upload, contentDescription = null) },
                        onClick = {
                            more = false
                            onUpload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_folder)) },
                        leadingIcon = { AppIcon(AppIcons.NewFolder, contentDescription = null) },
                        onClick = {
                            more = false
                            onNewFolder()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.preferences_refresh)) },
                        leadingIcon = { AppIcon(AppIcons.Refresh, contentDescription = null) },
                        onClick = {
                            more = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_show_hidden)) },
                        leadingIcon = {
                            AppIcon(
                                if (state.showHidden) AppIcons.Visible else AppIcons.Hidden,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            Checkbox(checked = state.showHidden, onCheckedChange = null)
                        },
                        onClick = {
                            more = false
                            onShowHidden(!state.showHidden)
                        },
                    )
                }
            }
        }
        if (enabled) {
            val scroll = rememberScrollState()
            // The end of a long path is the part that says where this is.
            LaunchedEffect(state.path, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pathCrumbs(state.path).forEachIndexed { index, crumb ->
                    if (index > 0) {
                        AppIcon(
                            AppIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onOpen(crumb) },
                        // Without a minimum of its own, the button keeps M3's 58dp for short names.
                        Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            if (crumb == ROOT_PATH) ROOT_PATH else crumb.substringAfterLast('/'),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

private val FileSort.label: Int
    get() =
        when (this) {
            FileSort.NAME -> R.string.files_sort_name
            FileSort.SIZE -> R.string.files_sort_size
            FileSort.DATE -> R.string.files_sort_date
        }

@Composable
private fun sortSummary(state: FileManagerUiState, shown: Int): String =
    stringResource(
        R.string.files_summary,
        shown,
        stringResource(state.sort.label),
        if (state.ascending) "↑" else "↓",
    )

@Composable
private fun FileRow(file: ToolboxFile, onOpen: () -> Unit, onMore: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ToolListItem(
        headline = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = { Text(fileSummary(file), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leading = {
            AppIcon(
                when (file.kind) {
                    ToolboxFileKind.DIRECTORY -> AppIcons.Folder
                    ToolboxFileKind.SYMLINK -> AppIcons.Link
                    ToolboxFileKind.FILE,
                    ToolboxFileKind.OTHER -> AppIcons.File
                },
                contentDescription = null,
                tint =
                    if (file.kind == ToolboxFileKind.DIRECTORY) colors.primary
                    else colors.onSurfaceVariant,
            )
        },
        onClick = onOpen,
        trailing = {
            IconButton(onClick = onMore) {
                AppIcon(AppIcons.More, stringResource(R.string.files_actions_for, file.name))
            }
        },
    )
}

@Composable
private fun fileSummary(file: ToolboxFile): String {
    val context = LocalContext.current
    val date = file.modifiedMillis.takeIf { it > 0 }?.let { formatDateTime(it, currentLocale()) }
    return when {
        file.kind == ToolboxFileKind.SYMLINK && file.linkTarget != null -> "→ ${file.linkTarget}"
        file.kind == ToolboxFileKind.DIRECTORY -> date.orEmpty()
        date == null -> context.fileSizeText(file.size)
        else -> "${context.fileSizeText(file.size)} · $date"
    }
}

@Composable
private fun TransferCard(
    transfer: FileTransfer,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onSaveAs: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (transfer.recoveryFailed)
                        stringResource(R.string.files_transfer_recovery_error)
                    else
                        stringResource(
                            if (transfer.upload) R.string.files_uploading
                            else R.string.files_downloading,
                            transfer.name,
                        ),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!transfer.paused) {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.files_pause)) }
                }
            }
            transfer.problem?.let {
                Text(it.asString(), style = MaterialTheme.typography.bodySmall)
            }
            if (transfer.paused) {
                Text(
                    stringResource(R.string.files_transfer_paused),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    if (!transfer.recoveryFailed)
                        TextButton(onClick = onResume) {
                            Text(stringResource(R.string.files_resume))
                        }
                    if (transfer.canChooseDestination) {
                        TextButton(onClick = onSaveAs) {
                            Text(stringResource(R.string.files_save_as))
                        }
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.files_discard)) }
                }
            }
            ProgressBar(transfer.fraction)
        }
    }
}

@Composable
private fun ClipCard(
    clip: FileClip,
    pasteEnabled: Boolean,
    cancelEnabled: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (clip.move) R.string.files_clip_move else R.string.files_clip_copy,
                    clip.file.name,
                ),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCancel, enabled = cancelEnabled) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onPaste, enabled = pasteEnabled) {
                Text(stringResource(R.string.files_paste_here))
            }
        }
    }
}

@Composable
private fun FileSheet(
    file: ToolboxFile,
    canChown: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onChmod: () -> Unit,
    onChown: () -> Unit,
) {
    val close: (() -> Unit) -> () -> Unit = { action ->
        {
            onDismiss()
            action()
        }
    }
    AppBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                file.name,
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            InlineValueRow(stringResource(R.string.files_detail_path), file.path)
            InlineValueRow(stringResource(R.string.files_detail_size), fileSummary(file))
            InlineValueRow(
                stringResource(R.string.files_detail_mode),
                fileModeText(file.mode),
            )
            InlineValueRow(
                stringResource(R.string.files_detail_owner),
                "${file.uid}:${file.gid}",
            )
            NavigationRow(
                stringResource(R.string.files_download),
                onClick = close(onDownload),
                icon = AppIcons.Download,
                enabled = enabled && file.kind != ToolboxFileKind.DIRECTORY,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.files_rename),
                onClick = close(onRename),
                icon = AppIcons.Edit,
                enabled = enabled,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.files_copy),
                onClick = close(onCopy),
                icon = AppIcons.Copy,
                enabled = enabled,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.files_move),
                onClick = close(onMove),
                icon = AppIcons.Folder,
                enabled = enabled,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.files_chmod),
                onClick = close(onChmod),
                icon = AppIcons.Lock,
                enabled = enabled,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.files_chown),
                onClick = close(onChown),
                icon = AppIcons.Person,
                summary = if (canChown) null else stringResource(R.string.toolbox_needs_root),
                enabled = enabled && canChown,
                trailingIcon = null,
            )
            NavigationRow(
                stringResource(R.string.action_delete),
                onClick = close(onDelete),
                icon = AppIcons.Delete,
                enabled = enabled,
                trailingIcon = null,
            )
        }
    }
}

@Composable
private fun FileDialogs(
    dialog: FileDialog,
    viewModel: FileManagerViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        FileDialog.NewFolder ->
            TextInputDialog(
                stringResource(R.string.files_new_folder),
                stringResource(R.string.files_name),
                initial = "",
                isValid = ::isFileName,
                onDismiss = onDismiss,
                onConfirm = viewModel::makeDirectory,
            )
        is FileDialog.Rename ->
            TextInputDialog(
                stringResource(R.string.files_rename),
                stringResource(R.string.files_name),
                initial = dialog.file.name,
                isValid = ::isFileName,
                onDismiss = onDismiss,
                onConfirm = { viewModel.rename(dialog.file, it) },
            )
        is FileDialog.Delete ->
            ConfirmDialog(
                stringResource(R.string.files_delete_title, dialog.file.name),
                stringResource(
                    if (dialog.file.kind == ToolboxFileKind.DIRECTORY) {
                        R.string.files_delete_folder_body
                    } else {
                        R.string.files_delete_body
                    }
                ),
                stringResource(R.string.action_delete),
                onConfirm = {
                    onDismiss()
                    viewModel.delete(dialog.file)
                },
                onDismiss = onDismiss,
            )
        is FileDialog.Chmod ->
            TextInputDialog(
                stringResource(R.string.files_chmod),
                stringResource(R.string.files_chmod_hint),
                initial = fileModeText(dialog.file.mode),
                keyboardType = KeyboardType.Number,
                monospace = true,
                isValid = { parseFileMode(it) != null },
                onDismiss = onDismiss,
                onConfirm = { text ->
                    parseFileMode(text)?.let { viewModel.chmod(dialog.file, it) }
                },
            )
        is FileDialog.Chown ->
            TextInputDialog(
                stringResource(R.string.files_chown),
                stringResource(R.string.files_chown_hint),
                initial = "${dialog.file.uid}:${dialog.file.gid}",
                monospace = true,
                isValid = { parseOwner(it) != null },
                onDismiss = onDismiss,
                onConfirm = { text ->
                    parseOwner(text)?.let { (uid, gid) -> viewModel.chown(dialog.file, uid, gid) }
                },
            )
    }
}

/** Three or four octal digits, as ls and chmod show them. */
internal fun parseFileMode(text: String): Int? =
    text
        .trim()
        .takeIf { it.length in 3..4 && it.all { digit -> digit in '0'..'7' } }
        ?.toInt(radix = 8)

/** A numeric `uid:gid`. Names mean nothing on this side of the connection. */
internal fun parseOwner(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val uid = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val gid = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return uid to gid
}

/** A dialog asking for one line of text. */
@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    isValid: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle =
                    if (monospace) {
                        MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(text.trim())
                },
                enabled = isValid(text),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
