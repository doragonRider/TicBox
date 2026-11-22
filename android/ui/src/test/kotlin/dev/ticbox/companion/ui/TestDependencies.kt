package dev.ticbox.companion.ui

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.ticbox.companion.api.CallAuthorizationAction
import dev.ticbox.companion.api.DndSyncClient
import dev.ticbox.companion.api.HealthModuleClient
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.api.MonitoringSettingsClient
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.SosClient
import dev.ticbox.companion.api.ToolboxTerminalSize
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.demo.DemoWatch
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.session.SessionController
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.toolbox.RemoteScreenSettings
import dev.ticbox.companion.ui.toolbox.ShellHost
import dev.ticbox.companion.ui.toolbox.StickyKey
import dev.ticbox.companion.ui.toolbox.TerminalDisplay
import dev.ticbox.companion.ui.toolbox.TerminalDisplayFactory
import dev.ticbox.companion.ui.toolbox.TerminalKey
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSessions(initial: SessionState) : SessionController {
    override val state = MutableStateFlow(initial)
    var retries = 0
    var demoRequests = 0

    override fun retry() {
        retries++
    }

    override fun enterDemo() {
        demoRequests++
    }

    override fun exitDemo() = Unit
}

class FakePreferences : AppPreferences {
    private val appearanceState = MutableStateFlow(AppearanceSettings(dynamicColor = false))
    private val languageState = MutableStateFlow(AppLanguage.SYSTEM)
    private val stepGoalState = MutableStateFlow<Int?>(null)
    override val appearance = appearanceState.asStateFlow()
    override val language = languageState.asStateFlow()
    override val stepGoal = stepGoalState.asStateFlow()
    override val remoteScreen = MutableStateFlow(RemoteScreenSettings())
    override val hideEmptyHealthMetrics = MutableStateFlow(false)

    override val notificationAlertsApplied = MutableStateFlow(false)

    override fun setNotificationAlertsApplied(applied: Boolean) {
        notificationAlertsApplied.value = applied
    }

    override fun setHideEmptyHealthMetrics(hide: Boolean) {
        hideEmptyHealthMetrics.value = hide
    }

    override fun setRemoteScreen(settings: RemoteScreenSettings) {
        remoteScreen.value = settings
    }

    override fun setThemeMode(mode: ThemeMode) {
        appearanceState.value = appearanceState.value.copy(themeMode = mode)
    }

    override fun setDynamicColor(enabled: Boolean) {
        appearanceState.value = appearanceState.value.copy(dynamicColor = enabled)
    }

    override fun setLanguage(language: AppLanguage) {
        languageState.value = language
    }

    override fun setStepGoal(goal: Int?) {
        stepGoalState.value = goal
    }
}

class FakePhoneApps(private val apps: List<PhoneApp> = emptyList()) : PhoneAppsSource {
    override suspend fun installedApps() = apps

    override suspend fun icon(packageName: String): ImageBitmap? = null
}

class TestDependencies(
    override val sessions: FakeSessions,
    override val phoneApps: PhoneAppsSource = FakePhoneApps(),
    override val terminalDisplays: TerminalDisplayFactory = FakeTerminalDisplays(),
) : CompanionDependencies {
    override val preferences = FakePreferences()
    override val messages = UserMessages()
    override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val shell by lazy { ShellHost(terminalDisplays, applicationScope) }
}

