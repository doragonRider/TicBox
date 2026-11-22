package dev.ticbox.companion.platform.identity

import android.content.Context
import android.content.pm.PackageManager
import dev.ticbox.companion.api.ApplicationRoute
import java.security.MessageDigest

/** Own app identity shared by the phone and watch APK; separate from the stock OEM wire route. */
internal object AndroidApplicationRoute {
    fun read(context: Context): ApplicationRoute {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = checkNotNull(info.signingInfo?.apkContentsSigners?.firstOrNull()) { "App signing certificate unavailable" }
        val digest = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        return ApplicationRoute(context.packageName, digest)
    }
}
