package dev.ticbox.companion

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import dev.ticbox.companion.ui.PhoneApp
import dev.ticbox.companion.ui.PhoneAppsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed apps for the notification picker. Needs QUERY_ALL_PACKAGES to see every app. */
class PackageManagerPhoneApps(context: Context) : PhoneAppsSource {
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName
    private val icons = LruCache<String, ImageBitmap>(128)

    override suspend fun installedApps(): List<PhoneApp> =
        withContext(Dispatchers.IO) {
            packageManager
                .getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                .filter { it.packageName != ownPackage }
                .mapNotNull { info ->
                    val application = info.applicationInfo ?: return@mapNotNull null
                    PhoneApp(
                        packageName = info.packageName,
                        label = application.loadLabel(packageManager).toString(),
                        system =
                            application.flags and
                                (ApplicationInfo.FLAG_SYSTEM or
                                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        internet =
                            packageManager.checkPermission(
                                Manifest.permission.INTERNET,
                                info.packageName,
                            ) == PackageManager.PERMISSION_GRANTED,
                        installedAt = info.firstInstallTime,
                    )
                }
        }

    override suspend fun icon(packageName: String): ImageBitmap? {
        icons.get(packageName)?.let {
            return it
        }
        return withContext(Dispatchers.IO) {
            val drawable =
                try {
                    packageManager.getApplicationIcon(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@withContext null
                }
            val bitmap = createBitmap(ICON_SIZE, ICON_SIZE)
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap().also { icons.put(packageName, it) }
        }
    }

    private companion object {
        const val ICON_SIZE = 96
    }
}
