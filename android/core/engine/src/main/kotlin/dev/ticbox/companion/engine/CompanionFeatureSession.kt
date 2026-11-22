package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*

/** Session-bound transport supplied by the runtime; a feature never creates its own socket. */
class CompanionFeatureSession(
    val sessionId: Long,
    val scope: DataScope,
    val companionRoute: ApplicationRoute,
    private val send: suspend (ApplicationRoute, String, ByteArray) -> Unit,
    private val request: suspend (ApplicationRoute, String, ByteArray, Long) -> ByteArray,
    private val open: suspend (ApplicationRoute, String) -> WearChannel,
) {
    val peer: PeerAddress get() = scope.peer
    suspend fun sendMessage(path: String, bytes: ByteArray, route: ApplicationRoute = companionRoute) = send(route, path, bytes)
    suspend fun request(path: String, bytes: ByteArray, timeoutMillis: Long = 30_000,
        route: ApplicationRoute = companionRoute): ByteArray = request(route, path, bytes, timeoutMillis)
    suspend fun openChannel(path: String, route: ApplicationRoute = companionRoute): WearChannel = open(route, path)
}
