package dev.ticbox.companion.ui.faces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.WatchFaceFavorite
import dev.ticbox.companion.api.WatchFaceImage
import dev.ticbox.companion.api.WatchFaceInstalled
import dev.ticbox.companion.api.WatchFaceManagementState
import dev.ticbox.companion.api.WatchFaceOutcome
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageGutter
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.navigation.PhotoFaceRoute
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class WatchFacesUiState(val faces: WatchFaceManagementState, val linkReady: Boolean) {
    /** In the watch's order; favorites it has not placed yet come last. */
    val favorites: List<WatchFaceFavorite>
        get() =
            faces.favorites.sortedBy { favorite ->
                val position = faces.favoriteOrder.indexOf(favorite.id)
                if (position < 0) Int.MAX_VALUE else position
            }

    val addable: List<WatchFaceInstalled>
        get() =
            faces.installed.filter { face ->
                faces.favorites.none { it.componentName == face.componentName }
            }

    fun name(favorite: WatchFaceFavorite): String? =
        faces.installed.firstOrNull { it.componentName == favorite.componentName }?.displayedName

    fun isCurrent(favorite: WatchFaceFavorite) =
        favorite.id != null && favorite.id == faces.current?.favoriteId
}

/** [photoFace] draws the photo face, whose own preview on the watch is a stock picture. */
class WatchFacesViewModel(
    dependencies: CompanionDependencies,
    private val photoFace: PhotoFacePictures? = null,
) : SessionViewModel(dependencies) {
    val state: StateFlow<WatchFacesUiState?> =
        sessionState(
            current = {
                WatchFacesUiState(
                    it.services.watchFaces.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.watchFaces.state, session.client.state) { faces, watch ->
                    WatchFacesUiState(faces, watch.connection.phase == ConnectionPhase.LINK_READY)
                }
            },
        )

    private var loader: Pair<WatchSession, FacePreviewLoader>? = null
    private val photoAsked = mutableSetOf<Long>()

    suspend fun preview(favorite: WatchFaceFavorite): ImageBitmap? {
        val current = session.value ?: return null
        photoFacePicture(current, favorite)?.let {
            return it
        }
        val active =
            loader?.takeIf { it.first == current }?.second
                ?: FacePreviewLoader(current.services.watchFaces).also { loader = current to it }
        return active.load(favorite)
    }

    /** The photo face with the watch's photo on it, asking the watch once per link. */
    private suspend fun photoFacePicture(
        session: WatchSession,
        favorite: WatchFaceFavorite,
    ): ImageBitmap? {
        val pictures = photoFace ?: return null
        val component = favorite.componentName ?: return null
        val watch = session.client.state.value
        val link = watch.connection.sessionId
        val known = session.services.watchFaces.state.value.photo
        return pictures.picture(component, known, watch.battery?.value) {
            if (photoAsked.add(link)) session.services.watchFaces.queryPhoto() else null
        }
    }

    fun refresh() =
        command(REFRESH) { session ->
            session.services.watchFaces.refresh()
            null
        }

    fun select(favorite: WatchFaceFavorite) {
        val id = favorite.id ?: return
        command(id) { session ->
            session.services.watchFaces.changeActiveFavorite(id).outcome.message()
        }
    }

    fun addFavorite(face: WatchFaceInstalled) =
        command(face.componentName) { session ->
            session.services.watchFaces.addFavorite(face.componentName).outcome.message()
        }

    /** Moves a favorite one place; [by] is negative towards the start. */
    fun move(favorite: WatchFaceFavorite, by: Int) {
        val ids = state.value?.favorites.orEmpty().mapNotNull { it.id }.toMutableList()
        val from = ids.indexOf(favorite.id)
        val to = from + by
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        command(REORDER) { session ->
            session.services.watchFaces.reorderFavorites(ids).outcome.message()
        }
    }

    /** The watch needs another face to fall back to when the active one goes. */
    fun remove(favorite: WatchFaceFavorite) {
        val id = favorite.id ?: return
        val current = state.value ?: return
        val fallback =
            if (current.isCurrent(favorite)) {
                current.favorites.firstOrNull { it.id != null && it.id != id }?.id
            } else {
                null
            }
        command(id) { session ->
            session.services.watchFaces.removeFavorite(id, fallback).outcome.message()
        }
    }

    /** Opens the watch's uninstall confirmation; the face goes once the watch reports it gone. */
    fun uninstall(componentName: String) {
        val packageName = componentName.substringBefore('/')
        command(UNINSTALL to packageName) { session ->
            session.services.watchFaces.uninstallPackage(packageName).outcome.message()
                ?: uiText(R.string.preferences_opened_on_watch)
        }
    }

    companion object {
        const val REFRESH = "faces_refresh"
        const val REORDER = "faces_reorder"
        const val UNINSTALL = "faces_uninstall"
    }
}

