package dev.ticbox.companion.toolboxagent

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.ticbox.companion.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** A socket has no descriptor until it connects, and setting a timeout before then throws "socket not created". */
internal fun LocalSocket.connectToHealthModule(readTimeoutMillis: Int) {
    connect(LocalSocketAddress(HealthMonitoringProtocol.PRIVATE_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
    soTimeout = readTimeoutMillis
}

/** Metadata-only probe: authenticates kernel peer UID and the already verified OEM APK profile. */
internal object AgentModuleRuntime {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "module-status-timeout").apply { isDaemon = true } }
    @Suppress("DEPRECATION")
    fun read(context: Context): JsonObject {
        val packageInfo = context.packageManager.getPackageInfo(HealthMonitoringProtocol.PACKAGE, 0)
        val info = requireNotNull(packageInfo.applicationInfo)
        val sourceHash = FileInputStream(info.sourceDir).use { MagiskModuleProtocol.sha256(it, 256L * 1024 * 1024) }
        val socket = LocalSocket()
        val timer = deadlines.schedule({ runCatching(socket::close) }, 3, TimeUnit.SECONDS)
        try {
            socket.use {
                socket.connectToHealthModule(2000)
                require(socket.peerCredentials.uid == info.uid && info.uid > 0)
                val body = "{\"version\":1,\"op\":\"status\"}".toByteArray()
                DataOutputStream(socket.outputStream).apply { writeInt(body.size); write(body); flush() }
                val input = DataInputStream(socket.inputStream); val size = input.readInt(); require(size in 1..4096)
                val reply = Json.parseToJsonElement(ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)).jsonObject
                return validate(reply, packageInfo.longVersionCode, sourceHash)
            }
        } finally { timer.cancel(false) }
    }
    internal fun validate(reply: JsonObject, installedVersion: Long, installedSha256: String): JsonObject {
        val expected = HealthMonitoringProtocol.ANALYZED_APK_SHA256[installedVersion]
        require(expected != null && installedSha256 == expected)
        require(reply.number("version") == 1L && reply.text("packageName") == HealthMonitoringProtocol.PACKAGE)
        require(reply.number("versionCode") == installedVersion && reply.text("apkSha256") == expected)
        require(MagiskModuleProtocol.digest(reply.moduleText("buildId")))
        return buildJsonObject { put("buildId", reply.moduleText("buildId")) }
    }
}
