package dev.ticbox.companion.api

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

class WatchFaceStyle(options: Map<String, ByteArray> = emptyMap(), rawProto: ByteArray? = null) {
    private val values = options.mapValues { it.value.copyOf() }
    private val raw = rawProto?.copyOf()
    val options: Map<String, ByteArray> get() = values.mapValues { it.value.copyOf() }
    val rawProto: ByteArray? get() = raw?.copyOf()
}
class WatchFaceComplication(val slotId: Int? = null, val providerComponent: String? = null, val dataType: Int? = null, rawProto: ByteArray? = null) {
    private val raw = rawProto?.copyOf()
    val rawProto: ByteArray? get() = raw?.copyOf()
}
class WatchFaceImage(bytes: ByteArray, val mimeType: String? = null) {
    private val body = bytes.copyOf()
    val bytes: ByteArray get() = body.copyOf()
    val sizeBytes get() = body.size
}
data class WatchFaceInstalled(
    val componentName: String,
    val displayedName: String? = null,
    val sdkVersion: Int? = null,
    val multipleFavorites: Boolean? = null,
    val previewDigest: String? = null,
    val sourceUri: String,
)
data class WatchFaceFavorite(
    val id: Int? = null,
    val componentName: String? = null,
    val style: WatchFaceStyle = WatchFaceStyle(),
    val complications: List<WatchFaceComplication> = emptyList(),
    val preview: WatchFaceImage? = null,
    val previewAssetName: String? = null,
    val previewDigest: String? = null,
    val instanceId: String? = null,
    val sourceUri: String? = null,
)
data class WatchFaceCurrent(val componentName: String?, val favoriteId: Int?)
data class WatchFacePhotoSettings(
    val packageName: String? = null,
    val className: String? = null,
    val category: Int = 2,
    val name: String? = null,
    val timePlace: Int? = null,
    val showBattery: Int? = null,
    val showDateAndWeek: Int? = null,
)
data class WatchFacePhoto(val settings: WatchFacePhotoSettings? = null, val image: WatchFaceImage? = null, val imageClassName: String? = null)
enum class WatchFaceOutcome { ACKNOWLEDGED, SUBMITTED, TRANSFERRED_UNVERIFIED, FAILED, TIMED_OUT, UNAVAILABLE, INTERRUPTED, UNKNOWN }
data class WatchFaceOperationResult(
    val outcome: WatchFaceOutcome,
    val statusCode: Int? = null,
    val detail: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
enum class WatchFaceEditPhase { IDLE, STARTING, ACTIVE, ENDING, ENDED, ABORTED, FAILED }
class WatchFaceEditingState(
    val phase: WatchFaceEditPhase = WatchFaceEditPhase.IDLE,
    val token: String? = null,
    val startStatus: Int? = null,
    val endStatus: Int? = null,
    styleSchema: ByteArray? = null,
    complicationStates: ByteArray? = null,
) {
    private val schema = styleSchema?.copyOf()
    private val states = complicationStates?.copyOf()
    val styleSchema: ByteArray? get() = schema?.copyOf()
    val complicationStates: ByteArray? get() = states?.copyOf()
}
data class WatchFacePreviewOptions(
    val style: WatchFaceStyle? = null,
    val complications: List<WatchFaceComplication> = emptyList(),
    val compressionQuality: Int? = null,
    /** Observed 0=unset, 1=WebP lossless, 2=WebP lossy; future values remain numeric. */
    val compressionType: Int = 1,
    val renderParametersProto: ByteArray? = null,
)
data class WatchFaceManagementState(
    val sessionId: Long = 0,
    val scope: DataScope? = null,
    val installed: List<WatchFaceInstalled> = emptyList(),
    val favorites: List<WatchFaceFavorite> = emptyList(),
    val favoriteOrder: List<Int> = emptyList(),
    val current: WatchFaceCurrent? = null,
    val photo: WatchFacePhoto? = null,
    val editing: WatchFaceEditingState = WatchFaceEditingState(),
    val lastOperation: WatchFaceOperationResult? = null,
    /** DataApi has no complete-inventory marker. */
    val inventoryComplete: Boolean = false,
)

/** Reopenable local file input. An implementation must never resolve a network URL. */
interface WatchFaceArchive {
    val sizeBytes: Long
    fun open(): InputStream
}

interface WatchFaceManagement : AutoCloseable {
    val state: StateFlow<WatchFaceManagementState>
    suspend fun refresh(): WatchFaceManagementState
    suspend fun previewAsset(digest: String): WatchFaceImage?
    suspend fun addFavorite(componentName: String, flavorId: String? = null): WatchFaceOperationResult
    suspend fun removeFavorite(favoriteId: Int, fallbackFavoriteId: Int? = null): WatchFaceOperationResult
    suspend fun reorderFavorites(favoriteIds: List<Int>): WatchFaceOperationResult
    suspend fun changeActiveFavorite(favoriteId: Int): WatchFaceOperationResult
    /** Opens the watch's package-removal confirmation through the runtime's device-settings client. */
    suspend fun uninstallPackage(packageName: String): WatchFaceOperationResult
    /** Mobvoi MPS selector has no observed same-path success acknowledgement. */
    suspend fun selectMobvoiFace(settings: WatchFacePhotoSettings): WatchFaceOperationResult
    suspend fun queryPhoto(): WatchFacePhoto
    suspend fun setPhoto(settings: WatchFacePhotoSettings?, image: ByteArray): WatchFaceOperationResult
    suspend fun startEditing(favoriteId: Int, width: Int, height: Int, abortCurrent: Boolean = false): WatchFaceEditingState
    /** Updates the session rendering through the observed screenshot request; no invented update opcode. */
    suspend fun updateEditing(options: WatchFacePreviewOptions): WatchFaceImage?
    suspend fun previewEditing(options: WatchFacePreviewOptions = WatchFacePreviewOptions()): WatchFaceImage?
    suspend fun previewComplication(complication: WatchFaceComplication, options: WatchFacePreviewOptions = WatchFacePreviewOptions(), calendarTimeMillis: Long? = null): WatchFaceImage?
    suspend fun endEditing(favorite: WatchFaceFavorite): WatchFaceOperationResult
    suspend fun abortEditing(): WatchFaceOperationResult
    /** MWF requires an explicitly selected independent AppKey and exact caller-supplied Channel path. */
    suspend fun transferMwfArchive(archive: WatchFaceArchive, channelPath: String, route: ApplicationRoute): WatchFaceOperationResult
    override fun close()
}
