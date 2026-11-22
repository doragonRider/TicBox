package dev.ticbox.companion.healthbridge

import android.content.Context
import android.net.Uri
import dev.ticbox.companion.protocol.LocalHealthTable
import kotlinx.serialization.json.*

internal fun healthQueryUri(table: LocalHealthTable): Uri =
    if (table == LocalHealthTable.SPORT || table.privateFitness) SportProviderReader.URI else HealthProviderReader.URI

/** Metadata only; resolving a Provider does not establish that its query will be accepted. */
internal fun healthProviderMetadata(context: Context, uri: Uri): JsonObject = buildJsonObject {
    put("selfUid", android.os.Process.myUid()); put("selfPackage", context.packageName)
    try {
        val provider = context.packageManager.resolveContentProvider(uri.authority ?: "", 0)
        if (provider == null) put("providerResolved", false)
        else {
            put("providerPackage", provider.packageName); put("providerUid", provider.applicationInfo.uid)
            put("providerExported", provider.exported); put("providerEnabled", provider.enabled)
            put("providerReadPermission", provider.readPermission?.let(::JsonPrimitive) ?: JsonNull)
            put("providerWritePermission", provider.writePermission?.let(::JsonPrimitive) ?: JsonNull)
            provider.readPermission?.let { permission ->
                put("selfReadPermissionGranted", context.packageManager.checkPermission(permission, context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                try { put("providerReadPermissionProtectionLevel", context.packageManager.getPermissionInfo(permission, 0).protectionLevel) }
                catch (problem: Exception) { put("providerPermissionMetadataError", problem.javaClass.simpleName) }
            }
            try { put("providerVersion", context.packageManager.getPackageInfo(provider.packageName, 0).versionName) }
            catch (problem: Exception) { put("providerVersionError", problem.javaClass.simpleName) }
        }
    } catch (problem: Exception) { put("providerMetadataError", problem.javaClass.simpleName) }
}
