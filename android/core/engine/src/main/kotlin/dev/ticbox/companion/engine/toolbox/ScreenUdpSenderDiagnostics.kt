package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenCodecOptions
import dev.ticbox.companion.protocol.*

/** Optional sender telemetry is metadata only; malformed diagnostics cannot stop the picture. */
internal object ScreenUdpSenderDiagnostics {
    fun parse(packet: ToolboxProtocol.Packet): Pair<String, Map<String, String>>? = runCatching {
        if (packet.type != ToolboxProtocol.PROGRESS || packet.bytes.size > 2_048) return null
        val value = packet.objectValue()
        if (value.text("kind") != "screen_udp_sender" || value.number("version") != 1L) return null
        val sample = value.text("sample")
        val single = sample == "slow_frame" || sample == "frame_gap"
        val keys = when (sample) {
            "slow_frame", "frame_gap" -> listOf("sequence", "frameBytes", "datagrams", "wireBytes", "sendMicros", "pacingWaitMicros")
            "window" -> listOf("elapsedMillis", "frames", "encodedBytes", "keyFrames", "maxFrameBytes", "maxSendMicros", "maxPacingWaitMicros")
            else -> return null
        }
        val numbers = keys.associateWith { key -> value.number(key)?.takeIf { it >= 0 } ?: return null }
        val frameSize = numbers[if (single) "frameBytes" else "maxFrameBytes"] ?: return null
        if (frameSize !in 1..ToolboxProtocol.MAX_PACKET.toLong()) return null
        val send = numbers[if (single) "sendMicros" else "maxSendMicros"] ?: return null
        val wait = numbers[if (single) "pacingWaitMicros" else "maxPacingWaitMicros"] ?: return null
        if (wait > send) return null
        val details = numbers.mapValues { it.value.toString() }.toMutableMap()
        val fecFields = if (single) listOf("parityDatagrams", "fecEncodeMicros")
            else listOf("parityDatagrams", "maxFecEncodeMicros")
        for (field in fecFields) {
            if (value.containsKey(field)) details[field] = (value.number(field)?.takeIf { it >= 0 } ?: return null).toString()
        }
        val fecTime = value.number(if (single) "fecEncodeMicros" else "maxFecEncodeMicros")
        if (fecTime != null && fecTime > send) return null
        val workFields = if (single) listOf("encryptMicros", "socketSendMicros", "sendCalls", "encryptCpuMicros", "socketSendCpuMicros")
            else listOf("maxEncryptMicros", "maxSocketSendMicros", "sendCalls", "datagrams", "maxEncryptCpuMicros", "maxSocketSendCpuMicros")
        for (field in workFields) if (value.containsKey(field)) {
            val observed = value.number(field)?.takeIf { it >= 0 } ?: return null
            if (field.endsWith("Micros") && observed > send) return null
            details[field] = observed.toString()
        }
        if (value.containsKey("nativeBatch")) details["nativeBatch"] = (value.flag("nativeBatch") ?: return null).toString()
        if (value.containsKey("fecImplementation")) details["fecImplementation"] =
            value.text("fecImplementation")?.takeIf { it in setOf("kotlin", "scalar", "neon", "ssse3") } ?: return null
        if (value.containsKey("mediaSecurity")) details["mediaSecurity"] =
            value.text("mediaSecurity")?.takeIf { it in setOf("aes_gcm", "hmac_sha256_128") } ?: return null
        if (single) {
            if (numbers.getValue("datagrams") !in 1..16_384 || numbers.getValue("wireBytes") < frameSize) return null
            if (value.number("parityDatagrams")?.let { it > numbers.getValue("datagrams") } == true) return null
            details["keyFrame"] = (value.flag("keyFrame") ?: return null).toString()
        } else {
            if (numbers.getValue("elapsedMillis") == 0L || numbers.getValue("frames") == 0L ||
                numbers.getValue("keyFrames") > numbers.getValue("frames")) return null
        }
        val gapFields = if (single) listOf("ptsMicros", "ptsGapMicros", "producerGapMicros") else listOf("maxPtsGapMicros", "maxProducerGapMicros")
        for (field in gapFields) if (value.containsKey(field)) details[field] = (value.number(field)?.takeIf { it >= 0 } ?: return null).toString()
        (when (sample) { "slow_frame" -> "video_sender_slow_frame"; "frame_gap" -> "video_sender_frame_gap"; else -> "video_sender_sample" }) to details
    }.getOrNull()

