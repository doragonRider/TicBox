package dev.ticbox.companion.ui.toolbox

import android.content.res.Configuration
import android.media.MediaFormat
import android.net.Uri
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenInput
import dev.ticbox.companion.api.ToolboxScreenOptions
import dev.ticbox.companion.api.ToolboxScreenSession
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDecimal
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.MaxContentWidth
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageGutter
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScreenPhase {
    STOPPED,
    STARTING,
    LIVE,
}

data class RemoteScreenUiState(
    val phase: ScreenPhase = ScreenPhase.STOPPED,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val touch: Boolean = true,
    val problem: UiText? = null,
    /** Only while frame statistics are turned on. */
    val graphs: ScreenStatsHistory? = null,
)

private data class StreamRequest(
    val surface: Surface,
    val options: ToolboxScreenOptions,
    val target: dev.ticbox.companion.api.ToolboxTarget,
    val overWifi: Boolean,
    val smoothPlayback: Boolean,
)

private data class ScreenDemand(
    val wanted: Boolean,
    val connected: Boolean,
    val target: dev.ticbox.companion.api.ToolboxTarget?,
    val udpRequested: Boolean,
    val request: StreamRequest?,
)

/**
 * Runs only while there is a surface to draw on and the user wants the mirror on. Leaving the page
 * for a while pauses it; coming back resumes it, with any settings changed in the meantime.
 */
class RemoteScreenViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
    private val documents: ToolboxDocuments,
    private val wifi: ScreenWifiLock,
    private val displayRefreshRate: () -> Float? = { null },
    private val fastestRefreshRate: () -> Float? = { null },
) : SessionViewModel(dependencies) {
    private val preferences = dependencies.preferences
    val settings: StateFlow<RemoteScreenSettings> = preferences.remoteScreen

    private val current = MutableStateFlow(RemoteScreenUiState())
    val state: StateFlow<RemoteScreenUiState> = current.asStateFlow()

    private val wanted = MutableStateFlow(false)
    private val visible = MutableStateFlow(true)
    private val surface = MutableStateFlow<Surface?>(null)
    private var screenshotTarget: dev.ticbox.companion.api.ToolboxTarget? = null

    @Volatile private var actions: ScreenControlQueue? = null
    // A dropped ADB connection must not silently restart UDP mirroring on the Bluetooth backend.
    @Volatile private var udpTarget: dev.ticbox.companion.api.ToolboxTarget? = null
    // Opening Advanced pauses a live stream; remember its ADB target if UDP is enabled there.
    @Volatile private var advancedResumeTarget: dev.ticbox.companion.api.ToolboxTarget? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // A change to any input reads all of them again. Each reaches combine from its own
            // collector, so the values it hands over can mix old and new: coming back from
            // Advanced,
            // the page being visible again could arrive before the surface's removal and open a
            // stream on the old surface, only to close it at once.
            combine(wanted, visible, surface, settings, toolbox.access) { _, _, _, _, _ ->
                    demand()
                }
                .distinctUntilChanged()
                .collectLatest { demand ->
                    val expectedUdpTarget =
                        udpTarget ?: advancedResumeTarget.takeIf { demand.udpRequested }
                    when {
                        demand.wanted &&
                            expectedUdpTarget != null &&
                            demand.target != expectedUdpTarget ->
                            finish(uiText(R.string.screen_udp_disconnected))
                        demand.request != null -> stream(demand.request)
                        // The mirror stays up, which is what gets it a surface back.
                        demand.wanted && demand.connected ->
                            current.update { it.copy(phase = ScreenPhase.STARTING) }
                        else -> {
                            // A lost connection ends the mirror: the next one may be another watch.
                            if (demand.wanted) wanted.value = false
                            current.update { it.copy(phase = ScreenPhase.STOPPED) }
                        }
                    }
                }
        }
    }

    private fun demand(): ScreenDemand {
        val wanted = wanted.value
        val settings = settings.value
        val access = toolbox.access.value
        val surface = surface.value
        val target = access?.target.takeIf { access?.connected == true }
        return ScreenDemand(
            wanted = wanted,
            connected = target != null,
            target = target,
            udpRequested = settings.advanced && settings.udp,
            request =
                if (wanted && visible.value && surface != null && target != null)
                    StreamRequest(
                        surface,
                        settings.options(access?.selected),
                        target,
                        overWifi = access?.selected == ToolboxBackendKind.ADB,
                        smoothPlayback = settings.smoothPlayback,
                    )
                else null,
        )
    }

    fun start() {
        advancedResumeTarget = null
        val access = toolbox.access.value
        udpTarget = access?.target?.takeIf { settings.value.options(access.selected).udp }
        current.update { it.copy(phase = ScreenPhase.STARTING, problem = null) }
        wanted.value = true
    }

    fun stop() {
        advancedResumeTarget = null
        udpTarget = null
        wanted.value = false
        current.update { it.copy(phase = ScreenPhase.STOPPED) }
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun setSurface(value: Surface?) {
        surface.value = value
    }

    fun setTouch(enabled: Boolean) = current.update { it.copy(touch = enabled) }

    fun setQuality(quality: ScreenQuality) =
        preferences.setRemoteScreen(settings.value.copy(preset = quality, advanced = false))

    /** The page is about to be left for those settings, so don't restart the stream for them. */
    fun useAdvanced() {
        advancedResumeTarget =
            toolbox.access.value?.target?.takeIf {
                wanted.value && it.kind == ToolboxBackendKind.ADB
            }
        visible.value = false
        preferences.setRemoteScreen(settings.value.copy(advanced = true))
    }

    fun touch(input: ToolboxScreenInput.Touch) {
        actions?.touch(input)
    }

    fun press(keyCode: Int) = act { session ->
        session.input(ToolboxScreenInput.Key(keyCode, down = true))
        session.input(ToolboxScreenInput.Key(keyCode, down = false))
    }

    /** Pasting, unlike injected key presses, also carries text that has no key: Chinese, say. */
    fun paste(text: String) = act { it.setClipboard(text, paste = true) }

    fun prepareScreenshot() {
        screenshotTarget = toolbox.access.value?.target
    }

    fun screenshot(uri: Uri) =
        command(SCREENSHOT) {
            val target = screenshotTarget ?: throw ToolboxException("connection_replaced")
            screenshotTarget = null
            val backend = toolbox.backend(target)
            documents.writeTo(uri) { backend.screenshot(it) }
            uiText(R.string.screen_screenshot_saved)
        }

    private fun act(action: suspend (ToolboxScreenSession) -> Unit) {
        actions?.action(action)
    }

    private suspend fun stream(request: StreamRequest) {
        udpTarget = request.target.takeIf { request.options.udp }
        advancedResumeTarget = null
        var decoder: ScreenDecoder? = null
        var audio: ScreenAudio? = null
        var videoConfiguration: ToolboxScreenEvent.Configuration? = null
        var audioConfiguration: ToolboxScreenEvent.AudioConfiguration? = null
        val queue = ScreenControlQueue(collectDiagnostics = request.options.collectDiagnostics)
        val stats =
            if (request.options.collectDiagnostics)
                ScreenFrameStats(udp = request.options.udp, targetFps = request.options.maxFps)
            else null
        current.update { it.copy(phase = ScreenPhase.STARTING, problem = null) }
        if (request.overWifi) wifi.acquire()
        try {
            reportingTimeouts { toolbox.backend(request.target).openScreen(request.options) }
                .use { session ->
                    coroutineScope {
                        val sender = launch {
                            while (queue.sendNext(session)) {
                                /* one writer */
                            }
                        }
                        val reporter = stats?.let { enabled ->
                            launch(ScreenStatsDispatcher) {
                                reportStats(session, enabled, request.options.maxFps)
                            }
                        }
                        actions = queue
                        collectScreenEvents(
                            session,
                            video = { event ->
                                when (event) {
                                    is ToolboxScreenEvent.Configuration -> {
                                        // UDP recovery repeats codec metadata with keyframes.
                                        if (!request.options.udp || videoConfiguration != event) {
                                            if (session.requiresVideoDecoderReady)
                                                current.update {
                                                    it.copy(phase = ScreenPhase.STARTING)
                                                }
                                            val preparationStarted =
                                                if (stats != null) System.nanoTime() else null
                                            decoder?.close()
                                            decoder =
                                                openDecoder(
                                                    request.surface,
                                                    event,
                                                    stats,
                                                    request.options.maxFps,
                                                    request.smoothPlayback,
                                                )
                                            videoConfiguration = event
                                            if (session.requiresVideoDecoderReady)
                                                checkNotNull(decoder).awaitReady()
                                            currentCoroutineContext().ensureActive()
                                            session.videoDecoderReady(
                                                event,
                                                preparationStarted?.let {
                                                    (System.nanoTime() - it).coerceAtLeast(0) /
                                                        1_000_000
                                                } ?: 0,
                                            )
                                        }
                                        current.update {
                                            it.copy(
                                                phase =
                                                    if (session.requiresVideoDecoderReady) it.phase
                                                    else ScreenPhase.LIVE,
                                                frameWidth = event.width,
                                                frameHeight = event.height,
                                            )
                                        }
                                    }
                                    is ToolboxScreenEvent.Frame -> {
                                        decoder?.queue(event)
                                        if (!event.codecConfig && event.bytes.isNotEmpty())
                                            current.update { it.copy(phase = ScreenPhase.LIVE) }
                                    }
                                    else -> Unit
                                }
                            },
                            audio = { event ->
                                when (event) {
                                    is ToolboxScreenEvent.AudioConfiguration -> {
                                        if (audioConfiguration != event) {
                                            audio?.close()
                                            audio = null
                                            audioConfiguration = event
                                            if (event.codec == OPUS) {
                                                audio = ScreenAudio(onFailure = ::soundStopped)
                                            } else {
                                                show(uiText(R.string.screen_audio_unavailable))
                                            }
                                        }
                                    }
                                    is ToolboxScreenEvent.AudioFrame -> audio?.queue(event)
                                    else -> Unit
                                }
                            },
                        )
                        sender.cancel()
                        reporter?.cancel()
                    }
                }
            finish(uiText(R.string.screen_ended))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            // Losing the surface fails the decoder too, but that only means the page was left.
            if (surface.value === request.surface) finish(problem.toUiText())
        } finally {
            actions = null
            queue.close()
            decoder?.close()
            audio?.close()
            wifi.release()
            current.update { it.copy(graphs = null) }
        }
    }

    /** Runs on [ScreenStatsDispatcher], away from the UI, the decoder and the controls. */
    private suspend fun reportStats(
        session: ToolboxScreenSession,
        stats: ScreenFrameStats,
        targetFps: Int,
    ) {
        var ticks = 0
        var history = ScreenStatsHistory(targetFps)
        while (true) {
            delay(STATS_INTERVAL_MILLIS)
            val now = System.nanoTime()
            val report = ++ticks % 2 == 0
            stats.displayRefreshRate(displayRefreshRate()?.toDouble())
            val tick = stats.tick(now)
            val snapshot = stats.summary(now)
            if (report) runCatching { session.reportPlayback(snapshot.playback()) }
            val timing = runCatching { session.timing() }.getOrNull()
            history +=
                ScreenStatsSample(
                    fps = tick.shownFps.toFloat(),
                    low1Fps = snapshot.presentation?.low1Fps?.toFloat(),
                    roundTripMillis = timing?.roundTripMicros?.let { it / 1_000f },
                    encoderMillis = timing?.senderMicros?.let { it / 1_000f },
                    decoderMillis = tick.decoderMicros?.let { it / 1_000f },
                )
            currentCoroutineContext().ensureActive()
            current.update { it.copy(graphs = history) }
        }
    }

    private fun openDecoder(
        surface: Surface,
        configuration: ToolboxScreenEvent.Configuration,
        stats: ScreenFrameStats?,
        targetFps: Int,
        smoothPlayback: Boolean,
    ): ScreenDecoder {
        val mime =
            when (configuration.codec.lowercase()) {
                "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
                "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
                else -> throw ToolboxException("screen_decoder_failed")
            }
        return try {
            ScreenDecoder(
                surface,
                configuration.width,
                configuration.height,
                mime,
                stats = stats,
                targetFps = targetFps,
                fastestRefreshHz = fastestRefreshRate(),
                smoothPlayback = smoothPlayback,
            )
        } catch (problem: Exception) {
            throw ToolboxException("screen_decoder_failed", problem)
        }
    }

    private fun soundStopped() = show(uiText(R.string.screen_audio_stopped))

    private fun show(problem: UiText) = current.update { it.copy(problem = problem) }

    private fun finish(problem: UiText) {
        advancedResumeTarget = null
        udpTarget = null
        wanted.value = false
        current.update { it.copy(phase = ScreenPhase.STOPPED, problem = problem) }
    }

    companion object {
        const val SCREENSHOT = "toolbox_screenshot"
        private const val OPUS = "opus"
        private const val STATS_INTERVAL_MILLIS = 1_000L
    }
}

