package dev.ticbox.companion.engine.watchface

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WatchFaceManagementConfig(
    val timeoutMillis: Long = 30_000,
    val maxInventoryItems: Int = 512,
    val maxPendingOperations: Int = 16,
    val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxExpandedArchiveBytes: Long = 128L * 1024 * 1024,
    val maxArchiveEntries: Int = 512,
    val googleRoute: ApplicationRoute? = null,
) {
    init { require(timeoutMillis > 0 && maxInventoryItems > 0 && maxPendingOperations > 0 && maxArchiveBytes > 0 && maxExpandedArchiveBytes > 0 && maxArchiveEntries > 0) }
}

/** Service-owned, session-bound local watch-face inventory and control. */
class WatchFaceManagementEngine(
    parentScope: CoroutineScope,
    private val data: DataSyncClient,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val config: WatchFaceManagementConfig = WatchFaceManagementConfig(),
    private val uninstallRequest: (suspend (CompanionFeatureSession, String) -> WatchFaceOperationResult)? = null,
) : WatchFaceManagement {
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(WatchFaceManagementState())
    override val state = mutableState.asStateFlow()
    private val ids = AtomicInteger()
    private val sendLock = Mutex()
    private val legacyLock = Mutex()
    private val editLock = Mutex()
    private val refreshLock = Mutex()
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var active: Run? = null
    @Volatile private var closed = false
    private class Run(val session: CompanionFeatureSession, parent: Job) {
        val job = SupervisorJob(parent)
        val pending = ConcurrentHashMap<Int, CompletableDeferred<WatchFaceWireOperation>>()
        val legacy = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
        val screenshots = ConcurrentHashMap<Int, CompletableDeferred<WatchFaceEditMessage>>()
        @Volatile var channel: WearChannel? = null
        @Volatile var start: CompletableDeferred<WatchFaceEditMessage>? = null
        @Volatile var end: CompletableDeferred<WatchFaceEditMessage>? = null
        @Volatile var token: String? = null
        @Volatile var schema: ByteArray? = null
        @Volatile var complicationStates: ByteArray? = null
    }
    init {
        worker.launch { for (ignored in dirty) try { readInventory() } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            catch (problem: Exception) { log("inventory_read_failed", problem) } }
        worker.launch { data.state.map { Triple(it.sessionId, it.receivedItems, it.unavailableAssets) }.distinctUntilChanged().collect { dirty.trySend(Unit) } }
    }

    fun attach(session: CompanionFeatureSession) {
        check(!closed)
        active?.let { detach(it.session.sessionId) }
        val run = Run(session, owner); active = run
        mutableState.value = WatchFaceManagementState(session.sessionId, session.scope)
        dirty.trySend(Unit)
    }
    fun detach(sessionId: Long) {
        val run = active?.takeIf { it.session.sessionId == sessionId } ?: return
        active = null
        run.channel?.close(); run.channel = null
        val failure = CancellationException("Watch face session ended")
        run.pending.values.forEach { it.cancel(failure) }; run.legacy.values.forEach { it.cancel(failure) }
        run.screenshots.values.forEach { it.cancel(failure) }; run.start?.cancel(failure); run.end?.cancel(failure)
        run.job.cancel(failure)
        mutableState.update { it.copy(sessionId = 0, editing = WatchFaceEditingState(WatchFaceEditPhase.ABORTED),
            lastOperation = WatchFaceOperationResult(WatchFaceOutcome.INTERRUPTED, detail = "session_ended")) }
    }

    /** Call only for the matching routed ordinary MessageApi event. Never answers a RequestApi RPC. */
    fun offerMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray, route: ApplicationRoute? = null): Boolean {
        val run = active?.takeIf { it.session.peer == peer && it.session.sessionId == sessionId } ?: return false
        val expectedRoute = if (path == WatchFaceProtocol.OPERATION_RESPONSE) googleRoute(run) else run.session.companionRoute
        if (route != null && route != expectedRoute) return false
        return try {
            if (path == WatchFaceProtocol.OPERATION_RESPONSE) {
                val response = WatchFaceProtocol.operation(payload)
                val pending = response.requestId?.let { run.pending[it] }
                if (pending != null) pending.complete(response) else log("unmatched_favorite_response", details = mapOf("status" to (response.status?.toString() ?: "absent")))
                true
            } else if (path in setOf(WatchFaceProtocol.MOBVOI_IMAGE, WatchFaceProtocol.MOBVOI_PHOTO_INFO, WatchFaceProtocol.MOBVOI_PHOTO_CONFIRM)) {
                require(payload.size <= ProtocolLimits.MAX_MESSAGE_BYTES) { "Watch face response byte budget exceeded" }
                if (run.legacy[path]?.complete(payload.copyOf()) != true) log("unsolicited_photo_response", details = mapOf("path" to path, "bytes" to payload.size.toString()))
                true
            } else if (path == WatchFaceProtocol.MOBVOI_DOWNLOAD) {
                result(run, WatchFaceOperationResult(WatchFaceOutcome.UNKNOWN, detail = "watch_requested_store_download")); true
            } else false
        } catch (problem: Exception) { log("message_decode_failed", problem, mapOf("path" to path, "bytes" to payload.size.toString())); true }
    }

    override suspend fun refresh(): WatchFaceManagementState {
        active?.let { run -> try { inRun(run) { data.requestSync() } } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { log("data_sync_refresh_failed", problem) } }
        readInventory()
        return state.value
    }
    private suspend fun readInventory() = refreshLock.withLock {
        val run = active ?: return@withLock
        val scope = run.session.scope
        val items = mutableListOf<DataItemRecord>()
        for (pathPrefix in listOf(WatchFaceProtocol.INSTALLED_PREFIX, WatchFaceProtocol.FAVORITE_PREFIX, WatchFaceProtocol.ORDER_PATH, WatchFaceProtocol.ACTIVE_PATH)) {
            var offset = 0
            while (items.size < config.maxInventoryItems) {
                current(run)
                val page = data.items(scope, DataItemQuery(route = googleRoute(run), uriPrefix = "wear://${scope.remoteNodeId}$pathPrefix",
                    includeDeleted = false, offset = offset, limit = minOf(100, config.maxInventoryItems - items.size)))
                items += page.items
                offset = page.nextOffset ?: break
            }
        }
        val installed = mutableListOf<WatchFaceInstalled>(); val favorites = mutableListOf<WatchFaceFavorite>()
        var order = emptyList<Int>(); var currentFace: WatchFaceCurrent? = null
        for (item in items) try {
            val uri = URI(item.uri)
            if (uri.authority != scope.remoteNodeId || item.deleted) continue
            val path = uri.path ?: continue; val bytes = item.data ?: continue
            when {
                path.startsWith(WatchFaceProtocol.INSTALLED_PREFIX) -> {
                    val values = NotificationCodec.decode(bytes).fields
                    fun text(key: String) = (values[key] as? DataMapValue.Text)?.value
                    val pkg = text("KEY_WATCH_FACE_PACKAGE"); val cls = text("KEY_WATCH_FACE_ACTIVITY")
                    if (pkg == null || cls == null) { log("installed_item_missing_identity"); continue }
                    val asset = (values["KEY_WATCH_FACE_PREVIEW"] as? DataMapValue.AssetIndex)?.value?.toString()
                    installed += WatchFaceInstalled("$pkg/$cls", text("KEY_WATCH_FACE_DISPLAYED_NAME"),
                        (values["KEY_WATCH_FACE_SDK_VERSION"] as? DataMapValue.IntNumber)?.value,
                        (values["KEY_WATCH_FACE_MULTIPLE_FAVORITES_OPT_IN"] as? DataMapValue.Bool)?.value,
                        item.assets.firstOrNull { it.name == asset }?.digest, item.uri)
                }
                path == WatchFaceProtocol.ACTIVE_PATH -> WatchFaceProtocol.active(bytes).let { currentFace = WatchFaceCurrent(it.componentName, it.favoriteId) }
                path == WatchFaceProtocol.ORDER_PATH -> order = WatchFaceProtocol.order(bytes)
                path.startsWith(WatchFaceProtocol.FAVORITE_PREFIX) -> {
                    val favorite = WatchFaceProtocol.favorite(bytes)
                    favorites += favorite.toApi(item.uri, item.assets.firstOrNull { it.name == favorite.previewAssetName }?.digest)
                }
            }
        } catch (problem: Exception) { log("inventory_item_unavailable", problem, mapOf("bytes" to (item.data?.size ?: 0).toString())) }
        current(run)
        val positions = order.withIndex().associate { it.value to it.index }
        mutableState.update { it.copy(installed = installed.toList(), favorites = favorites.sortedBy { f -> positions[f.id] ?: Int.MAX_VALUE },
            favoriteOrder = order, current = currentFace, inventoryComplete = false) }
        if (items.size == config.maxInventoryItems) log("inventory_local_budget", details = mapOf("items" to items.size.toString()))
    }

    override suspend fun previewAsset(digest: String): WatchFaceImage? {
        val run = active ?: return null
        val asset = inRun(run) { data.asset(run.session.scope, googleRoute(run), digest) }
        if (asset?.data != null) return WatchFaceImage(checkNotNull(asset.data))
        try { inRun(run) { data.requestAsset(run.session.scope, googleRoute(run), digest) } }
        catch (cancelled: CancellationException) { throw cancelled } catch (problem: Exception) { log("preview_asset_unavailable", problem) }
        return null
    }
    override suspend fun addFavorite(componentName: String, flavorId: String?) = favoriteOperation(WatchFaceProtocol.ADD_PATH) { WatchFaceProtocol.add(it, componentName, flavorId) }
    override suspend fun removeFavorite(favoriteId: Int, fallbackFavoriteId: Int?) = favoriteOperation(WatchFaceProtocol.REMOVE_PATH) { WatchFaceProtocol.remove(it, favoriteId, fallbackFavoriteId) }
    override suspend fun reorderFavorites(favoriteIds: List<Int>) = favoriteOperation(WatchFaceProtocol.REORDER_PATH) { WatchFaceProtocol.reorder(it, favoriteIds) }
    override suspend fun changeActiveFavorite(favoriteId: Int) = favoriteOperation(WatchFaceProtocol.CHANGE_ACTIVE_PATH) { WatchFaceProtocol.change(it, favoriteId) }
    override suspend fun uninstallPackage(packageName: String): WatchFaceOperationResult = perform { run ->
        val request = uninstallRequest ?: return@perform WatchFaceOperationResult(WatchFaceOutcome.UNAVAILABLE, detail = "device_settings_uninstall_adapter_not_supplied")
        request(run.session, packageName)
    }
    private suspend fun favoriteOperation(path: String, encode: (Int) -> ByteArray): WatchFaceOperationResult = perform { run ->
        require(run.pending.size < config.maxPendingOperations) { "Watch face pending operation budget exceeded" }
        val id = ids.incrementAndGet(); val pending = CompletableDeferred<WatchFaceWireOperation>(); run.pending[id] = pending
        try {
            send(run, path, encode(id), googleRoute(run))
            val response = withTimeout(config.timeoutMillis) { pending.await() }
            dirty.trySend(Unit)
            WatchFaceOperationResult(if (response.status == 0) WatchFaceOutcome.ACKNOWLEDGED else if (response.status in 1..3) WatchFaceOutcome.FAILED else WatchFaceOutcome.UNKNOWN,
                response.status, "favorites_operation", mapOf("request_id" to id.toString()))
        } finally { run.pending.remove(id) }
    }

    override suspend fun selectMobvoiFace(settings: WatchFacePhotoSettings): WatchFaceOperationResult = perform { run ->
        send(run, WatchFaceProtocol.MOBVOI_SET, WatchFaceMobvoiJson.encode(settings))
        WatchFaceOperationResult(WatchFaceOutcome.SUBMITTED, detail = "selector_has_no_observed_ack")
    }
    override suspend fun queryPhoto(): WatchFacePhoto = legacyLock.withLock {
        val run = active ?: return@withLock state.value.photo ?: WatchFacePhoto()
        val previous = state.value.photo ?: WatchFacePhoto()
        var settings = previous.settings; var image = previous.image; var imageClassName = previous.imageClassName
        perform { same ->
            settings = WatchFaceMobvoiJson.settings(legacyRequest(same, WatchFaceProtocol.MOBVOI_PHOTO_INFO, byteArrayOf(), WatchFaceProtocol.MOBVOI_PHOTO_INFO))
            WatchFaceOperationResult(WatchFaceOutcome.ACKNOWLEDGED, detail = "photo_settings_received")
        }
        if (active === run) perform { same ->
            val response = WatchFaceMobvoiJson.image(legacyRequest(same, WatchFaceProtocol.MOBVOI_IMAGE, byteArrayOf(), WatchFaceProtocol.MOBVOI_IMAGE))
            imageClassName = response.first; image = response.second
            WatchFaceOperationResult(if (image != null) WatchFaceOutcome.ACKNOWLEDGED else WatchFaceOutcome.UNKNOWN, detail = "photo_preview_received")
        }
        val value = WatchFacePhoto(settings, image, imageClassName)
        if (active === run) mutableState.update { it.copy(photo = value) }
        value
    }
    override suspend fun setPhoto(settings: WatchFacePhotoSettings?, image: ByteArray): WatchFaceOperationResult = legacyLock.withLock {
        perform { run ->
            val body = if (settings == null) image.copyOf() else WatchFacePhotoProtocol.combine(WatchFaceMobvoiJson.encode(settings), image)
            require(body.size <= ProtocolLimits.MAX_MESSAGE_BYTES) { "Watch face photo byte budget exceeded" }
            legacyRequest(run, if (settings == null) WatchFaceProtocol.MOBVOI_BACKGROUND else WatchFaceProtocol.MOBVOI_PHOTO_COMBINED, body, WatchFaceProtocol.MOBVOI_PHOTO_CONFIRM)
            // The watch now shows what was sent, so a page showing its photo needn't ask again.
            if (active === run) mutableState.update { it.copy(photo = WatchFacePhoto(settings ?: it.photo?.settings, WatchFaceImage(image), it.photo?.imageClassName)) }
            WatchFaceOperationResult(WatchFaceOutcome.ACKNOWLEDGED, detail = "uncorrelated_background_save_confirmation")
        }
    }
    private suspend fun legacyRequest(run: Run, path: String, bytes: ByteArray, reply: String): ByteArray {
        val pending = CompletableDeferred<ByteArray>(); run.legacy[reply] = pending
        try { send(run, path, bytes); return withTimeout(config.timeoutMillis) { pending.await() } }
        finally { run.legacy.remove(reply) }
    }

    override suspend fun startEditing(favoriteId: Int, width: Int, height: Int, abortCurrent: Boolean): WatchFaceEditingState = editLock.withLock {
        perform { run ->
            run.channel?.close(); run.token = null; run.schema = null; run.complicationStates = null
            editState(run, WatchFaceEditPhase.STARTING)
            val channel = run.session.openChannel(WatchFaceProtocol.EDIT_CHANNEL, googleRoute(run))
            if (active !== run) { channel.close(); throw CancellationException("Watch face session ended") }
            if (channel.info.peer != run.session.peer || channel.info.sessionId != run.session.sessionId) {
                channel.close(); throw IllegalStateException("Stale watch face editing channel") }
            run.channel = channel
            val pending = CompletableDeferred<WatchFaceEditMessage>(); run.start = pending
            CoroutineScope(worker.coroutineContext + run.job).launch { readEditing(run, channel) }
            try {
                channel.write(WatchFaceProtocol.frame(WatchFaceProtocol.startEditing(favoriteId, width, height, abortCurrent)))
                val response = withTimeout(config.timeoutMillis) { pending.await() }
                run.token = response.token
                val success = response.status == 1 && response.token != null && run.channel === channel && channel.state.value.phase == ChannelPhase.OPEN
                editState(run, if (success) WatchFaceEditPhase.ACTIVE else WatchFaceEditPhase.FAILED, startStatus = response.status)
                if (!success) { channel.close(); if (run.channel === channel) run.channel = null }
                WatchFaceOperationResult(if (success) WatchFaceOutcome.ACKNOWLEDGED else WatchFaceOutcome.FAILED, response.status, "editing_session_start")
            } catch (problem: Throwable) { channel.close(); if (run.channel === channel) run.channel = null; editState(run, WatchFaceEditPhase.FAILED); throw problem }
            finally { run.start = null }
        }
        state.value.editing
    }
    override suspend fun updateEditing(options: WatchFacePreviewOptions) = previewEditing(options)
    override suspend fun previewEditing(options: WatchFacePreviewOptions): WatchFaceImage? = render(options, null, null)
    override suspend fun previewComplication(complication: WatchFaceComplication, options: WatchFacePreviewOptions, calendarTimeMillis: Long?) = render(options, complication, calendarTimeMillis)
    private suspend fun render(options: WatchFacePreviewOptions, complication: WatchFaceComplication?, time: Long?): WatchFaceImage? = editLock.withLock {
        var image: WatchFaceImage? = null
        perform { run ->
            val channel = checkNotNull(run.channel) { "Editing channel not active" }; val token = checkNotNull(run.token) { "Editing token not available" }
            val id = ids.incrementAndGet(); val pending = CompletableDeferred<WatchFaceEditMessage>(); run.screenshots[id] = pending
            try {
                val request = if (complication == null) WatchFaceProtocol.screenshot(id, token, options.style?.wire(), options.complications.map { it.wire() }, options.compressionQuality,
                    options.compressionType, options.renderParametersProto)
                else WatchFaceProtocol.complicationScreenshot(id, token, complication.wire(), options.style?.wire(), options.compressionQuality, options.compressionType, time, options.renderParametersProto)
                channel.write(WatchFaceProtocol.frame(request))
                val response = withTimeout(config.timeoutMillis) { pending.await() }
                val screenshot = response.screenshot
                if (response.status == 1 && screenshot != null) image = WatchFaceImage(screenshot, if (options.compressionType in 0..2) "image/webp" else null)
                WatchFaceOperationResult(if (image != null) WatchFaceOutcome.ACKNOWLEDGED else WatchFaceOutcome.FAILED, response.status, "editing_preview")
            } finally { run.screenshots.remove(id) }
        }
        image
    }
    override suspend fun endEditing(favorite: WatchFaceFavorite): WatchFaceOperationResult = finishEditing(favorite)
    override suspend fun abortEditing(): WatchFaceOperationResult = finishEditing(null)
    private suspend fun finishEditing(favorite: WatchFaceFavorite?): WatchFaceOperationResult = editLock.withLock {
        perform { run ->
            val channel = checkNotNull(run.channel) { "Editing channel not active" }; val token = checkNotNull(run.token) { "Editing token not available" }
            val pending = CompletableDeferred<WatchFaceEditMessage>(); run.end = pending
            editState(run, WatchFaceEditPhase.ENDING)
            try {
                val bytes = if (favorite == null) WatchFaceProtocol.abortEditing(token) else WatchFaceProtocol.endEditing(token, favorite.wire())
                channel.write(WatchFaceProtocol.frame(bytes))
                val response = withTimeout(config.timeoutMillis) { pending.await() }
                val success = if (favorite == null) response.status == 5 else response.status == 1
                editState(run, if (favorite == null && success) WatchFaceEditPhase.ABORTED else if (success) WatchFaceEditPhase.ENDED else WatchFaceEditPhase.FAILED, endStatus = response.status)
                dirty.trySend(Unit)
                WatchFaceOperationResult(if (success) WatchFaceOutcome.ACKNOWLEDGED else WatchFaceOutcome.FAILED, response.status, "editing_session_end")
            } finally { run.end = null; channel.close(); if (run.channel === channel) run.channel = null; run.token = null }
        }
    }
    private suspend fun readEditing(run: Run, channel: WearChannel) {
        val decoder = WatchFaceFrameDecoder()
        try {
            while (true) {
                val bytes = channel.read() ?: break
                current(run)
                if (run.channel !== channel) return
                for (frame in decoder.feed(bytes)) {
                    val message = WatchFaceProtocol.editingMessage(frame)
                    when (message.kind) {
                        1 -> { if (message.status == 1) run.token = message.token
                            if (run.start?.complete(message) != true) log("unsolicited_edit_start", details = mapOf("status" to message.status.toString())) }
                        2 -> if (message.token == run.token) {
                            run.end?.complete(message)
                            if (run.end == null) { editState(run, WatchFaceEditPhase.ENDED, endStatus = message.status); run.token = null
                                run.screenshots.values.forEach { it.completeExceptionally(IllegalStateException("Editing session ended")) }
                                channel.close(); run.channel = null }
                        } else log("editing_token_mismatch")
                        3 -> if (message.requestId?.let { run.screenshots[it]?.complete(message) } != true) log("unmatched_edit_preview")
                        4 -> { run.schema = message.styleSchema ?: run.schema; run.complicationStates = message.complicationStates ?: run.complicationStates
                            editState(run, state.value.editing.phase, state.value.editing.startStatus, state.value.editing.endStatus) }
                        else -> log("unknown_edit_message", details = mapOf("fields" to message.unknownFields.joinToString(",")))
                    }
                }
            }
            decoder.finish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("editing_stream_failed", problem) }
        finally {
            channel.close()
            if (run.channel === channel) {
                run.channel = null; run.token = null
                val problem = IllegalStateException("Watch face editing stream ended")
                run.start?.completeExceptionally(problem); run.end?.completeExceptionally(problem); run.screenshots.values.forEach { it.completeExceptionally(problem) }
                if (active === run && state.value.editing.phase !in setOf(WatchFaceEditPhase.ENDED, WatchFaceEditPhase.ABORTED, WatchFaceEditPhase.FAILED)) editState(run, WatchFaceEditPhase.FAILED)
            }
        }
    }
    private fun editState(run: Run, phase: WatchFaceEditPhase, startStatus: Int? = state.value.editing.startStatus, endStatus: Int? = state.value.editing.endStatus) {
        if (active === run) mutableState.update { it.copy(editing = WatchFaceEditingState(phase, run.token, startStatus, endStatus, run.schema, run.complicationStates)) }
    }

    override suspend fun transferMwfArchive(archive: WatchFaceArchive, channelPath: String, route: ApplicationRoute): WatchFaceOperationResult = perform { run ->
        val uri = URI(channelPath)
        val finalSegment = uri.path?.substringAfterLast('/')
        require(!finalSegment.isNullOrEmpty() && finalSegment !in setOf(".", "..") && '\\' !in finalSegment && '\u0000' !in finalSegment) { "Unsafe MWF receiver file name" }
        val inspection = withContext(Dispatchers.IO) { WatchFaceArchiveInspector.inspect(archive, config.maxArchiveBytes, config.maxExpandedArchiveBytes, config.maxArchiveEntries) }
        current(run)
        val channel = run.session.openChannel(channelPath, route)
        if (active !== run) { channel.close(); throw CancellationException("Watch face session ended") }
        if (channel.info.peer != run.session.peer || channel.info.sessionId != run.session.sessionId) { channel.close(); throw IllegalStateException("Stale MWF archive channel") }
        val registration = run.job.invokeOnCompletion { channel.close() }
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var transferred = 0L
            withContext(Dispatchers.IO) {
                archive.open().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); current(run)
                        val count = localFileIo(input) { input.read(buffer) }
                        if (count < 0) break
                        if (count == 0) continue
                        transferred += count
                        require(transferred <= config.maxArchiveBytes && transferred <= inspection.bytes) { "MWF archive changed size" }
                        digest.update(buffer, 0, count); channel.write(buffer.copyOf(count))
                    }
                }
            }
            val sha256 = with(WatchFaceArchiveInspector) { digest.digest().hex() }
            require(transferred == inspection.bytes && sha256 == inspection.sha256) { "MWF archive changed during transfer" }
            channel.finishOutput(); channel.closeGracefully()
            WatchFaceOperationResult(WatchFaceOutcome.TRANSFERRED_UNVERIFIED, detail = "zip_schema_and_remote_installation_unverified",
                metadata = mapOf("bytes" to transferred.toString(), "entries" to inspection.entries.toString(), "sha256" to sha256))
        } finally { registration.dispose(); channel.close() }
    }

    private suspend fun send(run: Run, path: String, bytes: ByteArray, route: ApplicationRoute = run.session.companionRoute) = sendLock.withLock {
        current(run); run.session.sendMessage(path, bytes, route)
    }
    private fun googleRoute(run: Run) = config.googleRoute ?: run.session.companionRoute
    private fun current(run: Run) { if (closed || active !== run || !run.job.isActive) throw CancellationException("Stale watch face session") }
    private suspend fun <T> inRun(run: Run, action: suspend () -> T): T = coroutineScope {
        current(run); val child = currentCoroutineContext().job
        val registration = run.job.invokeOnCompletion { child.cancel(CancellationException("Watch face session ended")) }
        try { action() } finally { registration.dispose() }
    }
    private suspend fun perform(action: suspend (Run) -> WatchFaceOperationResult): WatchFaceOperationResult {
        val run = active ?: return WatchFaceOperationResult(WatchFaceOutcome.UNAVAILABLE, detail = "watch_not_connected")
        val value = try { inRun(run) { action(run) } }
        catch (_: TimeoutCancellationException) { WatchFaceOperationResult(WatchFaceOutcome.TIMED_OUT, detail = "remote_confirmation_timeout") }
        catch (cancelled: CancellationException) { if (currentCoroutineContext().isActive && active !== run) WatchFaceOperationResult(WatchFaceOutcome.INTERRUPTED, detail = "session_ended") else throw cancelled }
        catch (problem: Exception) { log("operation_failed", problem); WatchFaceOperationResult(WatchFaceOutcome.FAILED, detail = problem.javaClass.simpleName) }
        result(run, value); return value
    }
    private fun result(run: Run, value: WatchFaceOperationResult) { if (active === run) mutableState.update { it.copy(lastOperation = value) } }
    private fun log(event: String, problem: Throwable? = null, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("watch_faces", event, details = details + (problem?.let { mapOf("exception" to it.javaClass.simpleName) } ?: emptyMap()))) }
    }
    override fun close() { if (closed) return; active?.let { detach(it.session.sessionId) }; closed = true; dirty.close(); owner.cancel() }
    private fun WatchFaceWireFavorite.toApi(uri: String?, digest: String?) = WatchFaceFavorite(id, componentName,
        WatchFaceStyle(style?.let(WatchFaceProtocol::styleOptions).orEmpty(), style), complications.map { WatchFaceComplication(it.slotId, it.providerComponent, it.dataType, it.raw) },
        preview?.let { WatchFaceImage(it) }, previewAssetName, digest, instanceId, uri)
    private fun WatchFaceStyle.wire(): ByteArray = if (options.isEmpty() && rawProto != null) checkNotNull(rawProto) else WatchFaceProtocol.style(options, rawProto)
    private fun WatchFaceComplication.wire() = WatchFaceWireComplication(slotId, providerComponent, dataType, rawProto)
    private fun WatchFaceFavorite.wire() = WatchFaceWireFavorite(id, componentName, style.wire(), complications.map { it.wire() }, previewAssetName, instanceId, preview?.bytes)
}
