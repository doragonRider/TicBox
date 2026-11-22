package dev.ticbox.companion.engine

import dev.ticbox.companion.protocol.DecodedStruct
import dev.ticbox.companion.protocol.MigrationProtocol
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Only the observed accountless transfer branch. An empty REMOVE_ACCOUNTS completes the stock
 * watch's matcher without invoking account deletion. A nonempty watch account inventory requires
 * a separate account matching implementation; it must never be converted into an empty result.
 */
internal class AccountlessMigration(
    private val queryIntervalMillis: Long,
    private val send: suspend (ByteArray) -> Unit,
    private val receive: suspend () -> List<DecodedStruct>,
    private val diagnostic: (String, Map<String, String>) -> Unit,
) {
    suspend fun awaitConfirmation() = coroutineScope {
        val accountsReceived = CompletableDeferred<Unit>()
        // Keep one reader alive. Cancelling a timed Bluetooth read would close its socket.
        // Query retries run separately, serialized by the session writer, until a response arrives.
        val query = launch {
            repeat(4) { attempt ->
                if (accountsReceived.isCompleted) return@launch
                send(MigrationProtocol.accountOperation(MigrationProtocol.GET_ACCOUNTS))
                diagnostic("accounts_query_written", mapOf("attempt" to (attempt + 1).toString()))
                if (withTimeoutOrNull(queryIntervalMillis) { accountsReceived.await(); true } == true) return@launch
            }
            throw IOException("Watch transfer account query received no response")
        }
        var emptyDecisionWritten = false
        while (true) {
            var confirmed = false
            // Every envelope in this read was already received before a decision caused by
            // this batch can be written. A coalesced operation 2 + operation 4 therefore cannot
            // confirm the decision that processing operation 2 is about to send.
            val decisionWrittenBeforeRead = emptyDecisionWritten
            for (envelope in receive()) {
                checkSessionControl(envelope)
                val control = MigrationProtocol.decode(envelope) ?: continue
                when (control.type) {
                    MigrationProtocol.ACCOUNT_MATCHING -> {
                        val matching = control.accountMatching ?: continue
                        when (matching.operation) {
                            MigrationProtocol.ACCOUNTS_RESPONSE -> {
                                accountsReceived.complete(Unit)
                                if (matching.unknownFields.any { it.number == 2 }) throw MigrationProtocolException(
                                    "Watch transfer account inventory could not be read; no account decision was sent",
                                )
                                diagnostic("accounts_observed", mapOf("accountCount" to matching.accounts.size.toString()))
                                if (matching.accounts.isNotEmpty()) throw MigrationProtocolException(
                                    "Watch transfer with Google accounts requires account matching; no accounts were removed",
                                )
                                if (!emptyDecisionWritten) {
                                    send(MigrationProtocol.accountOperation(MigrationProtocol.REMOVE_ACCOUNTS))
                                    emptyDecisionWritten = true
                                    diagnostic("empty_account_decision_written", mapOf("removalCount" to "0"))
                                }
                            }
                            MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM -> {
                                if (decisionWrittenBeforeRead) confirmed = true
                                else diagnostic("unsolicited_confirmation_ignored", emptyMap())
                            }
                            else -> diagnostic("account_operation_ignored", mapOf("operation" to matching.operation.toString()))
                        }
                    }
                    else -> diagnostic("control_ignored", mapOf("type" to control.type.toString()))
                }
            }
            if (confirmed) {
                query.join()
                diagnostic("empty_account_decision_confirmed", mapOf("nextStep" to "resume_current_stream"))
                break
            }
        }
    }
}

/** Control failures still apply after the account matcher has handed the stream to business code. */
internal fun checkSessionControl(envelope: DecodedStruct) {
    val control = MigrationProtocol.decode(envelope) ?: return
    when (control.type) {
        MigrationProtocol.TERMINATE_ASSOCIATION -> throw AssociationTerminatedException()
        MigrationProtocol.MIGRATION_FAILED, MigrationProtocol.MIGRATION_CANCELLED -> throw MigrationProtocolException(
            "Watch transfer was stopped by the watch (control ${control.type})",
        )
        MigrationProtocol.ACCOUNT_MATCHING -> {
            val operation = control.accountMatching?.operation
            when (operation) {
                MigrationProtocol.CANCEL, MigrationProtocol.REMOTE_ERROR -> throw MigrationProtocolException(
                    "Watch rejected transfer account negotiation (operation $operation)",
                )
            }
        }
    }
}

internal class AssociationTerminatedException : IOException("Watch terminated this peer association")
internal class MigrationProtocolException(message: String) : IOException(message)
