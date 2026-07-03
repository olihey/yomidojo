package com.oliver.heyme.mangazuki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
private fun statusPresentation(status: String?): Pair<String, Color>? = when (status) {
    "FINISHED" -> "Finished" to Color(0xFF4CAF50)
    "RELEASING" -> "Releasing" to Color(0xFF2196F3)
    "NOT_YET_RELEASED" -> "Not yet released" to Color(0xFFFF9800)
    "CANCELLED" -> "Cancelled" to Color(0xFFF44336)
    "HIATUS" -> "Hiatus" to Color(0xFFFFC107)
    else -> null
}

/** [Series.format] -> (display label, pastel background color) for the format pill below
 * [StatusRow]. AniList's `MediaFormat` enum doesn't distinguish manhwa/manhua from manga
 * (`KitsuMetadataProvider.normalizeFormat` folds both into `MANGA`, PLAN.md §9.3), so only these
 * three values ever actually occur; null for an unmatched series or an unrecognized value. */
private fun formatPresentation(format: String?): Pair<String, Color>? = when (format) {
    "MANGA" -> "Manga" to Color(0xFF64B5F6)
    "NOVEL" -> "Novel" to Color(0xFFBA68C8)
    "ONE_SHOT" -> "One-shot" to Color(0xFFFFB74D)
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
private fun providerAttribution(providerId: String?): String? = when (providerId) {
    "ANILIST" -> "Data provided by AniList"
    "KITSU" -> "Data provided by Kitsu"
    else -> null
}

/** Small overlay label on the series header's banner (PLAN.md §9.3) — only shown once a
 * series is actually matched, so an unmatched series' blank banner stays clean. */
@Composable
fun MetadataAttributionLabel(providerId: String?, modifier: Modifier = Modifier) {
    val text = providerAttribution(providerId) ?: return
    Text(
        text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}