/** What a watch face command's result means for the user; null when its effect is visible. */
internal fun WatchFaceOutcome.message(): UiText? =
    when (this) {
        WatchFaceOutcome.ACKNOWLEDGED -> null
        WatchFaceOutcome.SUBMITTED,
        WatchFaceOutcome.TRANSFERRED_UNVERIFIED -> uiText(R.string.faces_sent)
        WatchFaceOutcome.TIMED_OUT -> uiText(R.string.faces_timed_out)
        WatchFaceOutcome.UNAVAILABLE -> uiText(R.string.faces_unavailable)
        WatchFaceOutcome.INTERRUPTED -> uiText(R.string.faces_interrupted)
        WatchFaceOutcome.FAILED,
        WatchFaceOutcome.UNKNOWN -> uiText(R.string.faces_failed)
    }

@Composable
fun WatchFacesScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val photoFace = rememberPhotoFacePictures()
    val viewModel = companionViewModel { WatchFacesViewModel(it, photoFace) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(
        stringResource(R.string.faces_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.faces_refresh),
                viewModel::refresh,
                enabled = state?.linkReady == true && WatchFacesViewModel.REFRESH !in pending,
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.WatchFaces,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val direction = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            modifier = Modifier.fillMaxSize().testTag("watch_faces"),
            contentPadding =
                PaddingValues(
                    start = padding.calculateStartPadding(direction) + PageGutter,
                    end = padding.calculateEndPadding(direction) + PageGutter,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val full = Modifier.fillMaxWidth()
            if (current.faces.favorites.isEmpty()) {
                fullWidth("empty") {
                    EmptyState(
                        AppIcons.WatchFaces,
                        stringResource(R.string.faces_empty_title),
                        stringResource(R.string.faces_empty_body),
                        full,
                    )
                }
            } else {
                fullWidth("favorites_title") {
                    SectionTitle(stringResource(R.string.faces_favorites))
                }
                val favorites = current.favorites
                items(favorites, key = { "favorite_${it.id}_${it.componentName}" }) { favorite ->
                    val busy = favorite.id?.let { it in pending } == true
                    val position = favorites.indexOf(favorite)
                    Box(Modifier.animateItem(), propagateMinConstraints = true) {
                        FavoriteCard(
                            favorite = favorite,
                            name = current.name(favorite) ?: stringResource(R.string.faces_unnamed),
                            current = current.isCurrent(favorite),
                            enabled = current.linkReady && favorite.id != null && !busy,
                            busy = busy,
                            photo = current.faces.photo?.image,
                            loadPreview = viewModel::preview,
                            onSelect = { viewModel.select(favorite) },
                            menu =
                                FavoriteMenu(
                                    canMoveEarlier =
                                        position > 0 && WatchFacesViewModel.REORDER !in pending,
                                    canMoveLater =
                                        position < favorites.lastIndex &&
                                            WatchFacesViewModel.REORDER !in pending,
                                    canRemove = favorites.size > 1,
                                    onMoveEarlier = { viewModel.move(favorite, -1) },
                                    onMoveLater = { viewModel.move(favorite, 1) },
                                    onRemove = { viewModel.remove(favorite) },
                                    onUninstall = {
                                        favorite.componentName?.let(viewModel::uninstall)
                                    },
                                ),
                        )
                    }
                }
            }
            if (current.addable.isNotEmpty()) {
                fullWidth("more") {
                    Column(
                        Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle(stringResource(R.string.faces_more))
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.animateContentSize()) {
                                current.addable.forEach { face ->
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                face.displayedName ?: face.componentName,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingContent = {
                                            AppIcon(AppIcons.WatchFaces, contentDescription = null)
                                        },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(
                                                    onClick = { viewModel.addFavorite(face) },
                                                    enabled =
                                                        current.linkReady &&
                                                            face.componentName !in pending,
                                                ) {
                                                    Text(stringResource(R.string.faces_add))
                                                }
                                                IconButton(
                                                    onClick = {
                                                        viewModel.uninstall(face.componentName)
                                                    },
                                                    enabled = current.linkReady,
                                                ) {
                                                    AppIcon(
                                                        AppIcons.Delete,
                                                        stringResource(R.string.faces_uninstall),
                                                    )
                                                }
                                            }
                                        },
                                        colors =
                                            ListItemDefaults.colors(
                                                containerColor = Color.Transparent
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            fullWidth("photo") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    NavigationRow(
                        stringResource(R.string.faces_photo_title),
                        onClick = { navigate(PhotoFaceRoute) },
                        icon = AppIcons.Palette,
                        summary = stringResource(R.string.faces_photo_summary),
                    )
                }
            }
            if (!current.faces.inventoryComplete) {
                fullWidth("catalog_note") {
                    FootnoteText(stringResource(R.string.faces_catalog_note))
                }
            }
        }
    }
}

/** A row across the grid, which moves along when the favorites around it change. */
private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.animateItem(), propagateMinConstraints = true) { content() }
    }
}