@Composable
fun RemoteScreenScreen(graph: ViewModelStoreOwner, onBack: () -> Unit, onAdvanced: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val context = LocalContext.current
    val display = LocalView.current.display
    val documents =
        remember(context) { ToolboxDocuments(context.applicationContext.contentResolver) }
    val viewModel = companionViewModel {
        RemoteScreenViewModel(
            it,
            toolbox,
            documents,
            ScreenWifiLock(context.applicationContext),
            displayRefreshRate = { display?.refreshRate },
            fastestRefreshRate = {
                display?.mode?.let { (it.alternativeRefreshRates + it.refreshRate).max() }
            },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val declaredEncoders by toolbox.screenEncoders.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val live = connected && state.phase == ScreenPhase.LIVE
    val running = state.phase != ScreenPhase.STOPPED
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val fill by animateFloatAsState(if (fullscreen) 1f else 0f, label = "mirror_fill")
    val focus = LocalFocusManager.current
    BackHandler(fullscreen) { fullscreen = false }
    LaunchedEffect(running) { if (!running) fullscreen = false }
    SystemBarsHidden(fullscreen)
    LifecycleStartEffect(viewModel) {
        viewModel.setVisible(true)
        onStopOrDispose { viewModel.setVisible(false) }
    }
    // Coming straight back from a page that hadn't finished opening never stopped this one.
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose {}
    }
    val saveShot =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            uri?.let(viewModel::screenshot)
        }
    PageScaffold(
        stringResource(R.string.tool_screen_title),
        onBack = onBack,
        pageScrolls = false,
        showTopBar = !fullscreen,
        actions = {
            ActionIcon(
                AppIcons.Camera,
                stringResource(R.string.screen_screenshot),
                onClick = {
                    viewModel.prepareScreenshot()
                    saveShot.launch("watch-screenshot.png")
                },
                enabled = connected && RemoteScreenViewModel.SCREENSHOT !in pending,
            )
        },
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val windowed = 1f - fill
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = fill)),
            contentAlignment = Alignment.TopCenter,
        ) {
            MirrorAndControls(
                fill,
                Modifier.consumeWindowInsets(padding)
                    .imePadding()
                    .widthUpTo(MaxContentWidth, fill)
                    .padding(
                        start = (padding.calculateStartPadding(direction) + PageGutter) * windowed,
                        top = (padding.calculateTopPadding() + 8.dp) * windowed,
                        end = (padding.calculateEndPadding(direction) + PageGutter) * windowed,
                        bottom = padding.calculateBottomPadding() * windowed,
                    ),
                mirror = {
                    RevealValue(state.problem.takeUnless { fullscreen }) {
                        StatusBanner(it.asString())
                    }
                    MirrorCard(
                        state,
                        connected,
                        viewModel,
                        fullscreen,
                        fill,
                        onFullscreen = {
                            focus.clearFocus()
                            fullscreen = true
                        },
                        Modifier.weight(1f, fill = false),
                    )
                },
            ) {
                HardwareKeys(live, viewModel::press)
                TextInputCard(live, viewModel::paste, viewModel::press)
                PresetsGroup(
                    settings,
                    settings.encoderFit(
                        access?.target?.let {
                            declaredEncoders?.get(it)?.get(settings.maxSize.coerceIn(SCREEN_SIZES))
                        }
                    ),
                    access?.selected,
                    connected,
                    viewModel::setQuality,
                    onAdvanced = {
                        viewModel.useAdvanced()
                        onAdvanced()
                    },
                )
            }
            if (fullscreen && live) MirrorTakesVolumeKeys(viewModel::press)
            AnimatedVisibility(fullscreen, enter = fadeIn(), exit = fadeOut()) {
                FloatingToolbar(live, viewModel::press, onExit = { fullscreen = false })
            }
        }
    }
}

