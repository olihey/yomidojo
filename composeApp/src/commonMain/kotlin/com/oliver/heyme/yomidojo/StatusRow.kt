package com.oliver.heyme.yomidojo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Release year + AniList status (§9) as a colored dot + label, e.g. "2021 · ● Releasing" —
 * shared by the series header (sits under the cover image) and the library's detailed layout
 * (sits in the row, PLAN.md §7.1), so both wrap rather than truncate this the same way. */
@Composable
fun StatusRow(status: String?, startYear: Int?, modifier: Modifier = Modifier) {
    val presentation = statusPresentation(status)
    if (presentation == null && startYear == null) return
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        startYear?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = dotColor)
            if (presentation != null) {
                Text("   •   ", style = MaterialTheme.typography.bodySmall, color = dotColor)
            }
        }
        presentation?.let { (label, color) ->
            Text("●", color = color, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** AniList `MediaStatus` -> (display label, color). Null for an unmatched series or a status
 * value AniList hasn't documented (future-proofing rather than crashing on an unknown enum). */
@Composable
internal fun statusPresentation(status: String?): Pair<String, Color>? = when (status) {
    "FINISHED" -> stringResource(Res.string.status_finished) to Color(0xFF4CAF50)
    "RELEASING" -> stringResource(Res.string.status_releasing) to Color(0xFF2196F3)
    "NOT_YET_RELEASED" -> stringResource(Res.string.status_not_yet_released) to Color(0xFFFF9800)
    "CANCELLED" -> stringResource(Res.string.status_cancelled) to Color(0xFFF44336)
    "HIATUS" -> stringResource(Res.string.status_hiatus) to Color(0xFFFFC107)
    else -> null
}

/** [Series.format] -> (display label, pastel background color) for the format pill below
 * [StatusRow]. AniList's `MediaFormat` enum doesn't distinguish manhwa/manhua from manga
 * (`KitsuMetadataProvider.normalizeFormat` folds both into `MANGA`, PLAN.md §9.3), so only these
 * three values ever actually occur; null for an unmatched series or an unrecognized value. */
@Composable
internal fun formatPresentation(format: String?): Pair<String, Color>? = when (format) {
    "MANGA" -> stringResource(Res.string.format_manga) to Color(0xFF64B5F6)
    "NOVEL" -> stringResource(Res.string.format_novel) to Color(0xFFBA68C8)
    "ONE_SHOT" -> stringResource(Res.string.format_one_shot) to Color(0xFFFFB74D)
    else -> null
}

/** Series format pill (e.g. "Manga") — opaque colored background, black text (deliberately
 * distinct from [StatusRow]'s dot-and-label style so the two aren't confused at a glance). Sits
 * directly under [StatusRow] in the series header. Renders nothing for an unmatched series or an
 * unrecognized format value. */
@Composable
fun FormatPill(format: String?, modifier: Modifier = Modifier) {
    val (label, color) = formatPresentation(format) ?: return
    Text(
        label,
        modifier = modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.Black,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** [Series.metadataProvider] -> its display attribution ("Data provided by AniList") — null
 * for an unmatched series (never stamped) or an unrecognized value (future-proofing). */
@Composable
private fun providerAttribution(providerId: String?): String? = when (providerId) {
    "ANILIST" -> stringResource(Res.string.metadata_attribution_anilist)
    "KITSU" -> stringResource(Res.string.metadata_attribution_kitsu)
    else -> null
}

/** Small overlay label on the series header's banner (PLAN.md §9.3) — only shown once a
 * series is actually matched, so an unmatched series' blank banner stays clean. Tapping it opens
 * [siteUrl] (the matched item's own AniList/Kitsu page) in the system browser via
 * [LocalUriHandler] -- the simplest cross-platform way to leave the app for an occasional
 * "view the source" link, no Custom Tabs dependency needed. Only clickable when a URL is actually
 * known (older matches made before `siteUrl` existed can still be null), in which case the
 * external-link glyph is also omitted rather than hinting at a link that isn't there. */
@Composable
fun MetadataAttributionLabel(providerId: String?, siteUrl: String?, modifier: Modifier = Modifier) {
    val text = providerAttribution(providerId) ?: return
    val uriHandler = LocalUriHandler.current
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .let { if (siteUrl != null) it.clickable { uriHandler.openUri(siteUrl) } else it }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (siteUrl != null) {
            Spacer(Modifier.width(3.dp))
            ExternalLinkGlyph(Color.White, Modifier.size(10.dp))
        }
    }
}

/** A minimal "external link" glyph (diagonal arrow breaking out of a box's top-right corner) --
 * hand-drawn rather than pulling in material-icons-extended for one icon, same call as
 * [ReaderScreen]'s [ChevronGlyph]/[HamburgerGlyph]. */
@Composable
private fun ExternalLinkGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Box: bottom-left corner around to bottom-right, up to mid-right -- left open at the
        // top and top-right so the arrow reads as "breaking out" of it, not a closed square.
        val box = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.4f)
            lineTo(size.width * 0.15f, size.height * 0.85f)
            lineTo(size.width * 0.6f, size.height * 0.85f)
            lineTo(size.width * 0.6f, size.height * 0.55f)
        }
        drawPath(box, color = tint, style = stroke)
        val arrow = Path().apply {
            moveTo(size.width * 0.45f, size.height * 0.55f)
            lineTo(size.width * 0.9f, size.height * 0.1f)
            moveTo(size.width * 0.55f, size.height * 0.1f)
            lineTo(size.width * 0.9f, size.height * 0.1f)
            lineTo(size.width * 0.9f, size.height * 0.45f)
        }
        drawPath(arrow, color = tint, style = stroke)
    }
}
