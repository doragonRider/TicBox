package dev.ticbox.companion.api

/** A stored AutoSleep session with the original associated point records, without generated scores. */
data class HealthSleepRecord(
    val raw: HealthRawRecord,
    val sessionId: String?, val deviceId: String?, val vendorUserId: String?, val activityTypeCode: Long?,
    val startMillis: Long?, val endMillis: Long?, val vendorSyncCode: Long?, val deletedCode: Long?,
    val relatedPoints: List<HealthPointRecord>, val relatedPointsStatus: HealthRecordReadStatus,
    val issues: Set<HealthParseIssue>,
) {
    /** Current session reader tests bit 0; other bookkeeping bits remain in vendorSyncCode. */
    val vendorSynced: Boolean? get() = vendorSyncCode?.let { (it and 1L) != 0L }
    val deleted: Boolean? get() = when (deletedCode) { 0L -> false; 1L -> true; else -> null }
    val motionIntervals get() = relatedPoints.filter { it.type == HealthPointType.MOTION }
    override fun toString() = "HealthSleepRecord(contents=<redacted>)"
    companion object { const val AUTO_SLEEP_TYPE_CODE = 100L }
}

/** Filters and pagination select sessions; associated points are loaded by the backend. */
data class HealthSleepQuery(
    val source: HealthRecordSource.LocalDevice,
    val fromMillis: Long? = null, val toMillis: Long? = null,
    val includeDeleted: Boolean = false, val offset: Int = 0, val limit: Int = 20,
    val recordKey: String? = null,
) {
    override fun toString() = "HealthSleepQuery(filters=<redacted>, offset=$offset, limit=$limit)"
}
data class HealthSleepPage(val items: List<HealthSleepRecord>, val nextOffset: Int?,
    val status: HealthRecordReadStatus = HealthRecordReadStatus.AVAILABLE)
