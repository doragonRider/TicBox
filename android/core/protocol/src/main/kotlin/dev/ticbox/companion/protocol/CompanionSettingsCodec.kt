package dev.ticbox.companion.protocol

/** SharedSetting/WatchSpecificSetting wrappers and the observed TOS audit record. */
object CompanionSettingsCodec {
    const val LOCALES_PATH = "/protogensettings/supported_locales/supported_locales"
    const val TIME_FORMAT_PATH = "/protogensettings/time/is_24_hour_format"
    const val PHONE_VERSION_PATH = "/protogensettings/phone_info/phone_version_info"
    const val TERMS_AUDIT_PATH = "/audit_recording/data/event/common/TERMS_OF_SERVICE"

    fun booleanValue(value: Boolean): ByteArray = number(2, if (value) 1 else 0)
    fun integerValue(value: Int): ByteArray = number(1, value.toLong())
    fun localesValue(languageTags: String): ByteArray = bytes(3, bytes(1, languageTags.toByteArray(Charsets.UTF_8)))
    fun setting(value: ByteArray, changedAtMillis: Long): ByteArray = bytes(1, value) + number(2, changedAtMillis)
    fun changedAt(setting: ByteArray): Long? = ProtoWire.decodeFields(setting)
        .lastOrNull { it.number == 2 && it.wireType == 0 }?.let { (it.value as ULong).toLong() }
    fun value(setting: ByteArray): ByteArray? = ProtoWire.decodeFields(setting)
        .lastOrNull { it.number == 1 && it.wireType == 2 }?.value as? ByteArray

    /** Audit event: context{device=PHONE,context=OOBE}, Timestamp, event=TERMS_OF_SERVICE,action=ADDED. */
    fun acceptedTermsAudit(atMillis: Long): ByteArray {
        val seconds = Math.floorDiv(atMillis, 1_000L)
        val nanos = Math.floorMod(atMillis, 1_000L) * 1_000_000
        val timestamp = number(1, seconds) + number(2, nanos)
        return bytes(1, number(1, 1) + number(2, 1)) + bytes(2, timestamp) + number(3, 1) + number(4, 1)
    }

    private fun number(field: Int, value: Long) = ProtoWire.encodedField(field, 0, ProtoWire.varint(value.toULong()))
    private fun bytes(field: Int, value: ByteArray) = ProtoWire.encodedField(field, 2, value)
}
