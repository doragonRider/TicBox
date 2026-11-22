package dev.ticbox.companion.api

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

/** Structured protocol metadata. Body bytes, credentials and permission backup contents stay out. */
class ProtocolDiagnostic(
    val component: String,
    val event: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    val channelId: ULong? = null,
    val localIsOpener: Boolean? = null,
    details: Map<String, String> = emptyMap(),
) {
    val details: Map<String, String> = details.toMap()
}

/** Implementations must enqueue promptly; logging must never wait for disk or a UI observer. */
fun interface DiagnosticSink {
    fun record(diagnostic: ProtocolDiagnostic)

    companion object { val NONE = DiagnosticSink { } }
}
