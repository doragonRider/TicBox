@file:Suppress("DEPRECATION")
package dev.ticbox.companion.platform.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.Rating
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.media.PhoneMediaSession
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Native metadata becomes the observed DataMap fields, with no media URI downloads. */
internal class AndroidMediaMapper(private val context: Context, private val diagnostics: DiagnosticSink) {
    class ImageBudget(var bytes: Int = 16 * 1024 * 1024, var slots: Int = 128)
    private val bootWallOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private data class PackageAssets(val label: String, val certificates: List<ByteArray>, val icon: ByteArray?)
    private val packages = object : LinkedHashMap<String, PackageAssets>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackageAssets>?): Boolean = size > 32
    }

    fun map(controller: MediaController, id: String, notifications: List<StatusBarNotification>?, images: ImageBudget): PhoneMediaSession {
        val packageName = controller.packageName
        val nativeMetadata = best("metadata") { controller.metadata }
        val playback = best("playback") { controller.playbackState }
        val extras = best("extras") { controller.extras }
        val info = best("volume") { controller.playbackInfo }
        val app = packageAssets(packageName)
        val description = best("description") { nativeMetadata?.description }
        val title = best("title") { nativeMetadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString()
            ?: nativeMetadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString() ?: description?.title?.toString() }
        val artist = best("artist") { nativeMetadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: nativeMetadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString() ?: description?.subtitle?.toString() }
        val playing = playback?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)
        val duration = best("duration") { nativeMetadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }?.getLong(MediaMetadata.METADATA_KEY_DURATION) }
        val position = playback?.position
        val volume = info?.takeIf { it.maxVolume > 0 }?.let { it.currentVolume.toFloat() / it.maxVolume }
        val absoluteVolume = info?.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE
        val fields = linkedMapOf<String, DataMapValue>(
            "mediacontrols.artist" to DataMapValue.Text(artist.orEmpty()), "mediacontrols.title" to DataMapValue.Text(title.orEmpty()),
            "mediacontrols.application_label" to DataMapValue.Text(app.label), "mediacontrols.package_name" to DataMapValue.Text(packageName),
            "mediacontrols.media_session_token" to DataMapValue.Text(controller.sessionToken.toString()),
            "mediacontrols.playing" to DataMapValue.Bool(playing), "mediacontrols.duration" to DataMapValue.LongNumber(duration ?: -1),
            "mediacontrols.position" to DataMapValue.LongNumber(position ?: -1),
            "mediacontrols.position_update_time" to DataMapValue.LongNumber(playback?.lastPositionUpdateTime?.takeIf { it != 0L }?.let { bootWallOffset + it } ?: 0),
            "mediacontrols.playback_speed" to DataMapValue.FloatNumber(playback?.playbackSpeed ?: 1f),
            "mediacontrols.transport_flags" to DataMapValue.IntNumber(playback?.actions?.toInt() ?: 0),
            "mediacontrols.volume" to DataMapValue.FloatNumber(volume ?: -1f), "supports_absolute_volume" to DataMapValue.Bool(absoluteVolume),
            "volume_increments_number" to DataMapValue.IntNumber(info?.maxVolume ?: -1),
            "mediacontrols.queue.current" to DataMapValue.LongNumber(playback?.activeQueueItemId ?: -1),
            "mediacontrols.notifications.data.source" to DataMapValue.Bool(false),
            "mediacontrols.supports_thumbs" to DataMapValue.Bool(controller.ratingType == Rating.RATING_THUMB_UP_DOWN),
        )
        fun string(name: String, value: String?) { value?.let { fields["mediacontrols.$name"] = DataMapValue.Text(it.take(32_768)) } }
        fun bool(name: String, value: Boolean) { fields["mediacontrols.$name"] = DataMapValue.Bool(value) }
        string("queue.id", description?.mediaId)
        string("media_uri", best("media_uri") { nativeMetadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: description?.mediaUri?.toString() })
        val rating = best("rating") { nativeMetadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) }
        fields["mediacontrols.user_rating"] = DataMapValue.IntNumber(when {
            rating == null || rating.ratingStyle != Rating.RATING_THUMB_UP_DOWN -> -2
            !rating.isRated -> 0
            rating.isThumbUp -> 1
            else -> -1
        })
        val wearActions = playback?.customActions.orEmpty().any { it.extras?.containsKey(SHOW_ON_WEAR) == true }
        bool("reserve_space_for_previous", best("reserve_previous") { extras?.getBoolean(if (wearActions) "android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_PREVIOUS" else "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS") } ?: false)
        bool("reserve_space_for_next", best("reserve_next") { extras?.getBoolean(if (wearActions) "android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_NEXT" else "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT") } ?: false)
        bool("controls_color_from_theme", best("theme_flag") { extras?.getBoolean("android.support.wearable.media.extra.BACKGROUND_COLOR_FROM_THEME") } ?: false)
        bool("watch_app_auto_launch_enabled", best("auto_launch") { extras?.getBoolean("watch_app_auto_launch_enabled") } ?: false)
        string("watch_app_auto_launch_extra", best("auto_launch_extra") { extras?.getString("watch_app_auto_launch_extra") } ?: "")
        val assets = linkedMapOf<String, MediaAsset>()
        fun image(key: String, bytes: ByteArray?) {
            if (bytes == null) return
            if (images.slots <= 0 || bytes.size > images.bytes) { metadata("image_snapshot_budget"); return }
            images.bytes -= bytes.size; images.slots--; assets[key] = MediaAsset(bytes)
        }
        image("mediacontrols.app_icon", app.icon)
        val artwork = best("artwork") { nativeMetadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: nativeMetadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: nativeMetadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) }
        image("mediacontrols.artwork", artwork?.let { bitmapBytes(it) })
        artwork?.let { bitmap -> best("dominant_color") { dominantColor(bitmap) }?.let { fields["mediacontrols.dominant_color"] = DataMapValue.IntNumber(it) } }
        best("theme_colors") {
            val packageContext = context.createPackageContext(packageName, 0)
            val theme = packageContext.resources.newTheme().apply { applyStyle(packageContext.applicationInfo.theme, true) }
            theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary, android.R.attr.colorPrimaryDark)).let { colors ->
                try { fields["mediacontrols.media_theme_primary_color"] = DataMapValue.IntNumber(themeColorAt(colors, 0))
                    fields["mediacontrols.media_theme_color_primary_dark"] = DataMapValue.IntNumber(themeColorAt(colors, 1)) }
                finally { colors.recycle() }
            }
        }
        val notification = notifications?.firstOrNull { sbn -> sbn.packageName == packageName &&
            best("notification_token") { sbn.notification.extras?.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java) } == controller.sessionToken }
        if (notifications != null) bool("media_notification_active", notification != null)
        notification?.notification?.smallIcon?.let { icon -> image("mediacontrols.media_notification_icon", best("notification_icon") { icon.loadDrawable(context)?.let(::drawableBytes) }) }
        val customActions = playback?.customActions.orEmpty().filter { !wearActions || it.extras?.getBoolean(SHOW_ON_WEAR) == true }.take(64).map { action ->
            val icon = best("custom_action_icon") { context.packageManager.getResourcesForApplication(packageName).getDrawable(action.icon, null).let(::drawableBytes) }
            val actionAssets = if (icon != null && images.slots > 0 && icon.size <= images.bytes) {
                images.bytes -= icon.size; images.slots--; mapOf("mediacontrols.custom_action_icon" to MediaAsset(icon))
            } else emptyMap()
            MediaContent(mapOf("mediacontrols.custom_action_action" to DataMapValue.Text(action.action),
                "mediacontrols.custom_action_name" to DataMapValue.Text(action.name.toString().take(32_768))), actionAssets)
        }
        val queue = best("queue") { controller.queue }.orEmpty()
        val index = queue.indexOfFirst { it.queueId == playback?.activeQueueItemId }.coerceAtLeast(0)
        val start = (index - 10).coerceIn(0, max(0, queue.size - 100))
        val lists = mapOf(
            "mediacontrols.custom_actions" to customActions,
            "mediacontrols.hash_bytes_list" to app.certificates.map { MediaContent(mapOf("mediacontrols.hash_bytes" to DataMapValue.Bytes(it))) },
            "mediacontrols.queue" to queue.drop(start).take(100).map { item ->
                val value = linkedMapOf<String, DataMapValue>("mediacontrols.queue.id" to DataMapValue.LongNumber(item.queueId))
                fun text(key: String, text: CharSequence?) { text?.let { value["mediacontrols.queue.$key"] = DataMapValue.Text(it.toString().take(32_768)) } }
                text("title", item.description.title); text("subtitle", item.description.subtitle); text("description", item.description.description)
                text("media.id", item.description.mediaId); MediaContent(value)
            },
        )
        val bundle = best("wear_media_bundle") { extras?.getBundle("wear_media_bundle") }
        val maps = bundle?.let { mapOf("mediacontrols.wear_media_bundle" to MediaContent(MediaBundles.toMap(it, diagnostics))) }.orEmpty()
        return PhoneMediaSession(MediaSessionSummary(id, packageName, app.label, title, artist, playing, position, duration,
            playback?.playbackSpeed ?: 1f, playback?.actions ?: 0, volume, absoluteVolume), MediaContent(fields, assets, maps, lists))
    }

    private fun packageAssets(packageName: String): PackageAssets = synchronized(packages) {
        packages[packageName] ?: run {
            val label = best("app_label") { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() } ?: packageName
            val certificates = best("certificates") {
                val signing = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (signing == null || signing.hasMultipleSigners()) emptyList() else signing.signingCertificateHistory.orEmpty()
                    .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            }.orEmpty()
            PackageAssets(label, certificates, best("app_icon") { drawableBytes(context.packageManager.getApplicationIcon(packageName)) }).also { packages[packageName] = it }
        }
    }
    // This array was constructed from explicit framework attr IDs above; its positions are
    // dynamic TypedArray indexes, rather than generated R.styleable ordinal constants.
    @SuppressLint("ResourceType")
    private fun themeColorAt(colors: TypedArray, index: Int): Int = colors.getColor(index, 0)

    private fun bitmapBytes(bitmap: Bitmap): ByteArray? = best("image_encode") {
        val scale = minOf(1f, 512f / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1) Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true) else bitmap
        try { ByteArrayOutputStream().use { stream ->
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)) return@best null
            stream.toByteArray().takeIf { it.size <= 4 * 1024 * 1024 }
        } } finally { if (scaled !== bitmap) scaled.recycle() }
    }
    private fun drawableBytes(drawable: Drawable): ByteArray? {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        return try { drawable.setBounds(0, 0, 128, 128); drawable.draw(Canvas(bitmap)); bitmapBytes(bitmap) } finally { bitmap.recycle() }
    }
    private fun dominantColor(bitmap: Bitmap): Int {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        repeat(8) { y -> repeat(8) { x -> val color = bitmap.getPixel((bitmap.width - 1) * x / 7, (bitmap.height - 1) * y / 7)
            if ((color ushr 24) != 0) { red += (color ushr 16) and 255; green += (color ushr 8) and 255; blue += color and 255; count++ } } }
        return if (count == 0) 0 else (255 shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
    }
    private fun <T> best(field: String, action: () -> T): T? = try { action() } catch (problem: Exception) {
        metadata("projection_partial", mapOf("field" to field, "exception" to problem.javaClass.simpleName)); null
    }
    private fun metadata(event: String, details: Map<String, String> = emptyMap()) { runCatching { diagnostics.record(ProtocolDiagnostic("media", event, details = details)) } }
    companion object { private const val SHOW_ON_WEAR = "android.support.wearable.media.extra.CUSTOM_ACTION_SHOW_ON_WEAR" }
}