    class Timing(val micros: Long?, val details: Map<String, String>)

    /** The target's per-second mean; validated components can also be logged while diagnostics are on. */
    fun parseTiming(packet: ToolboxProtocol.Packet): Timing? = runCatching {
        if (packet.type != ToolboxProtocol.PROGRESS || packet.bytes.size > 1_024) return null
        val value = packet.objectValue()
        if (value.text("kind") != "screen_udp_timing" || value.number("version") != 1L) return null
        val frames = value.number("frames")?.takeIf { it >= 0 } ?: return null
        val mean = if (frames == 0L) null else value.number("meanMicros")?.takeIf { it in 0..MAX_TIMING_MICROS } ?: return null
        val details = mutableMapOf("frames" to frames.toString(), "meanMicros" to (mean?.toString() ?: "unknown"))
        if (value.containsKey("rejectedFrames")) details["rejectedFrames"] = (value.number("rejectedFrames")?.takeIf { it >= 0 } ?: return null).toString()
        val rejections = listOf("overRangeFrames", "negativeAgeFrames", "missingTimestamps")
        if (rejections.any(value::containsKey)) {
            val total = value.number("rejectedFrames")?.takeIf { it >= 0 } ?: return null
            val counts = rejections.associateWith { value.number(it)?.takeIf { it in 0..total } ?: return null }
            var remaining = total
            for (count in counts.values) { if (count > remaining) return null; remaining -= count }
            if (remaining != 0L) return null
            counts.forEach { (key, count) -> details[key] = count.toString() }
            if (counts.getValue("overRangeFrames") > 0) {
                details["maxOverRangeAgeMicros"] = (value.number("maxOverRangeAgeMicros")?.takeIf { it > 1_000_000 } ?: return null).toString()
            }
        }
        val components = listOf("meanCaptureToRelayMicros", "maxCaptureToRelayMicros", "meanSendWorkMicros", "meanPacingMicros")
        if (components.any(value::containsKey)) {
            if (mean == null) return null
            val numbers = components.associateWith { value.number(it)?.takeIf { it in 0..MAX_TIMING_MICROS } ?: return null }
            // Integer division of the two component means may differ from their combined mean by one microsecond.
            val sum = numbers.getValue("meanCaptureToRelayMicros") + numbers.getValue("meanSendWorkMicros")
            if (mean - sum !in 0..1 || numbers.getValue("maxCaptureToRelayMicros") < numbers.getValue("meanCaptureToRelayMicros")) return null
            numbers.forEach { (key, number) -> details[key] = number.toString() }
        }
        Timing(mean, details)
    }.getOrNull()

    /** Separate bounded event so the global 24-detail diagnostic limit never truncates timings. */
    fun parseSocket(packet: ToolboxProtocol.Packet): Pair<String, Map<String, String>>? = runCatching {
        parse(packet) ?: return null
        val value = packet.objectValue()
        if (value.flag("nativeBatch") != true || !value.containsKey("gsoCalls")) return null
        val window = value.text("sample") == "window"
        val prefix = if (window) "maxSocket" else "socket"
        val bound = value.number(if (window) "maxSocketSendMicros" else "socketSendMicros") ?: return null
        val details = mutableMapOf("sample" to (value.text("sample") ?: return null))
        for (name in if (window) listOf("elapsedMillis", "frames") else listOf("sequence")) details[name] = (value.number(name) ?: return null).toString()
        for (suffix in listOf("CopyMicros", "CopyCpuMicros", "NativeMicros", "NativeCpuMicros")) {
            val name = prefix + suffix
            if (value.containsKey(name)) details[name] = (value.number(name)?.takeIf { it in 0..bound } ?: return null).toString()
        }
        val calls = value.number("gsoCalls")?.takeIf { it in 0..(value.number("sendCalls") ?: return null) } ?: return null
        val datagrams = value.number("gsoDatagrams")?.takeIf { it in 0..(value.number("datagrams") ?: return null) } ?: return null
        if (datagrams > 0 && calls == 0L) return null
        details["gsoCalls"] = calls.toString(); details["gsoDatagrams"] = datagrams.toString()
        details["gsoDisabled"] = (value.flag("gsoDisabled") ?: return null).toString()
        "video_sender_socket" to details
    }.getOrNull()

