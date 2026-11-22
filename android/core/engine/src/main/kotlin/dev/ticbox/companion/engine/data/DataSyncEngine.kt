package dev.ticbox.companion.engine.data

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns DataApi ordering, durable resume and asset exchange; never owns the physical writer. */
class DataSyncEngine(
    private val scope: CoroutineScope,
    private val store: DataSyncStore,
    private val config: DataSyncConfig = DataSyncConfig(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private var progressTransport: DataSyncProgressTransport? = null,
) : DataSyncClient {
    private val lock = Any()
    private val publications = Mutex()
    private var closed = false
    private var active: Run? = null
    private val mutableState = MutableStateFlow(DataSyncState())
    override val state = mutableState.asStateFlow()
    private val changed = MutableStateFlow(0L)
    @Volatile private var remoteItemObserver: ((Long, DataItemRecord) -> Unit)? = null
    private val inboundItemObservers = CopyOnWriteArrayList<(Long, DataItemRecord) -> Unit>()

    private sealed interface Work {
        class Inbound(val bytes: ByteArray) : Work
        class SendItem(val record: StoredDataItem) : Work
        class Fetch(val route: ApplicationRoute, val digest: String, val permissionOnly: Boolean) : Work
        data object Sync : Work
        data object RemoteSnapshot : Work
        data object PumpAssets : Work
    }
    private class Attachment(val digest: String, val owners: Set<ApplicationRoute>) {
        val bytes = ByteArrayOutputStream()
    }
    private data class FetchIntent(val route: ApplicationRoute, val permissionOnly: Boolean)
    private data class AssetOffer(val item: DataItemRecord, val digest: String)
    private inner class Run(val session: DataSyncSession) {
        val queue = Channel<Work>(config.inboundQueueCapacity)
        val progressBarrier = Mutex()
        val attachments = linkedMapOf<String, Attachment>()
        val fetches = linkedMapOf<String, FetchIntent>()
        val sends = linkedSetOf<String>()
        val fileSends = linkedMapOf<String, Set<ApplicationRoute>>()
        val acknowledgedFiles = linkedMapOf<String, Set<ApplicationRoute>>()
        // Keep a cursor over the durable replay snapshot, rather than an unbounded asset queue.
        // ACKs advance it without blocking this same worker from receiving those ACKs.
        var assetOffers: Iterator<AssetOffer>? = null
        var assetReplayRequested = false
        var missingAssetOffers = false
        val unavailableFetches = linkedSetOf<Pair<ApplicationRoute, String>>()
        var remoteSync: DataSyncStart? = null
        var frozen = false
        lateinit var job: Job
    }

    fun setProgressTransport(transport: DataSyncProgressTransport) { synchronized(lock) { alive(); progressTransport = transport } }
    /** Observers must enqueue promptly; application callbacks cannot terminate the DataApi reader. */
    fun setRemoteItemObserver(observer: ((Long, DataItemRecord) -> Unit)?) { synchronized(lock) { alive(); remoteItemObserver = observer } }

    /** Fresh wire observations, including replayed duplicates; never restores items from the cache. */
    fun observeInboundItems(observer: (Long, DataItemRecord) -> Unit): AutoCloseable {
        synchronized(lock) { alive(); inboundItemObservers += observer }
        return AutoCloseable { inboundItemObservers -= observer }
    }

    /** Read-only remote replay for a session-bound feature probe; durable cursors are unchanged. */
    suspend fun requestRemoteSnapshot(scope: DataScope, sessionId: Long) {
        checkAlive()
        val run = matching(scope)?.takeIf { it.session.sessionId == sessionId } ?: throw NotConnectedException()
        run.queue.send(Work.RemoteSnapshot)
    }

    fun attach(session: DataSyncSession) {
        val run = Run(session)
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                store.rememberScope(session.dataScope)
                sendSync(run)
                resumeAssets(run)
                for (work in run.queue) {
                    if (!current(run)) break
                    try {
                        when (work) {
                            is Work.Inbound -> inbound(run, work.bytes)
                            is Work.SendItem -> sendItem(run, work.record)
                            is Work.Fetch -> { run.unavailableFetches.remove(work.route to work.digest); fetch(run, work.route, work.digest, work.permissionOnly) }
                            Work.Sync -> { run.unavailableFetches.clear(); sendSync(run); resumeAssets(run) }
                            Work.RemoteSnapshot -> {
                                transmit(run, DataProtocol.syncStart(emptyMap(), run.session.remoteNodeId))
                                diagnostic(run, "remote_snapshot_requested", DiagnosticSeverity.INFO)
                            }
                            Work.PumpAssets -> {
                                resumeAssets(run)
                                if (run.missingAssetOffers) {
                                    run.missingAssetOffers = false
                                    offerOutgoingAssets(run, store.replay(run.session.dataScope))
                                }
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        // Losing an item followed by later source cursors would make recovery impossible.
                        freeze(run, DataSyncIssue.STORAGE, "data_operation_failed", error.javaClass.simpleName)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { freeze(run, DataSyncIssue.SEND_FAILED, "data_session_start_failed", error.javaClass.simpleName) }
            finally {
                run.queue.cancel(); run.attachments.clear(); run.fetches.clear(); run.sends.clear()
                run.fileSends.clear(); run.acknowledgedFiles.clear(); run.assetOffers = null; run.unavailableFetches.clear()
            }
        }
        val previous = synchronized(lock) {
            alive()
            val old = active
            active = run
            mutableState.value = DataSyncState(DataSyncPhase.RESUMING, session.dataScope, session.sessionId)
            changed.value++
            old
        }
        previous?.let { it.frozen = true; it.queue.cancel(); it.job.cancel() }
        run.job.start()
    }

    /** Copies DataApi input in reader order, waiting for bounded capacity instead of losing a cursor. */
    suspend fun acceptInbound(sessionId: Long, envelope: ByteArray): Boolean {
        val run = synchronized(lock) { active?.takeIf { !closed && !it.frozen && it.session.sessionId == sessionId } } ?: return false
        val relevant = try { DataProtocol.hasData(envelope) } catch (error: Exception) {
            freeze(run, DataSyncIssue.MALFORMED_MESSAGE, "malformed_data_envelope", error.javaClass.simpleName)
            return false
        }
        if (!relevant) return false
        val work = Work.Inbound(envelope.copyOf())
        if (run.queue.trySend(work).isSuccess) return true
        if (!current(run)) return false
        diagnostic(run, "data_queue_backpressure", DiagnosticSeverity.INFO,
            mapOf("capacity" to config.inboundQueueCapacity.toString(), "decision" to "wait for queue capacity; retain input order and durable cursors"))
        return try {
            run.queue.send(work)
            true
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Replacement/closure cancels the old queue independently of this caller.
            false
        }
    }

    fun detach(sessionId: Long) {
        val run = synchronized(lock) {
            val old = active?.takeIf { it.session.sessionId == sessionId } ?: return
            active = null
            old.frozen = true
            mutableState.value = mutableState.value.copy(phase = DataSyncPhase.DISCONNECTED, pendingAssetFetches = 0, pendingAssetSends = 0, incomingAttachments = 0, unavailableAssets = 0)
            changed.value++
            old
        }
        run.queue.cancel(); run.job.cancel()
    }

    override suspend fun scopes(peer: PeerAddress?): List<DataScope> { checkAlive(); return store.scopes(peer) }
    override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage { checkAlive(); return store.items(scope, query) }
    override suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord = publications.withLock {
        checkAlive()
        val bytes = draft.data
        require((bytes?.size ?: 0) <= config.maxItemBytes) { "DataItem exceeds the local allocation budget" }
        val record = store.publishLocal(scope) { sequence ->
            val item = DataItemRecord(scope, route, draft.uri, scope.localNodeId, sequence, draft.deleted,
                bytes, draft.assets, draft.lastModifiedMillis, System.currentTimeMillis(), local = true)
            StoredDataItem(item, DataProtocol.encodeItem(item.toWire()))
        }
        synchronized(lock) { changed.value++ }
        matching(scope)?.let { run ->
            try { run.queue.send(Work.SendItem(record)) }
            catch (cancel: CancellationException) { currentCoroutineContext().ensureActive(); if (current(run)) throw cancel }
        }
        record.item
    }
    override suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String): DataItemRecord =
        publish(scope, route, DataItemDraft(uri, deleted = true))

    override suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray): DataAssetRecord {
        checkAlive()
        require(bytes.size <= config.maxAssetBytes) { "Asset exceeds the local allocation budget" }
        val copy = bytes.copyOf()
        val asset = DataAssetRecord(scope, DataProtocol.digest(copy), setOf(route), copy, System.currentTimeMillis())
        store.putAsset(asset)
        synchronized(lock) { changed.value++ }
        matching(scope)?.queue?.trySend(Work.PumpAssets)
        return checkNotNull(store.asset(scope, asset.digest))
    }
    override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String): DataAssetRecord? {
        checkAlive()
        return store.asset(scope, digest)?.takeIf { route in it.owners }
    }
    override suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean) {
        checkAlive()
        val run = matching(scope) ?: throw NotConnectedException()
        run.queue.send(Work.Fetch(route, digest, permissionOnly))
    }
    override suspend fun requestSync() {
        checkAlive()
        val run = synchronized(lock) { active?.takeIf { !it.frozen } } ?: throw NotConnectedException()
        run.queue.send(Work.Sync)
    }

    private suspend fun sendSync(run: Run) {
        transmit(run, DataProtocol.syncStart(store.cursors(run.session.dataScope), run.session.remoteNodeId))
        diagnostic(run, "sync_start_sent", DiagnosticSeverity.INFO)
    }
    private suspend fun resumeAssets(run: Run) {
        for ((route, digest) in store.missingAssets(run.session.dataScope)) {
            if (run.fetches.size >= config.maxPendingAssets) break
            if (route to digest in run.unavailableFetches) continue
            val bytes = store.asset(run.session.dataScope, digest)?.data
            fetch(run, route, digest, permissionOnly = bytes != null)
        }
    }
    private suspend fun inbound(run: Run, bytes: ByteArray) {
        val parts = DataProtocol.split(bytes)
        // These are independent optional protobuf fields; a malformed sibling does not discard others.
        for ((number, raw) in parts.parts) {
            if (!current(run)) return
            try {
                when (number) {
                    8 -> receiveSync(run, raw)
                    9 -> receiveItem(run, raw)
                    4 -> receiveAsset(run, raw, parts.hasFile, bytes)
                    5 -> {
                        val digest = GmsStructs.decode("AckAsset", raw)["digest"] as String
                        if (!run.sends.remove(digest)) diagnostic(run, "unexpected_asset_ack", details = mapOf("digest" to digest))
                        else run.fileSends.remove(digest)?.let { owners ->
                            run.acknowledgedFiles.remove(digest)
                            run.acknowledgedFiles[digest] = owners
                            while (run.acknowledgedFiles.size > config.maxPendingAssets) run.acknowledgedFiles.remove(run.acknowledgedFiles.keys.first())
                        }
                        counts(run)
                        pumpOutgoingAssets(run)
                    }
                    6 -> receiveFetch(run, raw)
                    12 -> receivePiece(run, raw)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: ProtocolException) {
                diagnostic(run, "malformed_data_member", details = mapOf("field" to number.toString(), "cause" to error.javaClass.simpleName))
                if (number == 9 || number == 8) freeze(run, DataSyncIssue.MALFORMED_MESSAGE, "cursor_resume_required", "unreadable_data_item_or_sync")
            } catch (error: IllegalArgumentException) {
                diagnostic(run, "unrepresentable_data_member", details = mapOf("field" to number.toString(), "cause" to error.javaClass.simpleName))
                if (number == 9) freeze(run, DataSyncIssue.MALFORMED_MESSAGE, "cursor_resume_required", "unrepresentable_item_identity")
            }
        }
    }
    private suspend fun receiveSync(run: Run, raw: ByteArray) {
        val sync = DataProtocol.decodeSyncStart(raw)
        run.remoteSync = sync
        update(run) { it.copy(phase = DataSyncPhase.ACTIVE) }
        val replay = store.replay(run.session.dataScope)
        val sorted = if (sync.version < 2) replay.sortedBy { it.relaySequence }
            else replay.sortedWith(compareBy<StoredDataItem> { it.item.sourceNodeId }.thenBy { it.item.sequenceId })
        for (record in sorted) {
            val cursor = if (sync.version < 2) sync.receivedSequenceId else sync.cursors[record.item.sourceNodeId] ?: -1L
            val sequence = if (sync.version < 2) record.relaySequence else record.item.sequenceId
            if (sequence > cursor) sendItem(run, record, offerAssets = false)
        }
        // A peer can have committed the DataItem cursor while its assets remain buffered.
        // Reoffer referenced files independently of the DataItem source cursor on reconnect.
        offerOutgoingAssets(run, sorted)
        diagnostic(run, "remote_sync_start", DiagnosticSeverity.INFO, mapOf("version" to sync.version.toString(), "sourceCount" to sync.cursors.size.toString()))
    }
    private suspend fun sendItem(run: Run, record: StoredDataItem, offerAssets: Boolean = true) {
        val sync = run.remoteSync ?: return // Peer SyncStart determines version and restrictions.
        val notification = record.item.uri.startsWith("wear://${run.session.localNodeId}${NotificationProtocol.STREAM_PREFIX}")
        if (!DataProtocol.allows(record.item.toWire(), sync.restrictions, run.session.localNodeId)) {
            if (notification) notificationSendDiagnostic(run, record, "filtered_by_peer")
            return
        }
        val raw = if (sync.version < 2) DataProtocol.withItemSequence(record.wireDataItem, record.relaySequence) else record.wireDataItem
        transmit(run, DataProtocol.wrapDataItem(raw))
        update(run) { it.copy(sentItems = it.sentItems + 1) }
        if (notification) notificationSendDiagnostic(run, record, "wire_written")
        if (offerAssets && !record.item.deleted && record.item.assets.isNotEmpty()) offerOutgoingAssets(run, listOf(record))
    }

    private fun assetOffers(records: List<StoredDataItem>): Iterator<AssetOffer> = records.asSequence()
        .filter { !it.item.deleted }
        .flatMap { record -> record.item.assets.asSequence().map { AssetOffer(record.item, it.digest) } }.iterator()

    private suspend fun offerOutgoingAssets(run: Run, records: List<StoredDataItem>) {
        if (run.remoteSync == null) return
        if (run.assetOffers == null) run.assetOffers = assetOffers(records)
        else run.assetReplayRequested = true // Live changes coalesce into one subsequent durable scan.
        pumpOutgoingAssets(run)
    }

    private suspend fun pumpOutgoingAssets(run: Run) {
        while (current(run) && run.sends.size < config.maxPendingAssets) {
            val sync = run.remoteSync ?: return
            val offers = run.assetOffers ?: if (run.assetReplayRequested) {
                run.assetReplayRequested = false
                assetOffers(store.replay(run.session.dataScope)).also { run.assetOffers = it }
            } else return
            if (!offers.hasNext()) { run.assetOffers = null; continue }
            val offer = offers.next()
            if (offer.item.scope != run.session.dataScope ||
                !DataProtocol.allows(offer.item.toWire(), sync.restrictions, run.session.localNodeId) ||
                offer.digest in run.sends) continue
            if (offer.item.route in run.acknowledgedFiles[offer.digest].orEmpty()) continue
            val asset = store.asset(run.session.dataScope, offer.digest)
            val file = asset?.data
            if (asset == null || file == null || offer.item.route !in asset.owners) {
                run.missingAssetOffers = true
                diagnostic(run, "outgoing_asset_unavailable", details = mapOf("reason" to "file_or_permission_missing"))
                continue
            }
            sendAsset(run, offer.digest, asset.owners, file, "push")
        }
    }

    private fun notificationSendDiagnostic(run: Run, record: StoredDataItem, decision: String) {
        diagnostic(run, "notification_item_send", DiagnosticSeverity.INFO, mapOf(
            "decision" to decision, "sequenceId" to record.item.sequenceId.toString(),
            "deleted" to record.item.deleted.toString(), "assetCount" to record.item.assets.size.toString(),
        ))
    }
    private suspend fun receiveItem(run: Run, raw: ByteArray) {
        if (raw.size > config.maxItemBytes) {
            freeze(run, DataSyncIssue.RESOURCE_LIMIT, "data_item_budget_exceeded", raw.size.toString()); return
        }
        val wire = DataProtocol.decodeItem(raw)
        val source = if ((run.remoteSync?.version ?: 2) < 2 || wire.sourceNodeId.isNullOrEmpty()) run.session.remoteNodeId else wire.sourceNodeId.orEmpty()
        if (wire.sourceNodeId.isNullOrEmpty()) diagnostic(run, "data_source_defaulted_to_peer", DiagnosticSeverity.INFO)
        wire.notices.forEach { diagnostic(run, "protobuf_compatibility", DiagnosticSeverity.INFO, mapOf("kind" to it.kind, "path" to it.path, "occurrences" to it.occurrences.toString())) }
        if (wire.unknownFieldNumbers.isNotEmpty()) diagnostic(run, "unknown_data_item_fields", DiagnosticSeverity.INFO, mapOf("fields" to wire.unknownFieldNumbers.sorted().joinToString(",")))
        val route = ApplicationRoute(wire.app.packageName, wire.app.packageDigest)
        val item = DataItemRecord(run.session.dataScope, route, wire.uri, source, wire.sequenceId, wire.deleted,
            wire.data, wire.assets.map { DataAssetReference(it.name, it.digest, it.type) }, wire.lastModifiedMillis, System.currentTimeMillis())
        val result = store.applyRemote(StoredDataItem(item, if (wire.sourceNodeId != source) DataProtocol.withItemSource(raw, source) else raw))
        if (current(run)) inboundItemObservers.forEach { observer ->
            try { observer(run.session.sessionId, item) }
            catch (problem: Exception) { diagnostic(run, "inbound_item_observer_failed", DiagnosticSeverity.WARNING,
                mapOf("exception" to problem.javaClass.simpleName)) }
        }
        if (result == RemoteApplyResult.APPLIED) try { remoteItemObserver?.invoke(run.session.sessionId, item) }
        catch (problem: Exception) { diagnostic(run, "remote_item_observer_failed", DiagnosticSeverity.WARNING, mapOf("exception" to problem.javaClass.simpleName)) }
        update(run) { if (result == RemoteApplyResult.APPLIED) it.copy(receivedItems = it.receivedItems + 1) else it.copy(duplicateItems = it.duplicateItems + 1) }
        if (result == RemoteApplyResult.APPLIED && !item.deleted) for (reference in item.assets) {
            if (reference.digest.isEmpty()) { diagnostic(run, "asset_reference_has_no_digest"); continue }
            val existing = store.asset(item.scope, reference.digest)
            if (existing?.data == null || route !in existing.owners) fetch(run, route, reference.digest, existing?.data != null)
        }
    }
    private suspend fun fetch(run: Run, route: ApplicationRoute, digest: String, permissionOnly: Boolean) {
        if (route to digest in run.unavailableFetches) return
        val old = run.fetches[digest]
        if (old != null && (!old.permissionOnly || permissionOnly)) return
        if (old == null && run.fetches.size >= config.maxPendingAssets) {
            diagnostic(run, "asset_fetch_budget_exceeded", details = mapOf("digest" to digest)); return
        }
        run.fetches[digest] = FetchIntent(route, permissionOnly)
        counts(run)
        transmit(run, DataProtocol.fetchAsset(route.toWire(), digest, permissionOnly))
    }
    private suspend fun receiveAsset(run: Run, raw: ByteArray, hasFile: Boolean, ownerBytes: ByteArray) {
        val asset = GmsStructs.decode("SetAsset", raw)
        val digest = asset["digest"] as String
        val permissions = asset["permissions"] as? DecodedStruct
        val owners = ((permissions?.get("apps") as? List<*>) ?: emptyList<Any>()).filterIsInstance<DecodedStruct>().mapNotNull {
            runCatching { ApplicationRoute(it["packageName"] as String, it["packageDigest"] as String) }.getOrElse {
                diagnostic(run, "unrepresentable_asset_app_key"); null
            }
        }.toSet()
        if (hasFile) {
            val ownerDigest = DataProtocol.digest(ownerBytes)
            if (ownerDigest !in run.attachments && run.attachments.size >= config.maxIncomingAttachments) {
                deferFetch(run, digest); issue(run, DataSyncIssue.RESOURCE_LIMIT)
                diagnostic(run, "asset_attachment_budget_exceeded", details = mapOf("digest" to digest)); resumeAssets(run); return
            }
            if (run.attachments.put(ownerDigest, Attachment(digest, owners)) != null) diagnostic(run, "asset_owner_restarted", DiagnosticSeverity.INFO)
            counts(run)
        } else commitAsset(run, digest, owners, asset["inlineData"] as? ByteArray)
    }
    private suspend fun receivePiece(run: Run, raw: ByteArray) {
        val piece = GmsStructs.decode("FilePiece", raw)
        val owner = piece["ownerMessageDigest"] as String
        val attachment = run.attachments[owner]
        if (attachment == null) { diagnostic(run, "asset_piece_unknown_owner", details = mapOf("ownerDigest" to owner)); return }
        val bytes = piece["data"] as ByteArray
        if (bytes.size > config.maxAssetBytes - attachment.bytes.size()) {
            run.attachments.remove(owner); deferFetch(run, attachment.digest); counts(run)
            issue(run, DataSyncIssue.RESOURCE_LIMIT)
            diagnostic(run, "asset_file_budget_exceeded", details = mapOf("digest" to attachment.digest)); resumeAssets(run); return
        }
        attachment.bytes.write(bytes)
        if (piece["finalPiece"] == true) {
            run.attachments.remove(owner); counts(run)
            val file = attachment.bytes.toByteArray()
            val reported = piece["fileDigest"] as? String
            val computed = DataProtocol.digest(file)
            if (reported != null && reported != computed) diagnostic(run, "file_piece_digest_mismatch", details = mapOf("reported" to reported, "computed" to computed))
            commitAsset(run, attachment.digest, attachment.owners, file)
        }
    }
    private suspend fun commitAsset(run: Run, digest: String, owners: Set<ApplicationRoute>, bytes: ByteArray?) {
        if (bytes != null && (bytes.size > config.maxAssetBytes || DataProtocol.digest(bytes) != digest)) {
            deferFetch(run, digest); counts(run)
            val overBudget = bytes.size > config.maxAssetBytes
            issue(run, if (overBudget) DataSyncIssue.RESOURCE_LIMIT else DataSyncIssue.ASSET_DIGEST_MISMATCH)
            diagnostic(run, if (overBudget) "asset_file_budget_exceeded" else "asset_digest_mismatch", details = mapOf("digest" to digest, "bytes" to bytes.size.toString()))
            resumeAssets(run)
            return // Source receiver also withholds ACK on actual SetAsset digest mismatch.
        }
        store.putAsset(DataAssetRecord(run.session.dataScope, digest, owners, bytes, System.currentTimeMillis()))
        val requested = run.fetches.remove(digest)
        counts(run)
        transmit(run, DataProtocol.ackAsset(digest)) // Durable bytes/ACL precede ACK.
        val committed = store.asset(run.session.dataScope, digest)
        if (requested != null && (committed?.data == null || requested.route !in committed.owners)) {
            deferFetch(run, digest, requested)
            issue(run, DataSyncIssue.ASSET_PERMISSION_MISSING)
            diagnostic(run, "asset_reply_unavailable", details = mapOf("digest" to digest, "filePresent" to (committed?.data != null).toString(), "permissionGranted" to (committed != null && requested.route in committed.owners).toString()))
        }
        resumeAssets(run)
        if (bytes == null && store.asset(run.session.dataScope, digest)?.data == null) {
            issue(run, DataSyncIssue.ASSET_PERMISSION_MISSING)
            diagnostic(run, "asset_metadata_without_file", details = mapOf("digest" to digest))
        }
    }
    private fun deferFetch(run: Run, digest: String, intent: FetchIntent? = run.fetches.remove(digest)) {
        if (intent == null) return
        if (run.unavailableFetches.size.toLong() < config.maxPendingAssets.toLong() * 8) run.unavailableFetches += intent.route to digest
        else {
            // Retain the unresolved bounded slot if the negative cache itself is full.
            run.fetches[digest] = intent
            diagnostic(run, "asset_unavailable_budget_exceeded")
        }
        counts(run)
    }

    private suspend fun receiveFetch(run: Run, raw: ByteArray) {
        val request = GmsStructs.decode("FetchAsset", raw)
        val route = ApplicationRoute(request["packageName"] as String, request["packageDigest"] as String)
        val digest = request["digest"] as String
        if (digest in run.sends) { diagnostic(run, "duplicate_asset_fetch", DiagnosticSeverity.INFO); return }
        val asset = store.asset(run.session.dataScope, digest)
        if (asset == null || route !in asset.owners) {
            diagnostic(run, "asset_fetch_without_permission", details = mapOf("digest" to digest))
            transmit(run, DataProtocol.setAsset(digest, emptySet()))
            return
        }
        val file = if (request["permissionOnly"] == true) null else asset.data
        if (run.sends.size >= config.maxPendingAssets) { diagnostic(run, "asset_send_budget_exceeded"); return }
        sendAsset(run, digest, asset.owners, file, "request")
    }

    private suspend fun sendAsset(run: Run, digest: String, assetOwners: Set<ApplicationRoute>, file: ByteArray?, trigger: String) {
        if (file != null) run.fileSends[digest] = assetOwners.toSet()
        run.sends += digest; counts(run)
        val owners = assetOwners.map { it.toWire() }.toSet()
        // Stock V1 ignores SetAsset.inlineData and ACKs its permissions without storing a file.
        // The CONNECT version, not the file size or SyncStart version, selects the attachment format.
        val inline = run.session.negotiatedWireVersion == 0
        if (file == null || inline) transmit(run, DataProtocol.setAsset(digest, owners, file))
        else {
            val owner = DataProtocol.setAsset(digest, owners, hasFile = true)
            transmit(run, owner)
            val ownerDigest = DataProtocol.digest(owner)
            var offset = 0
            do {
                val end = minOf(file.size, offset + config.filePieceBytes)
                transmit(run, DataProtocol.filePiece(ownerDigest, file.copyOfRange(offset, end), end == file.size, digest.takeIf { end == file.size }))
                offset = end
            } while (offset < file.size)
        }
        diagnostic(run, "asset_send", DiagnosticSeverity.INFO, mapOf("trigger" to trigger,
            "bytes" to (file?.size ?: 0).toString(), "ownerCount" to owners.size.toString(),
            "wireVersion" to run.session.negotiatedWireVersion.toString(),
            "encoding" to if (file == null) "permissions" else if (inline) "inline" else "file_pieces"))
    }

    /** Optional source-backed tracking RPC. Unsupported/disabled peers time out without inventing success. */
    override suspend fun awaitProgressBarrier(timeoutMillis: Long): DataSyncBarrierResult {
        require(timeoutMillis > 0)
        checkAlive()
        val run = synchronized(lock) { active?.takeIf { !it.frozen } }
            ?: return DataSyncBarrierResult(DataSyncBarrierStatus.INTERRUPTED, state.value.sessionId)
        val transport = synchronized(lock) { progressTransport }
            ?: return DataSyncBarrierResult(DataSyncBarrierStatus.UNAVAILABLE, run.session.sessionId)
        val trackerId = UUID.randomUUID().toString()
        var sentSequence: Long? = null
        var receivedSequence: Long? = null
        val status = try {
            withTimeout(timeoutMillis) {
                run.progressBarrier.withLock {
                    if (!current(run)) return@withLock DataSyncBarrierStatus.INTERRUPTED
                    val sequence = localSequenceForPeer(run)
                    sentSequence = sequence
                    val reply = DataProtocol.decodeProgress(transport.request(run.session,
                        DataProtocol.encodeProgress(trackerId, sequence), timeoutMillis))
                    val replySequence = reply.sequenceId
                    receivedSequence = replySequence
                    if (reply.trackerId != trackerId) diagnostic(run, "data_progress_tracker_mismatch", details = mapOf("decision" to "use session-correlated RPC sequence and retain mismatch diagnostic"))
                    if (replySequence == null) {
                        diagnostic(run, "data_progress_response_unverified")
                        DataSyncBarrierStatus.UNVERIFIED
                    } else if (waitForSequenceAndAssets(run, replySequence)) DataSyncBarrierStatus.COMPLETE
                    else DataSyncBarrierStatus.INTERRUPTED
                }
            }
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); DataSyncBarrierStatus.TIMED_OUT }
        catch (cancel: CancellationException) { currentCoroutineContext().ensureActive(); DataSyncBarrierStatus.INTERRUPTED }
        catch (error: Exception) {
            diagnostic(run, "data_progress_unavailable", details = mapOf("cause" to error.javaClass.simpleName))
            if (current(run)) DataSyncBarrierStatus.UNAVAILABLE else DataSyncBarrierStatus.INTERRUPTED
        }
        diagnostic(run, "data_progress_barrier", DiagnosticSeverity.INFO, mapOf("status" to status.name))
        return DataSyncBarrierResult(status, run.session.sessionId, trackerId, sentSequence, receivedSequence)
    }

    /** Parent registers this on the GMS AppKey RequestApi path, never on an arbitrary application route. */
    suspend fun respondProgressBarrier(sessionId: Long, payload: ByteArray, timeoutMillis: Long = 60_000): ByteArray {
        require(timeoutMillis > 0)
        val run = synchronized(lock) { active?.takeIf { !it.frozen && it.session.sessionId == sessionId } }
            ?: throw NotConnectedException()
        val request = DataProtocol.decodeProgress(payload)
        val trackerId = request.trackerId?.takeIf { it.isNotEmpty() } ?: return byteArrayOf()
        val sequence = request.sequenceId ?: 0L // Source proto default.
        return withTimeout(timeoutMillis) {
            if (!waitForSequenceAndAssets(run, sequence)) throw NotConnectedException()
            val localSequence = localSequenceForPeer(run)
            DataProtocol.encodeProgress(trackerId, localSequence)
        }
    }

    private suspend fun localSequenceForPeer(run: Run): Long = store.replay(run.session.dataScope)
        .asSequence().filter { it.item.sourceNodeId == run.session.localNodeId &&
            DataProtocol.allows(it.item.toWire(), run.remoteSync?.restrictions.orEmpty(), run.session.localNodeId) }
        .maxOfOrNull { it.item.sequenceId } ?: -1L

    private suspend fun waitForSequenceAndAssets(run: Run, target: Long): Boolean {
        while (current(run)) {
            val version = changed.value
            val received = store.cursors(run.session.dataScope)[run.session.remoteNodeId] ?: -1L
            if (received >= target && store.missingAssets(run.session.dataScope).isEmpty() &&
                state.value.pendingAssetFetches == 0 && state.value.incomingAttachments == 0) return current(run)
            changed.first { it != version || !current(run) }
        }
        return false
    }

    private suspend fun transmit(run: Run, bytes: ByteArray) {
        if (!current(run)) throw CancellationException("Stale DataApi session")
        try { run.session.sendEnvelope(bytes) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            freeze(run, DataSyncIssue.SEND_FAILED, "data_write_failed", error.javaClass.simpleName)
            throw CancellationException("DataApi writer failed", error)
        }
    }
    private fun current(run: Run) = synchronized(lock) { !closed && active === run && !run.frozen }
    private fun matching(scope: DataScope) = synchronized(lock) { active?.takeIf { !closed && !it.frozen && it.session.dataScope == scope } }
    private fun alive() { if (closed) throw BackendClosedException() }
    private fun checkAlive() = synchronized(lock) { alive() }
    private fun update(run: Run, transform: (DataSyncState) -> DataSyncState) = synchronized(lock) {
        if (active === run && !closed) { mutableState.value = transform(mutableState.value); changed.value++ }
    }
    private fun issue(run: Run, issue: DataSyncIssue) = update(run) { it.copy(lastIssue = issue) }
    private fun counts(run: Run) = update(run) { it.copy(pendingAssetFetches = run.fetches.size, pendingAssetSends = run.sends.size, incomingAttachments = run.attachments.size, unavailableAssets = run.unavailableFetches.size) }
    private fun freeze(run: Run, issue: DataSyncIssue, event: String, reason: String) {
        val shouldReconnect = synchronized(lock) {
            if (active !== run || closed || run.frozen) false else {
                run.frozen = true
                mutableState.value = mutableState.value.copy(phase = DataSyncPhase.NEEDS_RESYNC, lastIssue = issue)
                changed.value++
                true
            }
        }
        if (shouldReconnect) {
            diagnostic(run, event, details = mapOf("reason" to reason))
            run.queue.cancel()
            run.session.onResyncRequired(event)
            run.job.cancel()
        }
    }
    private fun diagnostic(run: Run, event: String, severity: DiagnosticSeverity = DiagnosticSeverity.WARNING, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("data", event, severity, run.session.peer, run.session.sessionId, details = details)) }
    }
    override fun close() {
        val run = synchronized(lock) {
            if (closed) return
            closed = true
            val old = active; active = null
            mutableState.value = mutableState.value.copy(phase = DataSyncPhase.CLOSED, pendingAssetFetches = 0, pendingAssetSends = 0, incomingAttachments = 0, unavailableAssets = 0)
            changed.value++
            old
        }
        run?.let { it.frozen = true; it.queue.cancel(); it.job.cancel() }
        inboundItemObservers.clear()
        remoteItemObserver = null
        store.close()
    }

    private fun DataItemRecord.toWire() = DataWireItem(route.toWire(), uri, sequenceId, sourceNodeId, deleted, data,
        assets.map { DataWireAssetRef(it.name, it.digest, it.type) }, lastModifiedMillis)
    private fun ApplicationRoute.toWire() = DataAppKey(packageName, packageDigest)
}
