package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The same Java HTTP implementation runs on Android and against local JVM HTTP fixtures. */
open class UrlConnectionCloudHttpTransport(
    private val maxResponseBytes: Int = 16 * 1024 * 1024,
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
) : CloudHttpTransport, AutoCloseable {
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "mobvoi-cloud-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<CancellableContinuation<CloudHttpResponse>>()
    @Volatile private var closed = false
    init { require(maxResponseBytes > 0 && connectTimeoutMillis > 0 && readTimeoutMillis > 0) }
    override suspend fun execute(request: CloudHttpRequest): CloudHttpResponse = suspendCancellableCoroutine { continuation ->
        pending += continuation
        fun complete(result: Result<CloudHttpResponse>) { pending -= continuation; if (continuation.isActive) continuation.resumeWith(result) }
        if (closed) { complete(Result.failure(BackendClosedException())); return@suspendCancellableCoroutine }
        val connection = AtomicReference<HttpURLConnection?>()
        val future = AtomicReference<java.util.concurrent.Future<*>?>()
        continuation.invokeOnCancellation { pending -= continuation; connection.get()?.disconnect(); future.get()?.cancel(true) }
        val task = try { workers.submit {
            try {
                var target = URI(request.url)
                var response: CloudHttpResponse? = null
                for (redirect in 0..4) {
                    if (!continuation.isActive) break
                    val selected = target.toURL().openConnection() as HttpURLConnection
                    connection.set(selected); connections += selected
                    try {
                        if (closed || !continuation.isActive) throw CancellationException("Cloud HTTP transport closed or cancelled")
                        selected.instanceFollowRedirects = false
                        selected.connectTimeout = connectTimeoutMillis; selected.readTimeout = readTimeoutMillis
                        selected.requestMethod = request.method
                        request.headers.forEach(selected::setRequestProperty)
                        selected.setRequestProperty("Accept-Encoding", "gzip")
                        request.body?.let { body -> selected.doOutput = true; selected.setFixedLengthStreamingMode(body.size); selected.outputStream.use { it.write(body) } }
                        val status = selected.responseCode
                        val location = selected.getHeaderField("Location")
                        if (status in setOf(301, 302, 307, 308) && location != null && redirect < 4) {
                            val next = target.resolve(location)
                            // A credentialed request may follow only its original service origin.
                            if (next.scheme == target.scheme && next.host == target.host && next.port == target.port) { target = next; continue }
                        }
                        val input = if (status >= 400) selected.errorStream else selected.inputStream
                        val out = ByteArrayOutputStream()
                        if (input != null) {
                            val stream = if (selected.getHeaderField("Content-Encoding")?.equals("gzip", true) == true) GZIPInputStream(input) else input
                            stream.use {
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return@submit
                                    val count = it.read(buffer); if (count < 0) break
                                    if (count > maxResponseBytes - out.size()) throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.RESOURCE_LIMIT, "http_response"))
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        response = CloudHttpResponse(status, out.toByteArray(), selected.headerFields.filterKeys { it != null }.mapKeys { it.key!! })
                        break
                    } finally { connections -= selected; selected.disconnect(); connection.compareAndSet(selected, null) }
                }
                response?.let { complete(Result.success(it)) }
            } catch (error: Exception) { complete(Result.failure(error)) }
        } } catch (_: RejectedExecutionException) {
            complete(Result.failure(if (closed) BackendClosedException() else MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.RESOURCE_LIMIT, "http_queue"))))
            return@suspendCancellableCoroutine
        }
        future.set(task)
        if (closed || !continuation.isActive) { connection.get()?.disconnect(); task.cancel(true) }
    }
    override fun close() {
        closed = true
        pending.toList().forEach { it.cancel(CancellationException("Cloud HTTP transport closed")) }
        connections.toList().forEach(HttpURLConnection::disconnect)
        workers.shutdownNow()
    }
}