    /** Each change of the target encoder's bit rate as its queue grows or drains. */
    fun parseRate(packet: ToolboxProtocol.Packet): Pair<String, Map<String, String>>? = runCatching {
        if (packet.type != ToolboxProtocol.PROGRESS || packet.bytes.size > 512) return null
        val value = packet.objectValue()
        if (value.text("kind") != "screen_udp_rate" || value.number("version") != 1L) return null
        val reason = value.text("reason")?.takeIf { it == "decrease" || it == "increase" } ?: return null
        val target = value.number("targetBitrate")?.takeIf { it in 1..MAX_BIT_RATE } ?: return null
        val base = value.number("baseBitrate")?.takeIf { it in target..MAX_BIT_RATE } ?: return null
        val queue = value.number("queueMicros")?.takeIf { it in 0..MAX_TIMING_MICROS } ?: return null
        "video_bitrate_adapted" to mapOf("reason" to reason, "targetBitrate" to target.toString(), "baseBitrate" to base.toString(), "queueMicros" to queue.toString())
    }.getOrNull()

    /** What the session became within the target encoder's declared limits, once at its start. */
    fun parseLimit(packet: ToolboxProtocol.Packet): Pair<String, Map<String, String>>? = runCatching {
        if (packet.type != ToolboxProtocol.PROGRESS || packet.bytes.size > 512) return null
        val value = packet.objectValue()
        if (value.text("kind") != "screen_encoder_limit" || value.number("version") != 1L) return null
        val details = mutableMapOf("limitToEncoder" to (value.flag("limitToEncoder") ?: return null).toString())
        for (prefix in listOf("", "requested")) {
            fun key(name: String) = if (prefix.isEmpty()) name else prefix + name.replaceFirstChar(Char::uppercaseChar)
            details[key("codec")] = value.text(key("codec"))?.takeIf { it == "h264" || it == "h265" } ?: return null
            details[key("fps")] = (value.number(key("fps"))?.takeIf { it in 1..120 } ?: return null).toString()
            details[key("bitRate")] = (value.number(key("bitRate"))?.takeIf { it in 1..MAX_BIT_RATE } ?: return null).toString()
        }
        // The Agent's own options for this encoder, such as MediaTek's QP ceiling; never free text.
        value.text("encoderDefaults")?.let { text ->
            details["encoderDefaults"] = ToolboxScreenCodecOptions.normalize(text)?.takeIf { it.isNotEmpty() } ?: return null
        }
        "video_encoder_limit" to details
    }.getOrNull()

    private const val MAX_TIMING_MICROS = 10_000_000L
    private const val MAX_BIT_RATE = 1_000_000_000L
}

/**
 * A sanitised vendor parameter list as log details: the names go into `names1`, `names2` and so on,
 * comma separated, because one detail holds at most 256 characters.
 */
internal fun screenVendorParameterDetails(encoder: kotlinx.serialization.json.JsonObject): Map<String, String> {
    val details = linkedMapOf<String, String>()
    encoder.text("encoder")?.let { details["encoder"] = it }
    details["prefix"] = encoder.text("prefix").orEmpty()
    details["count"] = encoder.number("count")?.toString() ?: "unknown"
    details["omitted"] = encoder.number("omitted")?.toString() ?: "unknown"
    val names = (encoder["names"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as kotlinx.serialization.json.JsonPrimitive).content }
    var part = StringBuilder()
    var index = 0
    fun flush() { if (part.isNotEmpty()) { details["names${++index}"] = part.toString(); part = StringBuilder() } }
    for (name in names) {
        if (part.isNotEmpty() && part.length + 1 + name.length > 256) flush()
        if (part.isNotEmpty()) part.append(',')
        part.append(name)
    }
    flush()
    return details
}