private val ToolbarButton = 36.dp
private val ToolbarIcon = 20.dp
private val ToolbarPadding = 3.dp

/**
 * The keys of a full-screen mirror, which has no room for the page's. It folds down to one button
 * and drags out of the way of whatever is under it. Volume is left to the phone's own keys, see
 * [MirrorTakesVolumeKeys].
 */
@Composable
private fun FloatingToolbar(live: Boolean, onKey: (Int) -> Unit, onExit: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var dragged by remember { mutableStateOf(Offset.Zero) }
    var own by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val room = constraints
        fun inRoom(offset: Offset): Offset {
            val sideways = (room.maxWidth - own.width).coerceAtLeast(0) / 2f
            val upwards = (room.maxHeight - own.height).coerceAtLeast(0).toFloat()
            return Offset(offset.x.coerceIn(-sideways, sideways), offset.y.coerceIn(-upwards, 0f))
        }
        val colors = MaterialTheme.colorScheme
        MaterialSurface(
            Modifier.offset { inRoom(dragged).round() }
                .onSizeChanged { own = it }
                .pointerInput(room) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        dragged = inRoom(dragged + amount)
                    }
                },
            shape = CircleShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(ToolbarPadding), verticalAlignment = Alignment.CenterVertically) {
                ToolbarGroup(expanded, towards = Alignment.End) {
                    ToolbarKey(AppIcons.Back, R.string.screen_key_back, live) {
                        onKey(KeyEvent.KEYCODE_BACK)
                    }
                    ToolbarKey(AppIcons.Home, R.string.screen_key_home, live) {
                        onKey(KeyEvent.KEYCODE_HOME)
                    }
                }
                FilledIconButton(
                    onClick = { expanded = !expanded },
                    Modifier.size(ToolbarButton),
                ) {
                    Crossfade(expanded, label = "toolbar_toggle") {
                        if (it) {
                            AppIcon(
                                AppIcons.Close,
                                stringResource(R.string.screen_toolbar_collapse),
                                Modifier.size(ToolbarIcon),
                            )
                        } else {
                            AppIcon(
                                AppIcons.MoreHoriz,
                                stringResource(R.string.screen_toolbar_expand),
                                Modifier.size(ToolbarIcon),
                            )
                        }
                    }
                }
                ToolbarGroup(expanded, towards = Alignment.Start) {
                    ToolbarKey(AppIcons.Power, R.string.screen_key_power, live) {
                        onKey(KeyEvent.KEYCODE_POWER)
                    }
                    ToolbarKey(
                        AppIcons.FullscreenExit,
                        R.string.screen_fullscreen_exit,
                        enabled = true,
                        onClick = onExit,
                    )
                }
            }
        }
    }
}

