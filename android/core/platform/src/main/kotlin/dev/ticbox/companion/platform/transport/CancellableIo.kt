package dev.ticbox.companion.platform.transport

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine

/** Cancellation releases the resource which unblocks the operation, rather than just its thread. */
internal suspend fun <T> cancellableIo(
    dispatcher: CoroutineDispatcher,
    onCancel: () -> Unit,
    onDiscard: (T) -> Unit = {},
    block: () -> T,
): T = suspendCancellableCoroutine { continuation ->
    val completed = AtomicBoolean(false)
    fun complete(result: Result<T>) {
        if (!completed.compareAndSet(false, true)) {
            result.getOrNull()?.let { discarded -> runCatching { onDiscard(discarded) } }
            return
        }
        result.fold(
            onSuccess = { value ->
                continuation.resume(value) { _, discarded, _ -> runCatching { onDiscard(discarded) } }
            },
            onFailure = continuation::resumeWithException,
        )
    }
    continuation.invokeOnCancellation { runCatching(onCancel) }
    try {
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            if (!continuation.isActive) return@Runnable
            complete(runCatching(block))
        })
    } catch (failure: Throwable) {
        runCatching(onCancel)
        complete(Result.failure(failure))
    }
}
