package dev.ticbox.companion.platform.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope

internal object AndroidProtocolDiagnostics {
    fun create(context: Context, scope: CoroutineScope): BufferedDiagnosticSink {
        val journal = RotatingDiagnosticLog(File(context.noBackupFilesDir, "protocol-diagnostics"))
        val failures = AtomicLong()
        return BufferedDiagnosticSink(
            scope = scope,
            append = journal::append,
            mirror = { Log.i("TicBoxProtocol", it.take(3_800)) },
            onFailure = {
                val count = failures.incrementAndGet()
                if (count == 1L || count % 64 == 0L) {
                    Log.w("TicBoxProtocol", "Diagnostic storage failure count=$count type=${it.javaClass.simpleName}")
                }
            },
        )
    }
}
