package dev.ticbox.companion.protocol

import kotlinx.serialization.json.*

/** Format readbacks are metadata, not proof of a hardware encoder's actual output bitrate. */
object ScreenEncoderDiagnostics {
    const val MAX_BYTES = 2_048
    const val PREFIX = "TICWATCH_ENCODER_V1 "

    fun parse(bytes: ByteArray): JsonObject? = runCatching {
        if (bytes.size > MAX_BYTES) return null
        val value = ToolboxProtocol.Packet(ToolboxProtocol.PROGRESS, bytes).objectValue()
        if (value.text("kind") != "screen_encoder" || value.number("version") != 1L) return null
        val stage = value.text("stage")?.takeIf { it in STAGES } ?: return null
        val sdk = value.number("sdk")?.takeIf { it in 21..100 } ?: return null
        val preferred = value.flag("preferCbr") ?: return null
        if (stage == "output_sample") return outputSample(value, sdk, preferred)
        if (stage == "input_sample") return inputSample(value, sdk, preferred)
        if (stage == "vendor_parameters") return vendorParameters(value, sdk, preferred)
        buildJsonObject {
            put("kind", "screen_encoder"); put("version", 1); put("stage", stage); put("sdk", sdk); put("preferCbr", preferred)
            value.text("encoder")?.takeIf { it.length in 1..128 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.-" } }
                ?.let { put("encoder", it) }
            capabilities(value["capabilities"] as? JsonObject)?.let { put("capabilities", it) }
            for (key in listOf("hardware", "cbrSupported")) value.flag(key)?.let { put(key, it) }
            for (prefix in listOf("configured", "input", "output")) for ((suffix, maximum) in listOf("Bitrate" to 1_000_000_000, "BitrateMode" to 3, "Fps" to 1_000, "Latency" to 1_000)) {
                val key = prefix + suffix
                val number = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if (number != null && number.isFinite() && number in 0.0..maximum.toDouble()) put(key, checkNotNull(value[key]))
            }
            for ((key, maximum) in listOf("configuredMaxBFrames" to 64, "inputMaxBFrames" to 64, "outputReorderDepth" to 64, "inputOperatingRate" to 1_000)) {
                val number = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if (number != null && number.isFinite() && number in 0.0..maximum.toDouble()) put(key, checkNotNull(value[key]))
            }
        }
    }.getOrNull()

    private fun capabilities(value: JsonObject?): JsonObject? {
        if (value == null) return null
        return buildJsonObject {
            val lower = value.number("bitrateMin")
            val upper = value.number("bitrateMax")
            if (lower != null && upper != null && lower >= 0 && upper >= lower && upper <= Int.MAX_VALUE) {
                put("bitrateMin", lower); put("bitrateMax", upper)
            }
            for (key in listOf("width", "height")) value.number(key)?.takeIf { it in 1..65535 }?.let { put(key, it) }
            for (key in listOf("sizeSupported", "sizeRateSupported")) value.flag(key)?.let { put(key, it) }
            val fpsLower = (value["fpsMin"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val fpsUpper = (value["fpsMax"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (fpsLower != null && fpsUpper != null && fpsLower.isFinite() && fpsUpper.isFinite() && fpsLower >= 0 && fpsUpper >= fpsLower && fpsUpper <= 1_000_000) {
                put("fpsMin", fpsLower); put("fpsMax", fpsUpper)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private val STAGES = setOf("configured", "output_format", "output_sample", "input_sample", "vendor_parameters")
    private val CODEC_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    private val VENDOR_PREFIX = Regex("([A-Za-z0-9_-]+\\.){0,8}")
    private val VENDOR_NAME = Regex("[A-Za-z0-9_.-]{1,120}:[ilfsb?]")

    /**
     * Names and value types of the encoder's vendor parameters, never their values, each without
     * the [prefix] they all share. [omitted] didn't fit in the record.
     */
    private fun vendorParameters(value: JsonObject, sdk: Long, preferred: Boolean): JsonObject? {
        val prefix = value.text("prefix")?.takeIf { it.length <= 120 && VENDOR_PREFIX.matches(it) } ?: return null
        val names = (value["names"] as? JsonArray)?.takeIf { it.size <= 200 } ?: return null
        val listed = names.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(VENDOR_NAME::matches) ?: return null }
        val count = value.number("count")?.takeIf { it in listed.size..1_000 } ?: return null
        val omitted = value.number("omitted")?.takeIf { it == count - listed.size } ?: return null
        return buildJsonObject {
            put("kind", "screen_encoder"); put("version", 1); put("stage", "vendor_parameters"); put("sdk", sdk); put("preferCbr", preferred)
            value.text("encoder")?.takeIf(CODEC_NAME::matches)?.let { put("encoder", it) }
            put("prefix", prefix); put("count", count); put("omitted", omitted)
            put("names", JsonArray(listed.map(::JsonPrimitive)))
        }
    }

    /**
     * From going into the encoder through OpenGL to coming out, with how many later inputs were
     * already in it. A copy is the server's repeat of the last picture; an unmatched output is one
     * the server never drew, such as the framework's own repeat.
     */
    private fun inputSample(value: JsonObject, sdk: Long, preferred: Boolean): JsonObject? {
        val counts = listOf("submittedPictures", "submittedCopies", "skippedInputs", "overflowedInputs", "outputPictures", "outputCopies", "unmatchedOutputs",
            "picturesNoneBehind", "picturesOneBehind", "picturesTwoBehind", "picturesMoreBehind")
        val times = listOf("maxPictureLatencyMicros", "meanPictureLatencyMicros", "maxCopyLatencyMicros")
        val n = (listOf("elapsedMillis", "maxInputsBehind") + counts + times).associateWith { value.number(it)?.takeIf { it >= 0 } ?: return null }
        if (n.getValue("elapsedMillis") !in 1..3_600_000 || n.getValue("maxInputsBehind") > 1_000) return null
        if (counts.any { n.getValue(it) > 10_000_000 } || times.any { n.getValue(it) > MAX_LATENCY_MICROS }) return null
        val pictures = n.getValue("outputPictures")
        if (listOf("picturesNoneBehind", "picturesOneBehind", "picturesTwoBehind", "picturesMoreBehind").sumOf { n.getValue(it) } != pictures) return null
        if (n.getValue("meanPictureLatencyMicros") > n.getValue("maxPictureLatencyMicros")) return null
        return buildJsonObject {
            put("kind", "screen_encoder"); put("version", 1); put("stage", "input_sample"); put("sdk", sdk); put("preferCbr", preferred)
            n.forEach { (key, number) -> put(key, number) }
        }
    }

    private const val MAX_LATENCY_MICROS = 60_000_000L

    private fun outputSample(value: JsonObject, sdk: Long, preferred: Boolean): JsonObject? {
        val keys = listOf("targetBitrate", "elapsedMillis", "frames", "encodedBytes", "keyFrames", "maxFrameBytes", "ptsIntervals", "ptsIntervalBytes", "ptsSpanMicros", "invalidPtsIntervals", "maxDequeueWaitMicros", "maxLocalWriteMicros", "maxOutputAgeMicros", "negativeOutputAges")
        val n = keys.associateWith { value.number(it)?.takeIf { it >= 0 } ?: return null }
        val frames = n.getValue("frames")
        if (frames !in 1..10_000_000 || n.getValue("targetBitrate") !in 64_000..1_000_000_000 || n.getValue("elapsedMillis") < 1) return null
        if (n.getValue("keyFrames") > frames || n.getValue("negativeOutputAges") > frames || n.getValue("ptsIntervals") > frames || n.getValue("invalidPtsIntervals") > frames - n.getValue("ptsIntervals")) return null
        if (n.getValue("maxFrameBytes") !in 1..ToolboxProtocol.MAX_PACKET || n.getValue("encodedBytes") < n.getValue("maxFrameBytes") || n.getValue("encodedBytes") > frames * ToolboxProtocol.MAX_PACKET || n.getValue("ptsIntervalBytes") > n.getValue("encodedBytes")) return null
        val media = if (value.containsKey("mediaBitrate")) {
            if (n.getValue("ptsIntervals") == 0L || n.getValue("ptsSpanMicros") == 0L || n.getValue("invalidPtsIntervals") != 0L) return null
            val observed = value.number("mediaBitrate")?.takeIf { it >= 0 } ?: return null
            val expected = (n.getValue("ptsIntervalBytes") * 8_000_000.0 / n.getValue("ptsSpanMicros")).toLong()
            if (kotlin.math.abs(observed.toDouble() - expected.toDouble()) > 1) return null
            observed
        } else null
        return buildJsonObject {
            put("kind", "screen_encoder"); put("version", 1); put("stage", "output_sample"); put("sdk", sdk); put("preferCbr", preferred)
            n.forEach { (key, number) -> put(key, number) }
            media?.let { put("mediaBitrate", it) }
        }
    }

}
