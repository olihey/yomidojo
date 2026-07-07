package com.oliver.heyme.mangazuki

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
actual fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}

        val controller = WindowInsetsControllerCompat(window, view)
        // A positive action either way (hide or show), not just "hide, and restore on dispose" --
        // when one screen's ImmersiveMode call unmounts (nav-compose swapping destinations) and
        // the next screen's mounts in the same recomposition pass, disposal order between the two
        // isn't guaranteed. An unconditional "show bars" in onDispose could run *after* the new
        // screen's hide(), undoing it (seen navigating Library -> Series: bars reappeared and
        // stayed). Only ever asserting the current desired state, and never asserting anything on
        // teardown, makes the two independent of ordering -- whichever composable is left mounted
        // always wins.
        fun apply() {
            WindowCompat.setDecorFitsSystemWindows(window, !enabled)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        apply()

        // The system also clears a hidden state on its own whenever the window regains focus
        // (resuming from background, a dialog/keyboard dismissing, etc.) -- Android's own
        // immersive-mode guidance calls for re-asserting on every such focus regain, otherwise
        // the bars silently come back the next time the screen is revisited.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) apply() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
    }
}
