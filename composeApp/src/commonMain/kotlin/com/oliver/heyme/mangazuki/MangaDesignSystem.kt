package com.oliver.heyme.mangazuki

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import manga_reader.composeapp.generated.resources.Res
import manga_reader.composeapp.generated.resources.anton_regular
import manga_reader.composeapp.generated.resources.archivo_black
import manga_reader.composeapp.generated.resources.archivo_bold
import manga_reader.composeapp.generated.resources.archivo_extrabold
import manga_reader.composeapp.generated.resources.archivo_medium
import manga_reader.composeapp.generated.resources.archivo_regular
import manga_reader.composeapp.generated.resources.archivo_semibold
import org.jetbrains.compose.resources.Font

/**
 * Shared palette + type for the "Manga Library"/"Manga Detail" Claude Design projects (imported
 * 2026-07-06) -- a dark, pulp/comic-poster look: near-black background, a red-orange accent,
 * bold condensed display type for titles. Used by [MangaShelfGrid] (the Library grid view) and
 * the Series detail screen; kept in one place so the two don't drift.
 */
object MangaColors {
    val Bg = Color(0xFF0E0D0C)
    val Panel = Color(0xFF1A1817)
    val PanelBorder = Color(0xFF2A2725)
    val ChipBg = Color(0xFF1C1916)
    val ChipBorder = Color(0xFF2C2823)
    val Divider = Color(0xFF201E1C)
    val Accent = Color(0xFFEF4023)
    val Text = Color.White
    val TextDim = Color(0xFFCFCAC4)
    val TextMuted = Color(0xFF6B6763)
    val TextMuted2 = Color(0xFF7D7873)
    val TextMuted3 = Color(0xFF9A948D)
}

@Composable
fun mangaArchivo(): FontFamily = FontFamily(
    Font(Res.font.archivo_regular, FontWeight.Normal),
    Font(Res.font.archivo_medium, FontWeight.Medium),
    Font(Res.font.archivo_semibold, FontWeight.SemiBold),
    Font(Res.font.archivo_bold, FontWeight.Bold),
    Font(Res.font.archivo_extrabold, FontWeight.ExtraBold),
    Font(Res.font.archivo_black, FontWeight.Black),
)

@Composable
fun mangaAnton(): FontFamily = FontFamily(Font(Res.font.anton_regular, FontWeight.Normal))

/** Deterministic two-tone gradient (from the design's own mocked palette) for a cover that's
 * still loading or has none -- keyed by series/chapter id so the same item always gets the same
 * colors across recompositions and screens. */
internal val MangaGradientPalette = listOf(
    Color(0xFF2E4B4A) to Color(0xFF16302F),
    Color(0xFF3A2E52) to Color(0xFF221A33),
    Color(0xFF2B2F3A) to Color(0xFF181B23),
    Color(0xFF4A3A22) to Color(0xFF2C2113),
    Color(0xFF6B2F2A) to Color(0xFF411916),
    Color(0xFF23303B) to Color(0xFF141D24),
    Color(0xFF33323A) to Color(0xFF1E1D23),
    Color(0xFF4A2733) to Color(0xFF2B141C),
)

fun mangaGradientFor(key: String): Pair<Color, Color> =
    MangaGradientPalette[(key.hashCode() and 0x7FFFFFFF) % MangaGradientPalette.size]
