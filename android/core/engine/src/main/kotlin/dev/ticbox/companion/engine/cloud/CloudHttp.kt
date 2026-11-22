package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

internal class CloudHttp(private val transport: CloudHttpTransport, private val config: MobvoiCloudConfig, private val diagnostics: DiagnosticSink) {
    suspend fun json(operation: String, request: CloudHttpRequest, account: Boolean = false, retryRead: Boolean = request.method == "GET"): JsonObject {
        val attempts = if (retryRead) config.readAttempts else 1
        var last: Exception? = null
        repeat(attempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val response = transport.execute(request)
                if (response.body.size > config.maxResponseBytes) throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.RESOURCE_LIMIT, operation))
                val charsetName = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()?.substringAfter("charset=", "UTF-8")?.substringBefore(';')?.trim()?.trim('"') ?: "UTF-8"
                val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
                val raw = runCatching { MobvoiCloudJson.parse(response.body.toString(charset).toByteArray(Charsets.UTF_8)) }.getOrNull()
                val code = raw?.number("err_code")?.toInt()
                if (response.status == 401 || code == 400100 || (account && code == 306))
                    throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.EXPIRED, operation, response.status, code))
                val transient = response.status == 408 || response.status == 429 || response.status >= 500
                if (response.status !in 200..299) throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.HTTP, operation, response.status, code, transient))
                val obj = raw ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, operation, response.status))
                // Account success is status="success"; health/sports use err_code=0. Unknown extra fields remain harmless.
                val failed = if (account) obj.text("status")?.let { it != "success" } ?: (code != null && code != 0)
                    else code != null && code != 0
                if (failed) throw MobvoiCloudException(MobvoiCloudIssue(if (account) MobvoiCloudIssueKind.AUTHENTICATION else MobvoiCloudIssueKind.HTTP, operation, response.status, code))
                log(operation, "http_complete", mapOf("status" to response.status.toString(), "attempt" to (attempt + 1).toString()))
                return obj
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                last = error
                val retry = error is IOException || (error is MobvoiCloudException && error.issue.retryable)
                if (!retry || attempt + 1 == attempts) throw if (error is MobvoiCloudException) error else MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.NETWORK, operation, retryable = retry))
                log(operation, "http_retry", mapOf("attempt" to (attempt + 1).toString(), "cause" to error.javaClass.simpleName))
                delay(config.retryDelayMillis * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("No HTTP attempt")
    }
    private fun log(operation: String, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("cloud", event, DiagnosticSeverity.INFO, details = details + ("operation" to operation))) }
    }
}
