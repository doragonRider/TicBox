package dev.ticbox.companion.ui.faces

import dev.ticbox.companion.api.WatchFaceManagement
import dev.ticbox.companion.api.WatchFaceManagementState
import dev.ticbox.companion.api.WatchFaceOutcome
import dev.ticbox.companion.api.WatchFacePhotoSettings
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long a face just added may take to show up among the favorites. */
private const val FavoriteWaitMillis = 10_000L

/** `pkg/.Name` and `pkg/pkg.Name` name the same component. */
internal fun fullComponentName(component: String): String {
    val pkg = component.substringBefore('/')
    val cls = component.substringAfter('/', "")
    return if (cls.startsWith('.')) "$pkg/$pkg$cls" else component
}

/**
 * The photo face among the watch's faces, under the name the watch uses for it. The photo face
 * settings give its package and class, the class perhaps without its package; failing that, a face
 * of the settings' name in that package. Null when none of the faces the phone knows matches.
 */
internal fun photoFaceComponent(
    faces: WatchFaceManagementState,
    settings: WatchFacePhotoSettings,
): String? {
    val pkg = settings.packageName ?: return null
    val cls = settings.className ?: return null
    val known =
        (faces.installed.map { it.componentName } + faces.favorites.mapNotNull { it.componentName })
            .distinct()
    val wanted = fullComponentName("$pkg/$cls")
    known
        .firstOrNull { fullComponentName(it) == wanted }
        ?.let {
            return it
        }
    val samePackage = known.filter { it.substringBefore('/') == pkg }
    samePackage
        .singleOrNull { fullComponentName(it).endsWith(".${cls.removePrefix(".")}") }
        ?.let {
            return it
        }
    val name = settings.name ?: return null
    return faces.installed
        .singleOrNull { it.componentName in samePackage && it.displayedName == name }
        ?.componentName
}

/**
 * Makes the photo face the watch's face. The watch only switches between its favorites, and the
 * photo face often isn't one, so it is added first, as Watch faces → More faces would do. Null when
 * the watch took it; otherwise what to tell the user.
 */
internal suspend fun showPhotoFace(
    faces: WatchFaceManagement,
    settings: WatchFacePhotoSettings,
    store: PhotoFaceStore,
): UiText? {
    val state = faces.state.value
    val found = photoFaceComponent(state, settings)
    val name =
        found?.let { component ->
            state.installed.firstOrNull { it.componentName == component }?.displayedName
        } ?: settings.name
    fun missing() =
        if (name == null) uiText(R.string.faces_photo_face_missing_unnamed)
        else uiText(R.string.faces_photo_face_missing, name)
    val pkg = settings.packageName
    val cls = settings.className
    val component = found ?: if (pkg != null && cls != null) "$pkg/$cls" else return missing()
    fun favorite(faces: WatchFaceManagementState) =
        faces.favorites.firstOrNull { favorite ->
            favorite.id != null &&
                favorite.componentName?.let(::fullComponentName) == fullComponentName(component)
        }
    val chosen =
        favorite(state)
            ?: run {
                val added = faces.addFavorite(component)
                when (added.outcome) {
                    WatchFaceOutcome.ACKNOWLEDGED -> Unit
                    WatchFaceOutcome.FAILED -> return missing()
                    else -> return added.outcome.message()
                }
                withTimeoutOrNull(FavoriteWaitMillis) {
                    faces.state.map(::favorite).filterNotNull().first()
                } ?: return uiText(R.string.faces_photo_face_added)
            }
    val chosenName = chosen.componentName
    if (chosenName != null) {
        withContext(Dispatchers.IO) { runCatching { store.rememberComponent(chosenName) } }
    }
    val id = checkNotNull(chosen.id)
    if (faces.state.value.current?.favoriteId == id) return null
    return faces.changeActiveFavorite(id).outcome.message()
}
