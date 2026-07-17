package app.vellum.reader.core.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Plumbing for the shelf-cover → reader container transform. MainActivity
 * hosts one SharedTransitionLayout around the NavHost and provides both
 * scopes here; screens opt covers and reader roots in with
 * [sharedCoverBounds]. Everything degrades to a no-op when either scope is
 * absent (previews, sheets).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shares bounds under a per-book key: the cover grows into the open book. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCoverBounds(bookUuid: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val navScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedCoverBounds.sharedBounds(
            rememberSharedContentState(key = "cover-$bookUuid"),
            animatedVisibilityScope = navScope,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop, Alignment.Center),
        )
    }
}
