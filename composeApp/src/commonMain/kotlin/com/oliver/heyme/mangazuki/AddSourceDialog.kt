package com.oliver.heyme.mangazuki

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// Design-specific tones from "Add Source Dialog" (Claude Design, imported 2026-07-09) that don't
// map onto an existing MangaColors token -- the card sits a shade above MangaColors.Bg, and the
// rows/icon chips use their own warm-neutral borders.
private val CardBg = Color(0xFF141210)
private val RowBorder = Color(0xFF241F1C)
private val IconChipBg = Color(0xFF221F1C)
private val IconChipBorder = Color(0xFF302A26)
private val DescColor = Color(0xFF8A857F)
private val CancelText = Color(0xFFA29C95)

/**
 * "Add Source Dialog" (Claude Design, imported 2026-07-09): a custom dark modal replacing the old
 * Material `AlertDialog` chooser -- an accent eyebrow + Anton title, a close button, three tappable
 * source rows (icon chip + name + one-line description + chevron), and a Cancel button. Picking a
 * row hands off to the same callbacks as before (SAF picker, SMB dialog, OneDrive dialog).
 */
@Composable
internal fun AddSourceChooserDialog(
    onDismiss: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onPickSmbShare: () -> Unit,
    onPickOneDrive: () -> Unit,
    onPickGoogleDrive: (() -> Unit)? = null,
) {
    val archivo = mangaArchivo()
    val anton = mangaAnton()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .border(1.dp, MangaColors.PanelBorder, RoundedCornerShape(20.dp))
                    .padding(start = 24.dp, top = 26.dp, end = 24.dp, bottom = 22.dp),
            ) {
                // Header: eyebrow + title on the left, close button on the right.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.add_source_eyebrow).uppercase(),
                            color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp, letterSpacing = 2.8.sp,
                        )
                        Text(
                            stringResource(Res.string.add_source_dialog_title).uppercase(),
                            color = MangaColors.Text, fontFamily = anton, fontSize = 26.sp, lineHeight = 26.sp,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(MangaColors.Panel)
                            .border(1.dp, MangaColors.PanelBorder, CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.action_close), tint = CancelText, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    stringResource(Res.string.add_source_subtitle),
                    color = DescColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceRow(FolderIcon, stringResource(Res.string.add_source_local_folder), stringResource(Res.string.add_source_local_desc), archivo, anton, onPickLocalFolder)
                    SourceRow(ServerIcon, stringResource(Res.string.add_source_smb_share), stringResource(Res.string.add_source_smb_desc), archivo, anton, onPickSmbShare)
                    SourceRow(OneDriveIcon, stringResource(Res.string.add_source_onedrive), stringResource(Res.string.add_source_onedrive_desc), archivo, anton, onPickOneDrive)
                    if (onPickGoogleDrive != null) {
                        SourceRow(GoogleDriveIcon, stringResource(Res.string.add_source_googledrive), stringResource(Res.string.add_source_googledrive_desc), archivo, anton, onPickGoogleDrive)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(MangaColors.Panel)
                            .border(1.dp, MangaColors.PanelBorder, RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        Text(
                            stringResource(Res.string.action_cancel).uppercase(),
                            color = CancelText, fontFamily = archivo, fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp, letterSpacing = 0.7.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    name: String,
    desc: String,
    archivo: FontFamily,
    anton: FontFamily,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MangaColors.Panel)
            .border(1.dp, RowBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(IconChipBg)
                .border(1.dp, IconChipBorder, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // tint = Unspecified so each icon keeps its own baked-in colors -- accent strokes for
            // folder/server, the multi-blue brand mark for OneDrive.
            Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = MangaColors.Text, fontFamily = anton, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                desc, color = DescColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MangaColors.TextMuted, modifier = Modifier.size(18.dp))
    }
}

// --- Icons: the exact SVG paths from the design, rendered without pulling in material-icons
// (the app avoids that dependency for single glyphs -- see BackIcon.kt). ---

private fun strokePath(d: String, color: Color, viewport: Float = 24f): ImageVector =
    ImageVector.Builder(name = "src", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = viewport, viewportHeight = viewport)
        .addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(color), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()

private val FolderIcon: ImageVector by lazy {
    strokePath("M3 7a2 2 0 0 1 2 -2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V7z", MangaColors.Accent)
}

/** Two rounded bars + two indicator dots -- the design's `<rect>`s and `<circle>`s
 * expressed as SVG path data (rects → rounded-rect paths, dots → tiny filled circles). */
private val ServerIcon: ImageVector by lazy {
    val bar1 = "M4.5 4 h15 a1.5 1.5 0 0 1 1.5 1.5 v3 a1.5 1.5 0 0 1 -1.5 1.5 h-15 a1.5 1.5 0 0 1 -1.5 -1.5 v-3 a1.5 1.5 0 0 1 1.5 -1.5 z"
    val bar2 = "M4.5 14 h15 a1.5 1.5 0 0 1 1.5 1.5 v3 a1.5 1.5 0 0 1 -1.5 1.5 h-15 a1.5 1.5 0 0 1 -1.5 -1.5 v-3 a1.5 1.5 0 0 1 1.5 -1.5 z"
    val dot1 = "M6 7 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 z"
    val dot2 = "M6 17 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 z"
    ImageVector.Builder(name = "server", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathParser().parsePathString(bar1).toNodes(), stroke = SolidColor(MangaColors.Accent), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .addPath(PathParser().parsePathString(bar2).toNodes(), stroke = SolidColor(MangaColors.Accent), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .addPath(PathParser().parsePathString(dot1).toNodes(), fill = SolidColor(MangaColors.Accent))
        .addPath(PathParser().parsePathString(dot2).toNodes(), fill = SolidColor(MangaColors.Accent))
        .build()
}

/** The Microsoft OneDrive brand mark -- the four-shade blue cloud, verbatim fill paths from the
 * design (viewBox 32), rendered untinted so the brand colors survive. */
private val OneDriveIcon: ImageVector by lazy {
    ImageVector.Builder(name = "onedrive", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 32f, viewportHeight = 32f)
        .addPath(PathParser().parsePathString("M11.5 26h13.8a4.7 4.7 0 0 0 1 -9.3 6.9 6.9 0 0 0 -12.9 -3.2 5.6 5.6 0 0 0 -6.2 5.4A4.9 4.9 0 0 0 8.5 26").toNodes(), fill = SolidColor(Color(0xFF0364B8)))
        .addPath(PathParser().parsePathString("M11.5 26h6.9V13.6a6.9 6.9 0 0 0 -4.4 2.4 5.6 5.6 0 0 0 -6.2 5.4c0 .2 0 .4 0 .6a4.9 4.9 0 0 0 3.7 3.9z").toNodes(), fill = SolidColor(Color(0xFF0F78D4)))
        .addPath(PathParser().parsePathString("M25.3 16.7a6.9 6.9 0 0 0 -6.9 -3.1V26h6.9a4.7 4.7 0 0 0 0 -9.3z").toNodes(), fill = SolidColor(Color(0xFF1490DF)))
        .addPath(PathParser().parsePathString("M6.2 21.8a4.9 4.9 0 0 0 2.3 4.1 4.8 4.8 0 0 0 2.9 .9V16.1a5.6 5.6 0 0 0 -5.2 5.7z").toNodes(), fill = SolidColor(Color(0xFF28AFEA)))
        .build()
}

/** The Google Drive brand mark -- the three-shade colored cloud, verbatim fill paths from the
 * design (viewBox 24), rendered untinted so the brand colors survive. */
private val GoogleDriveIcon: ImageVector by lazy {
    // Simplified representation of Google Drive logo - a blue cloud with different shades
    ImageVector.Builder(name = "googledrive", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathParser().parsePathString("M12 3C9.5 3 7.5 5 7.5 7.5C7.5 10 9.5 12 12 12S16.5 10 16.5 7.5C16.5 5 14.5 3 12 3M8 13L11 16L14 13").toNodes(), fill = SolidColor(Color(0xFF4285F4)))
        .addPath(PathParser().parsePathString("M9 17C10 17 11 17.5 11 18C11 18.5 10 19 9 19S7 18.5 7 18C7 17.5 8 17 9 17Z").toNodes(), fill = SolidColor(Color(0xFF34A853)))
        .addPath(PathParser().parsePathString("M15 17C16 17 17 17.5 17 18C17 18.5 16 19 15 19S13 18.5 13 18C13 17.5 14 17 15 17Z").toNodes(), fill = SolidColor(Color(0xFFFBBC05)))
        .build()
}