/** Known Bundle/DataMap scalars only; unsupported Parcelables remain local and are counted. */
internal object MediaBundles {
    fun toMap(bundle: Bundle, diagnostics: DiagnosticSink): Map<String, DataMapValue> {
        var fields = 0; var unsupported = 0
        fun read(value: Any?, depth: Int): DataMapValue? {
            if (depth > 8 || fields++ > 1024) { unsupported++; return null }
            return when (value) {
                null -> DataMapValue.Null
                is String -> DataMapValue.Text(value.take(32_768))
                is CharSequence -> DataMapValue.Text(value.toString().take(32_768))
                is Boolean -> DataMapValue.Bool(value)
                is Int -> DataMapValue.IntNumber(value)
                is Long -> DataMapValue.LongNumber(value)
                is Float -> DataMapValue.FloatNumber(value)
                is Double -> DataMapValue.DoubleNumber(value)
                is Byte -> DataMapValue.ByteNumber(value)
                is ByteArray -> value.takeIf { it.size <= 256 * 1024 }?.let(DataMapValue::Bytes)
                is LongArray -> DataMapValue.Longs(value.take(1024))
                is FloatArray -> DataMapValue.Floats(value.take(1024))
                is Array<*> -> if (value.all { it is String }) DataMapValue.Strings(value.take(1024).filterIsInstance<String>()) else { unsupported++; null }
                is Bundle -> DataMapValue.MapValue(value.keySet().take(1024).mapNotNull { key ->
                    try { read(value.get(key), depth + 1)?.let { key to it } } catch (_: Exception) { unsupported++; null }
                }.toMap())
                is List<*> -> DataMapValue.ListValue(value.take(1024).mapNotNull { read(it, depth + 1) })
                else -> { unsupported++; null }
            }
        }
        val value = (read(bundle, 0) as? DataMapValue.MapValue)?.value.orEmpty()
        if (unsupported > 0) runCatching { diagnostics.record(ProtocolDiagnostic("media", "bundle_fields_unavailable", details = mapOf("count" to unsupported.toString()))) }
        return value
    }
}
