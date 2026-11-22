package dev.ticbox.companion.engine.data

/** Adapter to a session-bound RequestApi call on the Google GMS AppKey, DATA_SYNC_PROGRESS. */
fun interface DataSyncProgressTransport {
    suspend fun request(session: DataSyncSession, payload: ByteArray, timeoutMillis: Long): ByteArray
}