/** The buttons either side of the toggle, sliding in and out from behind it. */
@Composable
private fun RowScope.ToolbarGroup(
    visible: Boolean,
    towards: Alignment.Horizontal,
    content: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(
        visible,
        enter = fadeIn() + expandHorizontally(expandFrom = towards),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = towards),
    ) {
        Row(content = content)
    }
}

@Composable
private fun ToolbarKey(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick, Modifier.size(ToolbarButton), enabled) {
        AppIcon(icon, stringResource(label), Modifier.size(ToolbarIcon))
    }
}

/**
 * While this is composed, the phone's volume keys press the mirrored device's instead. A key
 * nothing in the window handled reaches this listener before the window turns it into the phone's
 * volume.
 */
@Composable
private fun MirrorTakesVolumeKeys(onKey: (Int) -> Unit) {
    val view = LocalView.current
    val press by rememberUpdatedState(onKey)
    DisposableEffect(view) {
        val listener = View.OnUnhandledKeyEventListener { _, event ->
            val volume =
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            // Each repeat of a held key is a whole press, so a lost release can't stick a key.
            if (volume && event.action == KeyEvent.ACTION_DOWN) press(event.keyCode)
            volume
        }
        view.addOnUnhandledKeyEventListener(listener)
        onDispose { view.removeOnUnhandledKeyEventListener(listener) }
    }
}

/** The bars come back for a moment with a swipe from the edge, and for good on leaving. */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    DisposableEffect(hidden, window) {
        val bars = window?.takeIf { hidden }?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Only the controls scroll, so the picture holds still while reaching for them. Upright, the mirror
 * gives up height past its share, which keeps the controls in reach above the keyboard. Sideways
 * there's too little height to share, so the two sit next to each other. As [fill] reaches 1, the
 * mirror takes the whole page and the controls are gone.
 *
 * All of that happens in one layout because the mirror must never move in the composition: that
 * would destroy the video's surface, and with it the stream.
 */
@Composable
private fun MirrorAndControls(
    fill: Float,
    modifier: Modifier,
    mirror: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Column(verticalArrangement = GrowingGaps(12.dp), content = mirror)
            // The gap above stays still, so scrolled controls stop short of what's above them.
            Column(
                Modifier.graphicsLayer { alpha = 1f - fill }
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = controls,
            )
        },
    ) { (mirrorPane, controlsPane), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 16.dp.roundToPx()
        val mirrorWidth = if (sideways) lerp((width - gap) / 2, width, fill) else width
        val tallest = if (sideways) height - gap else (height * MIRROR_SHARE).roundToInt()
        val picture =
            mirrorPane.measure(
                Constraints(
                    minWidth = mirrorWidth,
                    maxWidth = mirrorWidth,
                    maxHeight = lerp(tallest, height, fill),
                )
            )
        val rest =
            controlsPane.measure(
                if (sideways)
                    Constraints.fixed((width - mirrorWidth - gap).coerceAtLeast(0), height)
                else Constraints.fixed(width, (height - picture.height - gap).coerceAtLeast(0))
            )
        layout(width, height) {
            val top = ((height - picture.height) * fill / 2).roundToInt()
            picture.place(0, top)
            if (fill < 1f) {
                if (sideways) rest.place(mirrorWidth + gap, 0)
                else rest.place(0, top + picture.height + gap)
            }
        }
    }
}