private class FavoriteMenu(
    val canMoveEarlier: Boolean,
    val canMoveLater: Boolean,
    val canRemove: Boolean,
    val onMoveEarlier: () -> Unit,
    val onMoveLater: () -> Unit,
    val onRemove: () -> Unit,
    val onUninstall: () -> Unit,
)

@Composable
private fun FavoriteMenuButton(menu: FavoriteMenu, enabled: Boolean, name: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.size(32.dp),
        ) {
            AppIcon(AppIcons.More, stringResource(R.string.faces_options, name))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            fun run(action: () -> Unit) {
                open = false
                action()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.faces_move_earlier)) },
                onClick = { run(menu.onMoveEarlier) },
                enabled = menu.canMoveEarlier,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.faces_move_later)) },
                onClick = { run(menu.onMoveLater) },
                enabled = menu.canMoveLater,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.faces_remove)) },
                onClick = { run(menu.onRemove) },
                enabled = menu.canRemove,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.faces_uninstall)) },
                onClick = { run(menu.onUninstall) },
            )
        }
    }
}

@Composable
private fun FavoriteCard(
    favorite: WatchFaceFavorite,
    name: String,
    current: Boolean,
    enabled: Boolean,
    busy: Boolean,
    /** The watch's photo, which the photo face's card shows; other cards keep their cached one. */
    photo: WatchFaceImage?,
    loadPreview: suspend (WatchFaceFavorite) -> ImageBitmap?,
    onSelect: () -> Unit,
    menu: FavoriteMenu,
) {
    val preview by
        produceState<ImageBitmap?>(null, favorite.previewKey(), photo) {
            value = loadPreview(favorite)
        }
    Card(
        modifier =
            Modifier.selectable(
                selected = current,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    animateColorAsState(
                            if (current) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            label = "face_container",
                        )
                        .value
            ),
        border =
            BorderStroke(
                2.dp,
                animateColorAsState(
                        if (current) MaterialTheme.colorScheme.primary else Color.Transparent,
                        label = "face_border",
                    )
                    .value,
            ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Crossfade(preview, label = "face_preview") { image ->
                    if (image != null) {
                        Image(
                            image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(
                                AppIcons.WatchFaces,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Crossfade(busy, label = "face_busy") { working ->
                    if (working) CircularProgressIndicator(Modifier.size(32.dp))
                }
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    FavoriteMenuButton(menu, enabled && !busy, name)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(current) {
                    AppIcon(
                        AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp).size(18.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            FadingText(
                stringResource(if (current) R.string.faces_current else R.string.faces_tap_to_use),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
