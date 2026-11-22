package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Every public operation goes through the production agent protocol, over either real transport. */
open class AgentToolboxBackend(
    private val kind: ToolboxBackendKind,
    scope: CoroutineScope,
    protected val connector: ToolboxAgentConnector,
    private val connectTimeoutMillis: Long = 90_000,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val terminalStartTimeoutMillis: Long = 30_000,
    private val installReplyGraceMillis: Long = 2_000,
) : WatchToolboxBackend {
    init { require(connectTimeoutMillis > 0 && terminalStartTimeoutMillis > 0 && installReplyGraceMillis > 0) }
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val generation = AtomicLong()
    private val links = ConcurrentHashMap.newKeySet<AgentLink>()
    private val terminals = ConcurrentHashMap.newKeySet<AgentTerminalSession>()
    private val connecting = Mutex()
    private val lifecycle = Any()
    private var acceptedDeviceId: String? = null
    private val mutableState = MutableStateFlow(ToolboxBackendState(kind))
    override val state: StateFlow<ToolboxBackendState> = mutableState.asStateFlow()

    override suspend fun connect() = connectConfigured { }
    /** A service may reuse a connection without invalidating ongoing Advanced operations. */
    suspend fun ensureConnected() = connectConfigured(reuseConnected = true) { }
    suspend fun connectConfigured(reuseConnected: Boolean = false, configure: suspend () -> Unit) = connecting.withLock {
        if (reuseConnected && state.value.phase == ToolboxPhase.CONNECTED) {
            if (state.value.previousDeviceId != null) throw ToolboxException("device_changed")
            return@withLock
        }
        val epoch = invalidate(ToolboxPhase.CONNECTING, requireOpen = true)
        try {
            // Include READY and the probe reply: a live socket alone does not prove the agent
            // received its first request, and a blocked probe must not stay CONNECTING forever.
            withTimeout(connectTimeoutMillis) {
                configure(); connector.prepare()
                if (generation.get() != epoch) throw ToolboxException("connection_replaced")
                val link = acquire(epoch, connecting = true)
                link.use {
                    link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request("probe")); link.result()
                    if (!connectedState(epoch, link)) throw ToolboxException("connection_replaced")
                }
            }
        } catch (cancelled: CancellationException) {
            connector.close()
            if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) {
                setState(epoch, ToolboxBackendState(kind, ToolboxPhase.FAILED, connector.device, error = "timeout"))
                throw ToolboxException("timeout", cancelled)
            }
            setState(epoch, ToolboxBackendState(kind)); throw cancelled
        }
        catch (error: Exception) {
            connector.close()
            setState(epoch, ToolboxBackendState(kind, ToolboxPhase.FAILED, connector.device, error = (error as? ToolboxException)?.code ?: "connection_failed"))
            throw error
        }
    }
    override suspend fun disconnect() { invalidate(ToolboxPhase.DISCONNECTED) }
    override fun confirmDevice(target: ToolboxTarget) = synchronized(lifecycle) {
        val current = state.value
        if (current.phase != ToolboxPhase.CONNECTED || current.target != target) throw ToolboxException("connection_replaced")
        acceptedDeviceId = target.deviceId
        mutableState.value = current.copy(previousDeviceId = null)
    }
    private fun connectedState(epoch: Long, link: AgentLink): Boolean = synchronized(lifecycle) {
        if (generation.get() != epoch) return@synchronized false
        if (acceptedDeviceId == null) acceptedDeviceId = link.deviceId
        mutableState.value = ToolboxBackendState(kind, ToolboxPhase.CONNECTED, connector.device, link.capabilities,
            target = ToolboxTarget(link.deviceId, epoch, kind), previousDeviceId = acceptedDeviceId.takeIf { it != link.deviceId })
        true
    }
    private fun setState(epoch: Long, value: ToolboxBackendState): Boolean = synchronized(lifecycle) {
        if (generation.get() != epoch) false else { mutableState.value = value; true }
    }
    private fun invalidate(phase: ToolboxPhase, requireOpen: Boolean = false): Long {
        val epoch = synchronized(lifecycle) {
            if (state.value.phase == ToolboxPhase.CLOSED && phase != ToolboxPhase.CLOSED) {
                check(!requireOpen) { "toolbox_closed" }; return generation.get()
            }
            nextConnection.incrementAndGet().also { generation.set(it); mutableState.value = ToolboxBackendState(kind, phase, if (phase == ToolboxPhase.CONNECTING) connector.device else null) }
        }
        terminals.forEach(AgentTerminalSession::close)
        // Readers remove and close completed links concurrently. Traverse the concurrent set
        // directly: toList() has a size==1 shortcut that can race with removing its last entry.
        links.forEach(AgentLink::close); connector.close()
        return epoch
    }
    override fun close() { invalidate(ToolboxPhase.CLOSED); lifetime.cancel() }

    private suspend fun acquire(epoch: Long = generation.get(), connecting: Boolean = false, asyncClose: Boolean = false): AgentLink {
        var attempt = 1
        while (true) {
            try { return acquireOnce(epoch, connecting, asyncClose) }
            catch (error: IOException) {
                diagnostic("agent_open_failed", epoch, attempt, error)
                if (error is ToolboxException || attempt != 1 || generation.get() != epoch ||
                    !connector.recoverBeforeRequest(error)) throw error
                currentCoroutineContext().ensureActive()
                diagnostic("agent_open_retry", epoch, attempt, error)
                attempt++
            }
        }
    }
    private suspend fun acquireOnce(epoch: Long, connecting: Boolean, asyncClose: Boolean): AgentLink {
        val lease = currentCoroutineContext()[ToolboxOperationTarget]?.takeIf { it.backend === this }?.target
        val expected = if (connecting) null else lease ?: state.value.target ?: throw ToolboxException("device_identity_required")
        if (!connecting && state.value.previousDeviceId != null) throw ToolboxException("device_changed")
        if (expected != null && expected.connectionId != epoch) throw ToolboxException("connection_replaced")
        if (!connecting && state.value.phase != ToolboxPhase.CONNECTED) throw ToolboxException("not_connected")
        val channel = withTimeout(60_000) { connector.open() }
        val link = AgentLink(channel, asyncClose) { links.remove(it) }; links += link
        try {
            if (generation.get() != epoch) throw ToolboxException("connection_replaced")
            withTimeout(60_000) { link.hello() }
            if (expected != null && link.deviceId != expected.deviceId) {
                setState(epoch, ToolboxBackendState(kind, ToolboxPhase.FAILED, connector.device, error = "device_changed"))
                throw ToolboxException("device_changed")
            }
            if (kind == ToolboxBackendKind.ROOT_BRIDGE && !link.capabilities.root) throw ToolboxException("root_required")
            if (generation.get() != epoch) throw ToolboxException("connection_replaced")
            return link
        } catch (error: Throwable) { link.close(); throw error }
    }
    private suspend fun open(op: String, args: JsonObject = buildJsonObject { }): AgentLink {
        val link = acquire()
        try { link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request(op, args)); return link }
        catch (error: Throwable) {
            // A partial write may have executed remotely. Only acquire/READY may be retried.
            diagnostic("request_write_failed", generation.get(), 1, error)
            link.close(); throw error
        }
    }
    private fun diagnostic(event: String, epoch: Long, attempt: Int, error: Throwable) {
        runCatching { diagnostics.record(ProtocolDiagnostic("toolbox", event,
            severity = DiagnosticSeverity.WARNING, sessionId = epoch, details = buildMap {
                put("backend", kind.name); put("attempt", attempt.toString())
                put("exception", error.javaClass.simpleName)
                put("causes", generateSequence(error.cause) { it.cause }.take(8).joinToString(",") { it.javaClass.simpleName })
                if (error is ToolboxException) put("code", error.code)
            })) }
    }
    private suspend fun unary(op: String, args: JsonObject = buildJsonObject { }) = withTimeout(120_000) { open(op, args).use { it.result() } }

    /** Used by the dedicated monitoring service, retaining this backend's device/generation checks. */
    suspend fun monitoringSettings(args: JsonObject): JsonObject = withTimeout(120_000) {
        acquire().use { link ->
            if (!link.capabilities.root) throw ToolboxException("monitoring_root_required")
            if (ToolboxCapability.HEALTH_MONITORING !in link.capabilities.features) throw ToolboxException("monitoring_bridge_update_required")
            link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request(HealthMonitoringProtocol.OPERATION, args))
            link.result()
        }
    }

    override suspend fun install(apks: List<ToolboxApk>, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit): ToolboxInstallResult {
        require(apks.isNotEmpty() && apks.size <= 1024 && options.userId >= 0)
        return withTimeout(1_800_000) { open("install", installArgs(apks, options)).use { link -> installOn(link, apks, progress) } }
    }
    /** One bound channel from READY through upload and Magisk's reply. Never retries a request. */
    suspend fun healthModule(catalog: MagiskModuleProtocol.Catalog, source: (() -> InputStream)?,
        progress: (ToolboxProgress) -> Unit, submitted: () -> Unit): JsonObject = withTimeout(240_000) {
        acquire().use { link ->
            if (kind != ToolboxBackendKind.ROOT_BRIDGE || !link.capabilities.root) throw ToolboxException("module_root_required")
            if (ToolboxCapability.HEALTH_MODULE !in link.capabilities.features) throw ToolboxException("module_bridge_update_required")
            link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request(MagiskModuleProtocol.OPERATION, buildJsonObject {
                put("action", if (source == null) "status" else "install"); put("catalog", catalog.json())
            }))
            if (source == null) return@withTimeout link.result()
            // Preflight errors are read before opening the asset or starting an upload. This
            // preserves precise refusals even if the peer closes immediately after its ERROR.
            val ready = link.read()
            if (ready.type != ToolboxProtocol.PROGRESS || ready.objectValue().flag("readyForUpload") != true) link.unexpected(ready)
            supervisorScope {
                val writing = async {
                    try { toolboxIo(onCancel = link::close) { source() }.use { upload(link, it, catalog.bytes, submitted) } }
                    catch (error: Throwable) { link.close(); throw error }
                }
                try {
                    while (true) {
                        val packet = link.read()
                        when (packet.type) {
                            ToolboxProtocol.PROGRESS -> progress(ToolboxProgress(packet.objectValue().moduleNumber("completed"), catalog.bytes))
                            ToolboxProtocol.RESULT -> { writing.cancelAndJoin(); return@supervisorScope packet.objectValue() }
                            else -> link.unexpected(packet)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                } finally { writing.cancelAndJoin() }
            }
        }
    }
    private fun installArgs(apks: List<ToolboxApk>, options: ToolboxInstallOptions) = buildJsonObject {
        putJsonArray("parts") { apks.forEach { add(buildJsonObject { put("name", it.name); put("size", it.size) }) } }
        put("userId", options.userId); put("replace", options.replace); put("allowDowngrade", options.allowDowngrade); put("grantRuntimePermissions", options.grantRuntimePermissions)
    }
    private suspend fun installOn(link: AgentLink, apks: List<ToolboxApk>, progress: (ToolboxProgress) -> Unit, onSent: () -> Unit = {}): ToolboxInstallResult {
        val total = apks.fold(0L) { sum, part -> Math.addExact(sum, part.size) }
        return supervisorScope {
            // A rejected part may race the writer's next DATA packet. Its transport failure must
            // not cancel the reader before it can consume PackageManager's definitive refusal.
            val uploadFailure = AtomicReference<Throwable?>()
            val writing = async {
                try {
                    apks.forEach { apk ->
                        val input = toolboxIo(onCancel = link::close, onDiscard = InputStream::close) { apk.open() }
                        input.use {
                            try { uploadInstallPart(link, it, apk.size) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Throwable) {
                                // Record before close(): source cleanup can release a pending
                                // remote reply on another thread.
                                uploadFailure.compareAndSet(null, error)
                                throw error
                            }
                        }
                    }
                    onSent()
                    Result.success(Unit)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { uploadFailure.compareAndSet(null, error); Result.failure(error) }
            }
            val reading = async {
                try {
                    var result: ToolboxInstallResult? = null
                    while (result == null) {
                        val packet = link.read()
                        when (packet.type) {
                            ToolboxProtocol.PROGRESS -> packet.objectValue().let { progress(ToolboxProgress(it.number("completed") ?: 0, total, it.number("item")?.toInt() ?: 0)) }
                            ToolboxProtocol.RESULT -> {
                                val value = packet.objectValue()
                                val success = value.flag("success") ?: throw ToolboxException("invalid_install_result")
                                result = ToolboxInstallResult(success, value.text("output") ?: "")
                            }
                            else -> link.unexpected(packet)
                        }
                    }
                    Result.success(checkNotNull(result))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { Result.failure(error) }
            }
            fun sourceFailure(): Throwable? = uploadFailure.get()?.takeUnless { it is InstallTransportWriteFailure }
            try {
                val replyFirst = select<Boolean> {
                    reading.onAwait { true }
                    writing.onAwait { false }
                }
                val response = if (replyFirst) reading.await() else {
                    val sent = writing.await()
                    val error = sent.exceptionOrNull()
                    when (error) {
                        null -> reading.await()
                        is InstallTransportWriteFailure -> withTimeoutOrNull(installReplyGraceMillis) { reading.await() }
                            ?: throw error.failure
                        else -> throw error
                    }
                }
                // A local open/read/length/close error retains its own reason, even if stopping
                // the upload caused the peer to report an error as well.
                sourceFailure()?.let { throw it }
                val result = response.getOrThrow()
                if (result.success) {
                    val error = writing.await().exceptionOrNull()
                    if (error is InstallTransportWriteFailure) throw error.failure
                    if (error != null) throw error
                } else {
                    writing.cancelAndJoin()
                    sourceFailure()?.let { throw it }
                }
                result
            } finally {
                writing.cancel(); reading.cancel()
                writing.join(); reading.join()
            }
        }
    }
    /** Only network sends get this marker; source failures keep their original API reason. */
    private class InstallTransportWriteFailure(val failure: IOException) : IOException(failure)
    private suspend fun uploadInstallPart(link: AgentLink, input: InputStream, size: Long) {
        suspend fun send(type: Int, bytes: ByteArray = byteArrayOf()) {
            try { link.send(type, bytes) }
            catch (error: IOException) { throw InstallTransportWriteFailure(error) }
        }
        var remaining = size; val buffer = ByteArray(ToolboxProtocol.CHUNK)
        while (remaining > 0) {
            val n = toolboxIo(onCancel = { link.close(); input.close() }) { input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
            if (n < 0) throw ToolboxException("source_short_read")
            if (n == 0) throw ToolboxException("source_empty_read")
            send(ToolboxProtocol.DATA, buffer.copyOf(n)); remaining -= n
        }
        if (toolboxIo(onCancel = { link.close(); input.close() }) { input.read() } != -1) throw ToolboxException("source_size_mismatch")
        send(ToolboxProtocol.END)
    }
    /** Holds no lease and skips the version check: the point is to replace an agent tools would refuse. */
    override suspend fun updateHost(apk: ToolboxApk, progress: (ToolboxProgress) -> Unit): ToolboxInstallResult {
        if (kind != ToolboxBackendKind.ROOT_BRIDGE) throw ToolboxException("toolbox_unavailable")
        connector.prepare()
        val channel = withTimeout(60_000) { connector.open() }
        val link = AgentLink(channel) { links.remove(it) }; links += link
        val sent = AtomicBoolean()
        return try {
            link.use {
                checkUpdatable(withTimeout(60_000) { link.ready() })
                withTimeout(1_800_000) {
                    val options = ToolboxInstallOptions(replace = true, grantRuntimePermissions = true)
                    link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request("install", installArgs(listOf(apk), options)))
                    installOn(link, listOf(apk), progress) { sent.set(true) }
                }
            }
        } catch (lost: IOException) {
            if (lost is ToolboxException) throw lost
            // Everything sent means the commit may have begun, and it stops the bridge carrying this reply.
            throw ToolboxException(if (sent.get()) "install_result_unknown" else "bridge_update_interrupted", lost)
        }
    }
    private fun checkUpdatable(ready: JsonObject) {
        when (val problem = agentVersionProblem(ready.number("version"))) {
            null, "agent_version_mismatch" -> Unit
            else -> throw ToolboxException(problem)
        }
        if (ready.number("uid") != 0L) throw ToolboxException("root_required")
        val device = ready.text("deviceId") ?: throw ToolboxException("device_identity_required")
        val accepted = synchronized(lifecycle) { acceptedDeviceId }
        if (accepted != null && accepted != device) throw ToolboxException("device_changed")
    }
    override suspend fun readBundle(file: ToolboxApk): ToolboxBundle? = readPackageFile(file) { readWatchProfile() }
    override suspend fun installBundle(bundle: ToolboxBundle, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit) = installPackage(bundle, options, progress)
    private suspend fun upload(link: AgentLink, input: InputStream, size: Long, beforeEnd: () -> Unit = {}) {
        var remaining = size; val buffer = ByteArray(ToolboxProtocol.CHUNK)
        while (remaining > 0) {
            val n = toolboxIo(onCancel = { link.close(); input.close() }) { input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
            if (n < 0) throw ToolboxException("source_short_read")
            if (n == 0) throw ToolboxException("source_empty_read")
            link.send(ToolboxProtocol.DATA, buffer.copyOf(n)); remaining -= n
        }
        if (toolboxIo(onCancel = { link.close(); input.close() }) { input.read() } != -1) throw ToolboxException("source_size_mismatch")
        beforeEnd()
        link.send(ToolboxProtocol.END)
    }
    override suspend fun apps(userId: Int): List<ToolboxApp> {
        require(userId >= 0)
        return entries("apps", buildJsonObject { put("userId", userId) }).map { value -> ToolboxApp(
            value.required("packageName"), value.required("label"), value.text("versionName"), value.number("versionCode") ?: 0,
            value.number("uid")!!.toInt(), value.flag("enabled") == true, value.flag("system") == true,
            (value["apkPaths"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }, value.text("dataDirectory")) }
    }
    override suspend fun app(request: ToolboxAppRequest): ToolboxCommandResult {
        if (request.action !in setOf(ToolboxAppAction.START, ToolboxAppAction.ENABLE, ToolboxAppAction.UNSUSPEND)) protectHost(request.packageName)
        require(request.userId >= 0)
        val args = buildJsonObject { put("packageName", request.packageName); put("action", request.action.name); put("userId", request.userId); put("keepData", request.keepData) }
        return capture(command("app", args), ToolboxCommandOptions(timeoutMillis = 120_000))
    }
    override suspend fun permission(packageName: String, permission: String, grant: Boolean, userId: Int): ToolboxCommandResult {
        protectHost(packageName)
        require(userId >= 0)
        return execute(listOf("cmd", "package", if (grant) "grant" else "revoke", "--user", userId.toString(), packageName, permission).joinToString(" ", transform = ToolboxProtocol::quote))
    }
    override suspend fun appOp(packageName: String, operation: String, mode: String, userId: Int): ToolboxCommandResult {
        protectHost(packageName)
        require(userId >= 0)
        return execute(listOf("cmd", "appops", "set", "--user", userId.toString(), packageName, operation, mode).joinToString(" ", transform = ToolboxProtocol::quote))
    }
    override suspend fun backupAppData(packageName: String, output: OutputStream, userId: Int, progress: (ToolboxProgress) -> Unit) {
        protectHost(packageName)
        require(userId >= 0)
        streamCommand(command("backup", buildJsonObject { put("packageName", packageName); put("userId", userId) }), output, progress)
    }
    override suspend fun execute(command: String, options: ToolboxCommandOptions): ToolboxCommandResult = capture(openCommand(command, options), options)
    private fun protectHost(packageName: String) {
        if (packageName in state.value.capabilities?.protectedPackages.orEmpty()) throw ToolboxException("transport_host_protected")
    }
    private suspend fun capture(session: ToolboxCommandSession, options: ToolboxCommandOptions): ToolboxCommandResult = session.use {
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream(); var truncated = false
        var exit: ToolboxCommandEvent.Exited? = null
        it.closeInput()
        it.events.collect { event -> when (event) {
            is ToolboxCommandEvent.Stdout, is ToolboxCommandEvent.Stderr -> {
                val bytes = if (event is ToolboxCommandEvent.Stdout) event.bytes else (event as ToolboxCommandEvent.Stderr).bytes
                val sink = if (event is ToolboxCommandEvent.Stdout) stdout else stderr
                val remaining = options.maxCaptureBytes - stdout.size() - stderr.size()
                if (bytes.size > remaining) truncated = true
                sink.write(bytes, 0, minOf(bytes.size, remaining.coerceAtLeast(0)))
            }
            is ToolboxCommandEvent.Exited -> exit = event
        } }
        val status = exit ?: throw ToolboxException("missing_command_exit")
        ToolboxCommandResult(status.code, stdout.toByteArray(), stderr.toByteArray(), truncated, status.timedOut)
    }
    override suspend fun openCommand(command: String, options: ToolboxCommandOptions): ToolboxCommandSession = command("exec", buildJsonObject { put("command", command); put("timeoutMillis", options.timeoutMillis) })
    override suspend fun openTerminal(options: ToolboxTerminalOptions): ToolboxTerminalSession {
        val target = currentCoroutineContext()[ToolboxOperationTarget]?.takeIf { it.backend === this }?.target
            ?: state.value.target ?: throw ToolboxException("not_connected")
        var opened: AgentLink? = null
        var created: AgentTerminalSession? = null
        try {
            return withTimeout(terminalStartTimeoutMillis) {
                val link = acquire(target.connectionId, asyncClose = true).also { opened = it }
                if (ToolboxCapability.TERMINAL !in link.capabilities.features) throw ToolboxException("terminal_unavailable")
                link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request("terminal", buildJsonObject {
                    options.size.wireSize().forEach { (key, value) -> put(key, value) }
                    put("term", options.term)
                }))
                if (link.result().flag("started") != true) throw ToolboxException("terminal_start_failed")
                fun validate() {
                    val current = state.value
                    if (current.previousDeviceId != null) throw ToolboxException("device_changed")
                    if (current.phase != ToolboxPhase.CONNECTED || current.target != target) throw ToolboxException("connection_replaced")
                }
                validate()
                val session = AgentTerminalSession(link, worker, target, ::validate) { terminals.remove(it) }
                created = session
                terminals += session
                if (session.isClosed) terminals.remove(session)
                validate()
                session
            }
        } catch (timeout: TimeoutCancellationException) {
            created?.close(); opened?.close()
            if (!currentCoroutineContext().isActive) throw timeout
            throw ToolboxException("terminal_start_timeout", timeout)
        } catch (error: Throwable) { created?.close(); opened?.close(); throw error }
    }
    private suspend fun command(op: String, args: JsonObject): ToolboxCommandSession = AgentCommandSession(open(op, args), worker)
    private suspend fun streamCommand(session: ToolboxCommandSession, output: OutputStream, progress: (ToolboxProgress) -> Unit = {}) = session.use {
        var count = 0L; var exit: ToolboxCommandEvent.Exited? = null
        it.closeInput()
        it.events.collect { event -> when (event) {
            is ToolboxCommandEvent.Stdout -> { toolboxIo(onCancel = it::close) { output.write(event.bytes) }; count += event.bytes.size; progress(ToolboxProgress(count, null)) }
            is ToolboxCommandEvent.Stderr -> Unit
            is ToolboxCommandEvent.Exited -> exit = event
        } }
        val status = exit ?: throw ToolboxException("missing_command_exit")
        if (status.code != 0 || status.timedOut) throw ToolboxException("remote_command_failed_${status.code}")
    }
    override suspend fun screenshot(output: OutputStream) { streamCommand(openCommand("screencap -p", ToolboxCommandOptions()), output) }
    override suspend fun clipboard(): String = unary("clipboard_get").text("text") ?: ""
    override suspend fun setClipboard(text: String) { unary("clipboard_set", buildJsonObject { put("text", text) }) }
    override suspend fun openScreen(options: ToolboxScreenOptions): ToolboxScreenSession {
        val epoch = generation.get()
        val host = if (options.udp) {
            if (kind != ToolboxBackendKind.ADB || !options.video) throw ToolboxException("screen_udp_unavailable")
            connector.screenUdpHost ?: throw ToolboxException("screen_udp_unavailable")
        } else null
        val link = acquire(epoch, asyncClose = options.udp)
        try {
            if (options.udp && !link.secureScreenUdp) throw ToolboxException("screen_udp_tls_required")
            if (options.udp && !link.capabilities.features.containsAll(setOf(
                ToolboxCapability.SCREEN_UDP, ToolboxCapability.SCREEN_UDP_FEC, ToolboxCapability.SCREEN_KEYFRAME, ToolboxCapability.SCREEN_UDP_STARTUP,
            ))) throw ToolboxException("screen_udp_unavailable")
            if (options.udp && !options.udpEncryption && ToolboxCapability.SCREEN_UDP_AUTH_ONLY !in link.capabilities.features)
                throw ToolboxException("screen_udp_encryption_mode_unavailable")
            link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request("screen", buildJsonObject {
                put("video", options.video); put("maxSize", options.maxSize); put("maxFps", options.maxFps); put("bitRate", options.bitRate)
                put("videoCodec", options.codec.name.lowercase()); put("audio", options.audio)
                put("holdWifi", kind == ToolboxBackendKind.ADB); put("udp", options.udp)
                if (options.limitToEncoder && ToolboxCapability.SCREEN_ENCODER_LIMITS in link.capabilities.features) put("limitToEncoder", true)
                if (options.videoCodecOptions.isNotEmpty() && ToolboxCapability.SCREEN_CODEC_OPTIONS in link.capabilities.features)
                    put("videoCodecOptions", options.videoCodecOptions)
                if (options.udp) {
                    put("udpDiagnostics", options.collectDiagnostics)
                    put("udpEncryption", options.udpEncryption)
                    put("udpFec", ScreenUdpTransport.VideoProtection.RS_16_4.wireName)
                    put("udpLightKeyframe", true)
                    put("udpStartupGate", true)
                }
            }))
            val trace = if (options.video && options.collectDiagnostics) ScreenDiagnostics(diagnostics, kind, options) else null
            return if (host != null) UdpAgentScreenSession(link, worker, host, options.audio, trace = trace, maximumFps = options.maxFps, requiresVideoDecoderReady = true, startupGate = true, encryption = options.udpEncryption, collectDiagnostics = options.collectDiagnostics)
            else AgentScreenSession(link, worker, video = options.video, trace = trace)
        } catch (error: Throwable) { link.close(); throw error }
    }
    override suspend fun screenEncoders(maxSize: Int): List<ToolboxScreenEncoder> {
        require(maxSize in 64..4096)
        if (ToolboxCapability.SCREEN_ENCODER_LIMITS !in state.value.capabilities?.features.orEmpty()) throw ToolboxException("screen_encoders_unavailable")
        val codecs = unary("screen_encoders", buildJsonObject { put("maxSize", maxSize) })["codecs"] as? JsonArray ?: throw ToolboxException("screen_encoders_unavailable")
        return codecs.mapNotNull { value -> (value as? JsonObject)?.let(::screenEncoderValue) }
    }
    private fun screenEncoderValue(v: JsonObject): ToolboxScreenEncoder? {
        val codec = when (v.text("codec")) { "h264" -> ToolboxVideoCodec.H264; "h265" -> ToolboxVideoCodec.H265; else -> return null }
        val supported = v.flag("supported") ?: return null
        fun positive(name: String) = v.number(name)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        return if (!supported) ToolboxScreenEncoder(codec, false) else ToolboxScreenEncoder(codec, true, v.text("encoder"), v.flag("hardware"),
            positive("width"), positive("height"), positive("maxFps")?.coerceAtMost(120), positive("maxBitRate"))
    }
    override suspend fun files(path: String): List<ToolboxFile> = entries("files", buildJsonObject { put("path", path) }).map(::fileValue)
    override suspend fun stat(path: String): ToolboxFile = fileValue(unary("stat", buildJsonObject { put("path", path) }))
    override suspend fun file(request: ToolboxFileRequest) { try { unary("file", buildJsonObject {
        put("action", request.action.name); put("path", request.path); request.destination?.let { put("destination", it) }
        put("recursive", request.recursive); put("overwrite", request.overwrite)
        request.mode?.let { put("mode", it) }; request.uid?.let { put("uid", it) }; request.gid?.let { put("gid", it) }
    }) } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) throw ToolboxException("file_result_unknown", cancelled)
        throw cancelled
    } catch (error: IOException) {
        if (error is ToolboxException) throw error
        throw ToolboxException("file_result_unknown", error)
    } }
    override suspend fun pull(path: String, output: OutputStream, offset: Long, progress: (ToolboxProgress) -> Unit) {
        require(offset >= 0)
        val link = open("pull", buildJsonObject { put("path", path); put("offset", offset) })
        var complete = false
        try {
            var copied = 0L; var total: Long? = null
            while (true) {
                val packet = link.read()
                when (packet.type) {
                    ToolboxProtocol.PROGRESS -> total = packet.objectValue().number("total")
                    ToolboxProtocol.DATA -> { toolboxIo(onCancel = link::close) { output.write(packet.bytes) }; copied += packet.bytes.size; progress(ToolboxProgress(copied, total)) }
                    ToolboxProtocol.END -> { if (total != null && copied != total) throw ToolboxException("file_size_mismatch"); complete = true; if (ToolboxCapability.FILE_RESUME in link.capabilities.features) acknowledgeTransfer(link); return }
                    else -> link.unexpected(packet)
                }
            }
        } finally {
            // END and the checked length are authoritative. A transport already closed by the
            // completed agent must not turn a successful download into an operation failure.
            if (complete) link.closeAsync() else runCatching(link::close)
        }
    }
    override suspend fun push(path: String, input: InputStream, size: Long, options: ToolboxWriteOptions, progress: (ToolboxProgress) -> Unit) {
        require(size >= 0); options.mode?.let { require(it in 0..0xfff) }
        val args = buildJsonObject { put("path", path); put("size", size); put("overwrite", options.overwrite); put("atomic", options.atomic); options.mode?.let { put("mode", it) } }
        input.use { source -> open("push", args).use { link -> coroutineScope {
            val writing = launch { upload(link, source, size) }
            try {
                while (true) { val packet = link.read(); when (packet.type) {
                    ToolboxProtocol.PROGRESS -> progress(ToolboxProgress(packet.objectValue().number("completed") ?: 0, size))
                    ToolboxProtocol.RESULT -> { writing.join(); return@coroutineScope }
                    else -> link.unexpected(packet)
                } }
            } finally { writing.cancelAndJoin() }
        } } }
    }
    private suspend fun openTransfer(op: String, args: JsonObject): AgentLink {
        val link = acquire(asyncClose = true)
        try {
            if (ToolboxCapability.FILE_RESUME !in link.capabilities.features) throw ToolboxException("file_resume_unavailable")
            link.send(ToolboxProtocol.REQUEST, ToolboxProtocol.request(op, args))
            return link
        } catch (error: Throwable) { link.close(); throw error }
    }
    private fun transferTarget(expected: ToolboxTarget? = null): ToolboxTarget {
        val current = state.value
        val target = current.target ?: throw ToolboxException("device_identity_required")
        if (current.phase != ToolboxPhase.CONNECTED) throw ToolboxException("not_connected")
        if (current.previousDeviceId != null || expected != null &&
            (expected.kind != target.kind || expected.deviceId != target.deviceId)) throw ToolboxException("device_changed")
        return target
    }
    override suspend fun snapshotFile(path: String): ToolboxFileSnapshot {
        val target = transferTarget()
        return openTransfer("file_snapshot", buildJsonObject { put("path", path) }).use { link ->
            val value = link.result()
            val size = value.number("size")?.takeIf { it >= 0 } ?: throw ToolboxException("file_transfer_protocol")
            val snapshot = ToolboxFileSnapshot(target, path, size, checkedHash(value.required("sha256")), value.number("modifiedMillis") ?: 0)
            acknowledgeTransfer(link); snapshot
        }
    }
    override suspend fun pullResumable(snapshot: ToolboxFileSnapshot, output: OutputStream, offset: Long,
        prefixSha256: String?, progress: (ToolboxProgress) -> Unit): ToolboxTransferReceipt {
        transferTarget(snapshot.target)
        require(snapshot.size >= 0 && offset in 0..snapshot.size)
        checkedHash(snapshot.sha256)
        if (offset > 0) checkedHash(prefixSha256 ?: throw ToolboxException("file_resume_mismatch"))
        val args = buildJsonObject {
            put("path", snapshot.path); put("size", snapshot.size); put("sha256", snapshot.sha256); put("offset", offset)
            if (offset > 0) put("prefixSha256", prefixSha256)
        }
        return openTransfer("pull_resume", args).use { link ->
            val initial = link.read()
            if (initial.type != ToolboxProtocol.PROGRESS) link.unexpected(initial)
            val ready = initial.objectValue()
            if (ready.number("completed") != offset || ready.number("total") != snapshot.size) throw ToolboxException("file_transfer_protocol")
            progress(ToolboxProgress(offset, snapshot.size))
            var copied = offset
            val digest = MessageDigest.getInstance("SHA-256")
            while (true) {
                val packet = link.read()
                when (packet.type) {
                    ToolboxProtocol.DATA -> {
                        if (packet.bytes.isEmpty() || packet.bytes.size > snapshot.size - copied) throw ToolboxException("file_size_mismatch")
                        toolboxIo(onCancel = link::close) { output.write(packet.bytes) }
                        digest.update(packet.bytes); copied += packet.bytes.size
                        progress(ToolboxProgress(copied, snapshot.size))
                    }
                    ToolboxProtocol.RESULT -> {
                        val result = packet.objectValue()
                        if (copied != snapshot.size || result.number("bytes") != snapshot.size || result.text("sha256") != snapshot.sha256 ||
                            result.text("suffixSha256") != digest.digest().hex()) throw ToolboxException("file_checksum_mismatch")
                        acknowledgeTransfer(link)
                        return@use ToolboxTransferReceipt(copied, snapshot.sha256, offset)
                    }
                    else -> link.unexpected(packet)
                }
            }
            @Suppress("UNREACHABLE_CODE") throw ToolboxException("file_transfer_protocol")
        }
    }
    private fun uploadRequest(request: ToolboxUploadRequest): JsonObject {
        transferTarget(request.target)
        require(request.transferId.matches(Regex("[a-f0-9]{32}")) && request.size >= 0)
        require(request.options.atomic) { "Resumable uploads require atomic replacement" }
        checkedHash(request.sha256); request.options.mode?.let { require(it in 0..0xfff) }
        return buildJsonObject {
            put("transferId", request.transferId); put("path", request.path); put("size", request.size); put("sha256", request.sha256)
            put("overwrite", request.options.overwrite); request.options.mode?.let { put("mode", it) }
        }
    }
    override suspend fun pushResumable(request: ToolboxUploadRequest, open: (Long) -> InputStream,
        progress: (ToolboxProgress) -> Unit): ToolboxTransferReceipt {
        val args = uploadRequest(request)
        return openTransfer("push_resume", args).use { link ->
            val first = link.read()
            if (first.type == ToolboxProtocol.RESULT) {
                val receipt = transferReceipt(first.objectValue(), request, request.size)
                acknowledgeTransfer(link)
                progress(ToolboxProgress(request.size, request.size)); return@use receipt
            }
            if (first.type != ToolboxProtocol.PROGRESS) link.unexpected(first)
            val ready = first.objectValue()
            val offset = ready.number("completed")?.takeIf { it in 0..request.size } ?: throw ToolboxException("file_transfer_protocol")
            if (ready.number("total") != request.size) throw ToolboxException("file_transfer_protocol")
            val retainedHash = checkedHash(ready.required("prefixSha256"))
            val sourcePrefixHash = if (offset == 0L) MessageDigest.getInstance("SHA-256").digest().hex() else {
                val source = toolboxIo(onCancel = link::close) { open(0) }
                source.use { input -> toolboxIo(onCancel = { link.close(); input.close() }) {
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(ToolboxProtocol.CHUNK); var left = offset
                    while (left > 0) {
                        val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (count <= 0) throw ToolboxException("source_short_read")
                        digest.update(buffer, 0, count); left -= count
                    }
                    digest.digest().hex()
                } }
            }
            if (retainedHash != sourcePrefixHash) throw ToolboxException("file_resume_mismatch")
            progress(ToolboxProgress(offset, request.size))
            val source = toolboxIo(onCancel = link::close) { open(offset) }
            source.use { input -> coroutineScope {
                val submitted = AtomicBoolean()
                val writing = launch { upload(link, input, request.size - offset) { submitted.set(true) } }
                try {
                    var confirmed = offset
                    while (true) {
                        val packet = link.read()
                        when (packet.type) {
                            ToolboxProtocol.PROGRESS -> {
                                val value = packet.objectValue()
                                val count = value.number("completed") ?: throw ToolboxException("file_transfer_protocol")
                                if (count !in confirmed..request.size || value.number("total") != request.size) throw ToolboxException("file_transfer_protocol")
                                confirmed = count; progress(ToolboxProgress(count, request.size))
                            }
                            ToolboxProtocol.RESULT -> {
                                writing.join()
                                val receipt = transferReceipt(packet.objectValue(), request, offset)
                                acknowledgeTransfer(link)
                                progress(ToolboxProgress(request.size, request.size)); return@coroutineScope receipt
                            }
                            else -> link.unexpected(packet)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") throw ToolboxException("file_transfer_protocol")
                } catch (error: IOException) {
                    if (submitted.get() && error !is ToolboxException) throw ToolboxException("file_commit_unconfirmed", error)
                    throw error
                } finally { writing.cancelAndJoin() }
            } }
        }
    }
    private fun transferReceipt(value: JsonObject, request: ToolboxUploadRequest, offset: Long): ToolboxTransferReceipt {
        if (value.number("bytes") != request.size || value.text("sha256") != request.sha256) throw ToolboxException("file_checksum_mismatch")
        return ToolboxTransferReceipt(request.size, request.sha256, offset)
    }
    override suspend fun discardUpload(request: ToolboxUploadRequest) {
        openTransfer("discard_upload", uploadRequest(request)).use { link ->
            if (link.result().flag("discarded") != true) throw ToolboxException("file_transfer_protocol")
            acknowledgeTransfer(link)
        }
    }
    private suspend fun acknowledgeTransfer(link: AgentLink) {
        try { withTimeout(5_000) { link.send(ToolboxProtocol.END) } }
        catch (timeout: TimeoutCancellationException) { if (!currentCoroutineContext().isActive) throw timeout }
        catch (_: IOException) { /* The verified completion reply remains authoritative. */ }
    }
    private fun checkedHash(value: String): String = value.also {
        if (!it.matches(Regex("[a-f0-9]{64}"))) throw ToolboxException("file_transfer_protocol")
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun entries(op: String, args: JsonObject): List<JsonObject> = open(op, args).use { link ->
        val result = mutableListOf<JsonObject>()
        while (true) { val packet = link.read(); when (packet.type) {
            ToolboxProtocol.RESULT -> result += packet.objectValue()
            ToolboxProtocol.END -> return@use result
            else -> link.unexpected(packet)
        } }
        @Suppress("UNREACHABLE_CODE") result
    }
    private fun fileValue(v: JsonObject) = ToolboxFile(v.required("path"), v.required("name"), ToolboxFileKind.valueOf(v.required("kind")),
        v.number("size")!!, v.number("modifiedMillis")!!, v.number("mode")!!.toInt(), v.number("uid")!!.toInt(), v.number("gid")!!.toInt(), v.text("linkTarget"))
    private companion object { val nextConnection = AtomicLong() }
}

internal fun JsonObject.required(key: String) = text(key) ?: throw ToolboxException("missing_$key")
/** Why tools can't use an agent of this version; only an older one that's still updatable can be fixed from here. */
internal fun agentVersionProblem(
    version: Long?,
    current: Long = ToolboxProtocol.VERSION.toLong(),
    firstUpdatable: Long = ToolboxProtocol.FIRST_UPDATABLE_VERSION.toLong(),
): String? = when {
    version == current -> null
    version == null || version < firstUpdatable -> "bridge_update_needs_adb"
    version > current -> "phone_app_outdated"
    else -> "agent_version_mismatch"
}
internal class AgentLink(private val channel: ToolboxChannel, private val asyncClose: Boolean = false, private val closed: (AgentLink) -> Unit) : AutoCloseable {
    val secureScreenUdp: Boolean get() = channel.secureScreenUdp
    private val outputLock = Any()
    private val retired = AtomicBoolean()
    lateinit var capabilities: ToolboxCapabilities
        private set
    lateinit var deviceId: String
        private set
    suspend fun ready(): JsonObject {
        val packet = read(); if (packet.type != ToolboxProtocol.READY) unexpected(packet)
        return packet.objectValue()
    }
    suspend fun hello() {
        val v = ready(); agentVersionProblem(v.number("version"))?.let { throw ToolboxException(it) }
        val features = (v["features"] as? JsonArray).orEmpty().mapNotNull { runCatching { ToolboxCapability.valueOf((it as JsonPrimitive).content) }.getOrNull() }.toSet()
        deviceId = v.text("deviceId")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: throw ToolboxException("device_identity_required")
        val protected = (v["protectedPackages"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet()
        capabilities = ToolboxCapabilities(v.number("uid")!!.toInt(), v.number("sdk")!!.toInt(), features, protected)
    }
    suspend fun read(onRemoteError: ((JsonObject) -> Unit)? = null): ToolboxProtocol.Packet {
        val packet = toolboxIo(onCancel = ::close) {
            if (retired.get()) throw ToolboxException("connection_closed")
            ToolboxProtocol.required(channel.input)
        }
        if (packet.type == ToolboxProtocol.ERROR) {
            val error = packet.objectValue()
            if (packet.bytes.size <= 2_048) runCatching { onRemoteError?.invoke(error) }
            throw ToolboxException(error.text("code") ?: "remote_error")
        }
        return packet
    }
    suspend fun result(): JsonObject { val packet = read(); if (packet.type != ToolboxProtocol.RESULT) unexpected(packet); return packet.objectValue() }
    suspend fun send(type: Int, value: JsonObject) = send(type, value.toString().toByteArray(Charsets.UTF_8))
    suspend fun send(type: Int, bytes: ByteArray = byteArrayOf()) = toolboxIo(onCancel = ::close) { synchronized(outputLock) {
        if (retired.get()) throw ToolboxException("connection_closed")
        ToolboxProtocol.write(channel.output, type, bytes)
    } }
    fun unexpected(packet: ToolboxProtocol.Packet): Nothing = throw ToolboxException("unexpected_packet_${packet.type}")
    override fun close() {
        if (asyncClose) closeAsync()
        else if (retired.compareAndSet(false, true)) try { channel.close() } finally { closed(this) }
    }
    /** CLSE may wait behind a stalled TLS write; retire locally before asking that fixed stream to close. */
    fun closeAsync() {
        if (retired.compareAndSet(false, true)) {
            try { closed(this) }
            finally { Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { runCatching(channel::close) }) }
        }
    }
}

private class AgentCommandSession(private val link: AgentLink, worker: CoroutineScope) : ToolboxCommandSession {
    private val channel = Channel<ToolboxCommandEvent>(16)
    override val events: Flow<ToolboxCommandEvent> = channel.receiveAsFlow()
    private val exit = CompletableDeferred<ToolboxCommandEvent.Exited>()
    private val reading = worker.launch {
        try {
            while (true) { val packet = link.read(); when (packet.type) {
                ToolboxProtocol.STDOUT -> channel.send(ToolboxCommandEvent.Stdout(packet.bytes))
                ToolboxProtocol.STDERR -> channel.send(ToolboxCommandEvent.Stderr(packet.bytes))
                ToolboxProtocol.EXIT -> {
                    val v = packet.objectValue(); val result = ToolboxCommandEvent.Exited(v.number("code")!!.toInt(), v.flag("timedOut") == true)
                    exit.complete(result); channel.send(result); break
                }
                else -> link.unexpected(packet)
            } }
            channel.close()
        } catch (error: Throwable) { exit.completeExceptionally(error); channel.close(error) }
        finally { link.close() }
    }
    override suspend fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) { val end = minOf(bytes.size, offset + ToolboxProtocol.CHUNK); link.send(ToolboxProtocol.STDIN, bytes.copyOfRange(offset, end)); offset = end }
    }
    override suspend fun closeInput() {
        if (!exit.isCompleted) try { link.send(ToolboxProtocol.END) }
        catch (error: java.io.IOException) { if (!exit.isCompleted) throw error }
    }
    override suspend fun awaitExit() = exit.await()
    override suspend fun cancel() { if (!exit.isCompleted) link.send(ToolboxProtocol.CANCEL) }
    override suspend fun forceStop() { if (!exit.isCompleted) link.send(ToolboxProtocol.FORCE_STOP) }
    override fun close() { link.close(); reading.cancel(); channel.cancel(); exit.cancel() }
}