private const val MIRROR_SHARE = 0.6f

private val BadgeRoom = 144.dp

/**
 * The point on a mirror's way from 1 to 0 where it's fully covered and the badge starts to show.
 */
private const val MIRROR_GONE_AT = 0.25f

/**
 * At most [limit] wide while windowed, opening up to all the width there is as [fill] reaches 1.
 */
private fun Modifier.widthUpTo(limit: Dp, fill: Float) = layout { measurable, constraints ->
    val widest =
        lerp(limit.roundToPx().coerceAtMost(constraints.maxWidth), constraints.maxWidth, fill)
    val content =
        measurable.measure(constraints.copy(maxWidth = widest.coerceAtLeast(constraints.minWidth)))
    layout(content.width, content.height) { content.place(0, 0) }
}

/**
 * Has what's inside, which keeps to [ratio], take up less as [presence] goes from 1 to 0, until
 * it's as tall as the badge's place. Both limits move, as either can be the one holding it in.
 */
private fun Modifier.shrinking(presence: Float, ratio: Float) = layout { measurable, constraints ->
    val height =
        lerp(
            BadgeRoom.roundToPx().coerceAtMost(constraints.maxHeight),
            constraints.maxHeight,
            presence,
        )
    val width =
        lerp(
            (BadgeRoom.toPx() * ratio).roundToInt().coerceAtMost(constraints.maxWidth),
            constraints.maxWidth,
            presence,
        )
    val content = measurable.measure(constraints.copy(maxWidth = width, maxHeight = height))
    layout(content.width, content.height) { content.place(0, 0) }
}

@Composable
private fun MirrorCard(
    state: RemoteScreenUiState,
    connected: Boolean,
    viewModel: RemoteScreenViewModel,
    fullscreen: Boolean,
    fill: Float,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val running = state.phase != ScreenPhase.STOPPED
    val windowed = 1f - fill
    val background = lerp(colors.primaryContainer, Color.Black, fill)
    val presence by
        animateFloatAsState(if (running) 1f else 0f, tween(500), label = "mirror_presence")
    MaterialSurface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp * windowed),
        color = background,
        contentColor = colors.onPrimaryContainer,
    ) {
        Column {
            AnimatedVisibility(!fullscreen) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(10.dp)
                            .background(
                                animateColorAsState(
                                        if (state.phase == ScreenPhase.LIVE) colors.primary
                                        else colors.onPrimaryContainer.copy(alpha = 0.4f),
                                        label = "mirror_status",
                                    )
                                    .value,
                                CircleShape,
                            )
                    )
                    FadingText(
                        stringResource(
                            when (state.phase) {
                                ScreenPhase.STOPPED -> R.string.screen_status_stopped
                                ScreenPhase.STARTING -> R.string.screen_status_starting
                                ScreenPhase.LIVE -> R.string.screen_status_live
                            }
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    IconButton(onClick = onFullscreen, enabled = running) {
                        AppIcon(AppIcons.Fullscreen, stringResource(R.string.screen_fullscreen))
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(
                        start = 20.dp * windowed,
                        top = 2.dp * windowed,
                        end = 20.dp * windowed,
                        bottom = 16.dp * windowed,
                    )
                    .heightIn(min = BadgeRoom * (1f - presence)),
                contentAlignment = Alignment.Center,
            ) {
                if (running || presence > 0f) {
                    MirrorView(state, fill, presence, background, viewModel)
                }
                if (presence < 1f) {
                    ToolBadge(
                        AppIcons.Screen,
                        container = colors.onPrimaryContainer.copy(alpha = 0.08f),
                        tint = colors.primary,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = (1f - presence / MIRROR_GONE_AT).coerceIn(0f, 1f)
                            },
                        size = 96.dp,
                    )
                }
                ScreenStatsOverlay(state.graphs, Modifier.matchParentSize())
            }
            AnimatedVisibility(!fullscreen) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(
                        if (running) AppIcons.Stop else AppIcons.Play,
                        stringResource(
                            if (running) R.string.screen_stop else R.string.screen_start
                        ),
                        onClick = if (running) viewModel::stop else viewModel::start,
                        enabled = connected,
                    )
                    val touchLabel =
                        stringResource(
                            if (state.touch) R.string.screen_touch_on else R.string.screen_touch_off
                        )
                    FilledIconToggleButton(
                        checked = state.touch,
                        onCheckedChange = viewModel::setTouch,
                        enabled = connected,
                        modifier = Modifier.size(48.dp),
                    ) {
                        AppIcon(AppIcons.TouchControl, touchLabel)
                    }
                }
            }
        }
    }
}

