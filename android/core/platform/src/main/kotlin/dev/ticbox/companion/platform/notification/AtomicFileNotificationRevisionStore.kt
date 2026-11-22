package dev.ticbox.companion.platform.notification

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.engine.notification.NotificationRevisionStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OEM-style global notification event counter, independent of post time and DataApi sequence. */
class AtomicFileNotificationRevisionStore(context: Context) : NotificationRevisionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "notification-global-revision.counter"))
    override suspend fun next(): Long = lock.withLock {
        withContext(Dispatchers.IO) {
            val revision = try {
                file.openRead().use { input ->
                    val data = DataInputStream(input)
                    val value = data.readLong()
                    if (data.read() != -1) throw IOException("Unrecognized notification revision record")
                    value
                }
            } catch (_: FileNotFoundException) { 0L }
            val stream = file.startWrite()
            try {
                DataOutputStream(stream).apply { writeLong(revision + 1); flush() }
                file.finishWrite(stream)
            } catch (problem: Throwable) { file.failWrite(stream); throw problem }
            revision
        }
    }
    companion object { private val lock = Mutex() }
}