/** Stands in for the emulator, whose native library doesn't load on a JVM. */
class FakeTerminalDisplay(
    private val onInput: (ByteArray) -> Unit,
    private val onSize: (ToolboxTerminalSize) -> Unit,
    private val panelSize: ToolboxTerminalSize,
) : TerminalDisplay {
    private val received = ByteArrayOutputStream()
    val pressed = mutableListOf<TerminalKey>()
    val characters = mutableListOf<Char>()
    val typed = mutableListOf<String>()
    val pasted = mutableListOf<String>()
    private var onPaste: () -> Unit = {}
    @Volatile var closed = false

    /** How long the screen takes over what it's given. */
    @Volatile var showMillis = 0L

    /** Everything the terminal was given, as one text. */
    val shown: String
        get() = synchronized(received) { received.toString(Charsets.UTF_8) }

    override var ctrl by mutableStateOf(StickyKey.OFF)
    override var alt by mutableStateOf(StickyKey.OFF)

    /** What the emulator does with a key from the phone's keyboard. */
    fun keyboardSends(text: String) = onInput(text.toByteArray())

    /** What the emulator's own menu does. */
    fun requestPaste() = onPaste()

    fun reportSize(size: ToolboxTerminalSize) = onSize(size)

    override fun show(output: ByteArray) {
        Thread.sleep(showMillis)
        synchronized(received) { received.writeBytes(output) }
    }

    override fun press(key: TerminalKey) {
        pressed += key
    }

    override fun press(character: Char) {
        characters += character
    }

    override fun type(text: String) {
        typed += text
    }

    override fun paste(text: String) {
        pasted += text
    }

    override fun close() {
        closed = true
    }

    @Composable
    override fun Content(
        background: Color,
        foreground: Color,
        keyboardEnabled: Boolean,
        onPaste: () -> Unit,
        focus: FocusRequester,
        modifier: Modifier,
    ) {
        this.onPaste = onPaste
        LaunchedEffect(Unit) { onSize(panelSize) }
        // Focus can only be asked of the real one while it takes a keyboard.
        Box(if (keyboardEnabled) modifier.focusRequester(focus).focusable() else modifier)
    }
}

class FakeTerminalDisplays(val panelSize: ToolboxTerminalSize = ToolboxTerminalSize(30, 50)) :
    TerminalDisplayFactory {
    val created = mutableListOf<FakeTerminalDisplay>()

    override fun create(onInput: (ByteArray) -> Unit, onSize: (ToolboxTerminalSize) -> Unit) =
        FakeTerminalDisplay(onInput, onSize, panelSize).also { created += it }
}

object NoSystemActions : SystemActions {
    override fun openNotificationAccess() = Unit

    override fun openAppNotificationSettings() = Unit

    override fun requestCallingAccess(action: CallAuthorizationAction) = Unit

    override fun requestSosAccess(access: SosAccess) = Unit

    override fun sosSmsSubscriptions() = emptyList<Int>()
}

fun DemoWatch.session(
    key: String = "demo",
    client: WatchClient = this.client,
    toolbox: WatchToolboxClient = WatchToolboxClient.UNAVAILABLE,
    dnd: DndSyncClient = DndSyncClient.UNAVAILABLE,
    healthRecords: HealthRecordsClient = this.healthRecords,
    monitoring: MonitoringSettingsClient = this.monitoring,
    sos: SosClient = this.sos,
    localHealth: LocalHealthSyncClient = this.localHealth,
    healthModule: HealthModuleClient = this.healthModule,
    notificationAlertPatch: NotificationAlertPatchClient = this.notificationAlertPatch,
    watchModuleHost: WatchModuleHostClient = this.watchModuleHost,
) =
    WatchSession(
        key = key,
        isDemo = key == "demo",
        client = client,
        services =
            WatchUiServices(
                privacy = privacy,
                pairing = pairing,
                setup = setup,
                notifications = notifications,
                media = media,
                finding = finding,
                deviceSettings = deviceSettings,
                watchFaces = watchFaces,
                recovery = recovery,
                history = history,
                healthRecords = healthRecords,
                localHealth = localHealth,
                data = data,
                calling = calling,
                network = network,
                account = account,
                cloudHealth = cloudHealth,
                google = google,
                toolbox = toolbox,
                dnd = dnd,
                healthModule = healthModule,
                notificationAlertPatch = notificationAlertPatch,
                watchModuleHost = watchModuleHost,
                monitoring = monitoring,
                sos = sos,
            ),
        localProfileId = "profile",
    )

fun demoWatch(context: Context) =
    DemoWatch(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))

/** A watch client that has never been paired. */
class UnpairedClient : WatchClient {
    override val state = MutableStateFlow(WatchSnapshot()).asStateFlow()

    override suspend fun connect(peer: PeerAddress) = Unit

    override suspend fun disconnect() = Unit

    override suspend fun refreshBattery() = Unit

    override suspend fun refreshHealth() = Unit

    override suspend fun setStepGoal(goal: Int) = Unit

    override fun close() = Unit
}