/**
 * The watch's face, round when the stream is square, because that's what its display is. A
 * SurfaceView puts frames on the screen without going through this app's main thread, so nothing
 * happening here can hold them up. It sits behind the window, where neither clipping nor alpha
 * reach it, so the window paints over it instead: what's outside the face, and all of it as it
 * goes. [presence] goes from 1 to 0 as it leaves, shrinking it to the height of the badge's place.
 */
@Composable
private fun MirrorView(
    state: RemoteScreenUiState,
    fill: Float,
    presence: Float,
    background: Color,
    viewModel: RemoteScreenViewModel,
) {
    val width = state.frameWidth
    val height = state.frameHeight
    val configured = width > 0 && height > 0
    val ratio = if (configured) width.toFloat() / height else 1f
    val shape = if (ratio == 1f) CircleShape else RoundedCornerShape(16.dp * (1f - fill))
    Box(
        Modifier.widthUpTo(280.dp, fill)
            .shrinking(presence, ratio)
            .aspectRatio(ratio)
            .background(Color.Black, shape),
        contentAlignment = Alignment.Center,
    ) {
        AndroidExternalSurface(
            modifier = Modifier.matchParentSize(),
            // A fixed buffer keeps scaling to the display hardware as the mirror changes size.
            surfaceSize = if (configured) IntSize(width, height) else IntSize.Zero,
        ) {
            onSurface { surface, _, _ ->
                viewModel.setSurface(surface)
                surface.onDestroyed { viewModel.setSurface(null) }
            }
        }
        AnimatedVisibility(
            state.phase == ScreenPhase.STARTING,
            Modifier.matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val face = Path().apply { addOutline(outline) }
            clipPath(face, ClipOp.Difference) { drawRect(background) }
            drawRect(
                background.copy(alpha = ((1f - presence) / (1f - MIRROR_GONE_AT)).coerceIn(0f, 1f))
            )
        }
        if (state.phase == ScreenPhase.LIVE && state.touch && configured) {
            TouchLayer(width, height, viewModel::touch, Modifier.matchParentSize(), shape)
        }
    }
}

