package com.oliver.heyme.mangazuki

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A hand-drawn back-arrow glyph (the standard Material "arrow_back" outline), used instead of
 * a plain Unicode "←" in a `Text`. The bare-glyph approach (used elsewhere for badges, e.g.
 * StatusRow.kt) looked visibly off-center for this one: most fonts don't center an arrow glyph
 * within its own advance width, which `IconButton` then centers faithfully — so the button ends
 * up centered, but the ink inside it isn't. A vector path renders exactly where drawn, so this
 * sidesteps the issue without pulling in the material-icons-core/-extended dependency the rest
 * of the app deliberately avoids for single glyphs.
 */
private val BackArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BackArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            lineTo(7.83f, 11f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13f)
            lineTo(20f, 13f)
            close()
        }
    }.build()
}

/** Drop-in for the navigation-back `IconButton` content across the app's top bars. [tint]
 * defaults to the current content color so it matches the surrounding text/icons automatically. */
@Composable
fun BackIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(BackArrowIcon, contentDescription = "Back", modifier = modifier, tint = tint)
}
