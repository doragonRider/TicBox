package dev.ticbox.companion.ui.toolbox

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Clipboards hold passwords and the like, so their text stays in memory: never saved or logged. */
class ClipboardViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
) : SessionViewModel(dependencies) {
    private val read = MutableStateFlow<String?>(null)
    private val draftText = MutableStateFlow("")

    /** The watch's clipboard when last read; empty is an answer too, so null means not yet. */
    val fromWatch: StateFlow<String?> = read.asStateFlow()
    val draft: StateFlow<String> = draftText.asStateFlow()

    fun setDraft(text: String) {
        draftText.value = text
    }

    fun readFromWatch() {
        val backend = runCatching { toolbox.backend() }
        command(READ) {
            val pinned = backend.getOrThrow()
            val text = reportingTimeouts { pinned.clipboard() }
            toolbox.backend(pinned.state.value.target)
            read.value = text
            null
        }
    }

    fun setOnWatch() {
        val backend = runCatching { toolbox.backend() }
        val text = draftText.value
        command(SET) {
            reportingTimeouts { backend.getOrThrow().setClipboard(text) }
            uiText(R.string.clipboard_sent)
        }
    }

    /** The key reaches whatever has focus there, so all this can report is that it was pressed. */
    fun pasteOnWatch() {
        val backend = runCatching { toolbox.backend() }
        command(PASTE) {
            val result = reportingTimeouts { backend.getOrThrow().execute(PRESS_PASTE) }
            uiText(
                if (result.successful) R.string.clipboard_paste_sent
                else R.string.clipboard_paste_failed
            )
        }
    }

    companion object {
        const val READ = "toolbox_clipboard_read"
        const val SET = "toolbox_clipboard_set"
        const val PASTE = "toolbox_clipboard_paste"
        private const val PRESS_PASTE = "input keyevent KEYCODE_PASTE"
    }
}

@Composable
fun ClipboardScreen(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val viewModel = companionViewModel { ClipboardViewModel(it, toolbox) }
    val fromWatch by viewModel.fromWatch.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val context = LocalContext.current
    val phone = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    PageScaffold(
        stringResource(R.string.tool_clipboard_title),
        onBack = onBack,
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        PageColumn(padding) {
            ToolHero(AppIcons.Paste, stringResource(R.string.clipboard_intro))
            ToolPanel(
                AppIcons.Download,
                stringResource(R.string.clipboard_read_title),
                stringResource(R.string.clipboard_read_body),
            ) {
                ToolButton(
                    AppIcons.Copy,
                    stringResource(R.string.clipboard_read),
                    onClick = viewModel::readFromWatch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected && ClipboardViewModel.READ !in pending,
                )
                RevealValue(fromWatch) { clip ->
                    FadeSwitch(clip, Modifier.fillMaxWidth()) { text ->
                        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                            if (text.isEmpty()) {
                                FootnoteText(stringResource(R.string.clipboard_empty))
                            } else {
                                SelectionContainer { CommandOutput(text) }
                                ToolButton(
                                    AppIcons.Phone,
                                    stringResource(R.string.clipboard_copy_to_phone),
                                    onClick = {
                                        phone.setPrimaryClip(ClipData.newPlainText("watch", text))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = connected,
                                    tonal = true,
                                )
                            }
                        }
                    }
                }
            }
            ToolPanel(
                AppIcons.Paste,
                stringResource(R.string.clipboard_send_title),
                stringResource(R.string.clipboard_send_body),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::setDraft,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected,
                    minLines = 3,
                    placeholder = { Text(stringResource(R.string.clipboard_text_hint)) },
                    shape = RoundedCornerShape(20.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val roomForBothLabels = PaddingValues(horizontal = 8.dp)
                    ToolButton(
                        AppIcons.Paste,
                        stringResource(R.string.clipboard_set),
                        onClick = viewModel::setOnWatch,
                        modifier = Modifier.weight(1f),
                        enabled = connected && ClipboardViewModel.SET !in pending,
                        contentPadding = roomForBothLabels,
                    )
                    ToolButton(
                        AppIcons.Send,
                        stringResource(R.string.clipboard_paste),
                        onClick = viewModel::pasteOnWatch,
                        modifier = Modifier.weight(1f),
                        enabled = connected && ClipboardViewModel.PASTE !in pending,
                        tonal = true,
                        contentPadding = roomForBothLabels,
                    )
                }
            }
        }
    }
}