/** Sits above the video, so fingers on it reach the watch. */
@Composable
internal fun TouchLayer(
    frameWidth: Int,
    frameHeight: Int,
    onTouch: (ToolboxScreenInput.Touch) -> Unit,
    modifier: Modifier,
    shape: Shape = RectangleShape,
) {
    val frame by rememberUpdatedState(IntSize(frameWidth, frameHeight))
    val send by rememberUpdatedState(onTouch)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val currentBounds =
        remember(shape, density, layoutDirection) {
            ScreenTouchBounds(shape, density, layoutDirection)
        }
    val bounds by rememberUpdatedState(currentBounds)
    Box(
        // A new video configuration must update coordinates without cancelling a held finger.
        modifier.pointerInput(Unit) {
            val pressed = linkedMapOf<Long, ToolboxScreenInput.Touch>()
            try {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { change ->
                            val previous = pressed[change.id.value]
                            val inside = bounds.contains(change.position, size)
                            val action =
                                when {
                                    change.changedToDownIgnoreConsumed() && inside -> TOUCH_DOWN
                                    previous == null -> null
                                    !inside -> TOUCH_UP
                                    change.changedToUpIgnoreConsumed() -> TOUCH_UP
                                    change.pressed && change.positionChanged() -> TOUCH_MOVE
                                    else -> null
                                }
                            if (action != null) {
                                val event =
                                    if (action == TOUCH_UP && !inside) {
                                        // Release at the last valid position. Re-entry while still
                                        // held cannot start a new gesture without another DOWN.
                                        checkNotNull(previous).let {
                                            screenTouch(
                                                TOUCH_UP,
                                                it.pointerId,
                                                it.x.toFloat(),
                                                it.y.toFloat(),
                                                it.width,
                                                it.height,
                                                frame.width,
                                                frame.height,
                                            )
                                        }
                                    } else
                                        screenTouch(
                                            action,
                                            change.id.value,
                                            change.position.x,
                                            change.position.y,
                                            size.width,
                                            size.height,
                                            frame.width,
                                            frame.height,
                                        )
                                if (action == TOUCH_UP) pressed.remove(event.pointerId)
                                else pressed[event.pointerId] = event
                                send(event)
                            }
                            change.consume()
                        }
                    }
                }
            } finally {
                // Cancellation (touch disabled, page left, or system gesture) may skip UP.
                // scrcpy releases its pointer slot on UP, not on ACTION_CANCEL.
                pressed.values.forEach {
                    send(
                        screenTouch(
                            TOUCH_UP,
                            it.pointerId,
                            it.x.toFloat(),
                            it.y.toFloat(),
                            it.width,
                            it.height,
                            frame.width,
                            frame.height,
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun HardwareKeys(enabled: Boolean, onPress: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyTile(
                AppIcons.Back,
                R.string.screen_key_back,
                enabled,
                { onPress(KeyEvent.KEYCODE_BACK) },
                Modifier.weight(1f),
            )
            KeyTile(
                AppIcons.Home,
                R.string.screen_key_home,
                enabled,
                { onPress(KeyEvent.KEYCODE_HOME) },
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyTile(
                AppIcons.Power,
                R.string.screen_key_power,
                enabled,
                { onPress(KeyEvent.KEYCODE_POWER) },
                Modifier.weight(1f),
            )
            KeyTile(
                AppIcons.VolumeDown,
                R.string.screen_key_volume_down,
                enabled,
                { onPress(KeyEvent.KEYCODE_VOLUME_DOWN) },
                Modifier.weight(1f),
            )
            KeyTile(
                AppIcons.VolumeUp,
                R.string.screen_key_volume_up,
                enabled,
                { onPress(KeyEvent.KEYCODE_VOLUME_UP) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeyTile(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        AppIcon(icon, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextInputCard(enabled: Boolean, onPaste: (String) -> Unit, onPress: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    MaterialSurface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(AppIcons.Keyboard, contentDescription = null)
                Text(
                    stringResource(R.string.screen_text_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.screen_text_hint)) },
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPress(KeyEvent.KEYCODE_DEL) }, enabled = enabled) {
                    Text(stringResource(R.string.screen_key_backspace))
                }
                TextButton(onClick = { onPress(KeyEvent.KEYCODE_ENTER) }, enabled = enabled) {
                    Text(stringResource(R.string.screen_key_enter))
                }
                Spacer(Modifier.weight(1f))
                ToolButton(
                    AppIcons.Paste,
                    stringResource(R.string.screen_text_paste),
                    onClick = {
                        onPaste(text)
                        text = ""
                    },
                    enabled = enabled && text.isNotEmpty(),
                    tonal = true,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetsGroup(
    settings: RemoteScreenSettings,
    fit: ScreenEncoderFit,
    backend: ToolboxBackendKind?,
    enabled: Boolean,
    onPreset: (ScreenQuality) -> Unit,
    onAdvanced: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val preset = settings.preset ?: defaultScreenQuality(backend)
    val advancedValue = advancedValue(settings, fit)
    ListGroup {
        NavigationRow(
            stringResource(R.string.screen_quality_title),
            onClick = { expanded = !expanded },
            icon = AppIcons.Tune,
            summary = if (settings.advanced) advancedValue else qualityValue(preset),
            enabled = enabled,
            trailingIcon = AppIcons.ExpandMore,
        )
        Reveal(expanded) {
            ScreenQuality.entries.forEach { option ->
                RadioRow(
                    stringResource(option.label),
                    selected = !settings.advanced && option == preset,
                    onClick = { onPreset(option) },
                    summary = qualityValue(option),
                    enabled = enabled,
                )
            }
            RadioRow(
                stringResource(R.string.screen_advanced_title),
                selected = settings.advanced,
                onClick = onAdvanced,
                summary = advancedValue,
                enabled = enabled,
                trailingIcon = AppIcons.ChevronRight,
            )
        }
    }
}

@Composable
private fun qualityValue(quality: ScreenQuality): String =
    stringResource(
        R.string.screen_quality_value,
        formatDecimal(quality.options.bitRate / 1_000_000.0, currentLocale()),
        quality.options.maxFps,
    )

/** What the stream will ask for, the encoder's limits included once Advanced has read them. */
@Composable
private fun advancedValue(settings: RemoteScreenSettings, fit: ScreenEncoderFit): String =
    stringResource(
        R.string.screen_advanced_value,
        settings.maxSize,
        formatDecimal(fit.bitRate(settings.bitRate) / 1_000_000.0, currentLocale()),
        fit.fps(settings.maxFps),
    )
