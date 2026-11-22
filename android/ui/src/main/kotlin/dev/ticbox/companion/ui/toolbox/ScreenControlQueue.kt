package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenInput
import dev.ticbox.companion.api.ToolboxScreenSession
import kotlinx.coroutines.channels.Channel

/** One writer; only unsent moves may be replaced. Down/up, keys and paste are barriers. */
internal class ScreenControlQueue(
    private val collectDiagnostics: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private sealed interface Entry {
        class Action(
            val touchAction: Int? = null,
            val queuedAt: Long = 0,
            val run: suspend (ToolboxScreenSession) -> Unit,
        ) : Entry

        class Moves(val latest: LinkedHashMap<Long, ToolboxScreenInput.Touch>) : Entry
    }

    private val lock = Any()
    private val pending = ArrayDeque<Entry>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var failure: ToolboxException? = null

    fun touch(event: ToolboxScreenInput.Touch) =
        synchronized(lock) {
            if (closed || failure != null) return@synchronized
            if (event.action == TOUCH_MOVE) {
                val moves = pending.lastOrNull() as? Entry.Moves
                if (moves != null && (event.pointerId in moves.latest || moves.latest.size < 10)) {
                    moves.latest[event.pointerId] = event
                    return@synchronized
                }
                enqueue(Entry.Moves(linkedMapOf(event.pointerId to event)))
            } else
                enqueue(
                    Entry.Action(
                        touchAction =
                            event.action.takeIf {
                                collectDiagnostics && (it == TOUCH_DOWN || it == TOUCH_UP)
                            },
                        queuedAt = if (collectDiagnostics) nanoTime() else 0,
                    ) {
                        it.input(event)
                    }
                )
        }

    fun action(run: suspend (ToolboxScreenSession) -> Unit) =
        synchronized(lock) {
            if (!closed && failure == null) enqueue(Entry.Action(run = run))
        }

    private fun enqueue(entry: Entry) {
        // A broken transport must not accumulate an unbounded history of taps or text.
        // Fail the session instead of silently dropping a down/up event.
        if (pending.size == 128) {
            failure = ToolboxException("screen_control_backlog")
            pending.clear()
        } else pending.addLast(entry)
        ready.trySend(Unit)
    }

    suspend fun sendNext(session: ToolboxScreenSession): Boolean {
        while (true) {
            val entry =
                synchronized(lock) {
                    failure?.let { throw it }
                    if (closed) return false
                    pending.removeFirstOrNull()
                }
            // Removing under the lock transfers ownership: producers can only change the
            // next queued batch while this one waits on Bluetooth or an ADB acknowledgement.
            when (entry) {
                is Entry.Action -> {
                    entry.touchAction?.let { action ->
                        val waitedMillis =
                            ((nanoTime() - entry.queuedAt).coerceAtLeast(0)) / 1_000_000
                        runCatching { session.reportControlQueued(action, waitedMillis) }
                    }
                    entry.run(session)
                    return true
                }
                is Entry.Moves -> {
                    for (event in entry.latest.values) session.input(event)
                    return true
                }
                null -> Unit
            }
            if (ready.receiveCatching().isClosed) return false
        }
    }

    override fun close() =
        synchronized(lock) {
            closed = true
            pending.clear()
            ready.close()
            Unit
        }
}
