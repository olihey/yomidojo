package com.mangaread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangaread.core.domain.ReadingMode

fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.SYSTEM -> "Follow system setting"
}

fun TitleLanguage.label(): String = when (this) {
    TitleLanguage.FILE -> "From file/folder name"
    TitleLanguage.ANILIST_ROMAJI -> "AniList - Romaji"
    TitleLanguage.ANILIST_ENGLISH -> "AniList - English"
    TitleLanguage.ANILIST_NATIVE -> "AniList - Native"
}

fun MetadataProviderChoice.label(): String = when (this) {
    MetadataProviderChoice.ANILIST -> "AniList"
    MetadataProviderChoice.KITSU -> "Kitsu"
}

/** Shared with the reader's chrome quick-switcher, so both use identical wording. */
fun ReadingMode.label(): String = when (this) {
    ReadingMode.PAGED_LTR -> "Paged, left to right"
    ReadingMode.PAGED_RTL -> "Paged, right to left (manga default)"
    ReadingMode.VERTICAL_PAGED -> "Vertical, one page at a time"
    ReadingMode.VERTICAL_CONTINUOUS -> "Vertical, continuous scroll (webtoon)"
}

/** Compact form for the chrome's quick-switcher button, where space is tight. */
fun ReadingMode.shortLabel(): String = when (this) {
    ReadingMode.PAGED_LTR -> "Paged →"
    ReadingMode.PAGED_RTL -> "Paged ←"
    ReadingMode.VERTICAL_PAGED -> "Vertical ↕"
    ReadingMode.VERTICAL_CONTINUOUS -> "Webtoon ↕"
}

/** Reader settings (PLAN.md §8.1): default reading mode, tap-zone layout, volume-key paging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: ReaderPreferences,
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    onResetLibrary: () -> Unit,
) {
    var readingMode by remember { mutableStateOf(prefs.defaultReadingMode) }
    var invertTapZones by remember { mutableStateOf(prefs.invertTapZones) }
    var volumeKeyPaging by remember { mutableStateOf(prefs.volumeKeyPaging) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val themeMode by appPreferences.themeMode.collectAsState()
    val titleLanguage by appPreferences.titleLanguage.collectAsState()
    val metadataProvider by appPreferences.metadataProvider.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { BackIcon() }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            Text(
                "Theme",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { appPreferences.setThemeMode(mode) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = mode == themeMode, onClick = { appPreferences.setThemeMode(mode) })
                    Text(mode.label())
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                "Series title",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
            Text(
                "Which title to show for a matched series - falls back to the file/folder name " +
                    "if AniList doesn't have that language for it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TitleLanguage.entries.forEach { language ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { appPreferences.setTitleLanguage(language) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = language == titleLanguage, onClick = { appPreferences.setTitleLanguage(language) })
                    Text(language.label())
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                "Metadata provider",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
            Text(
                "Used for background matching of new series. Fix Metadata (on a series screen) " +
                    "can always search a different provider just for that one lookup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            MetadataProviderChoice.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { appPreferences.setMetadataProvider(choice) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = choice == metadataProvider, onClick = { appPreferences.setMetadataProvider(choice) })
                    Text(choice.label())
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                "Default reading mode",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
            Text(
                "Used for any series you haven't switched individually — the reader's chrome " +
                    "has a quick-switcher that remembers your choice per series.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ReadingMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            readingMode = mode
                            prefs.defaultReadingMode = mode
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = mode == readingMode, onClick = {
                        readingMode = mode
                        prefs.defaultReadingMode = mode
                    })
                    Text(mode.label())
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SettingSwitchRow(
                title = "Swap tap zones",
                subtitle = "Flip which side of the screen advances vs goes back",
                checked = invertTapZones,
                onCheckedChange = {
                    invertTapZones = it
                    prefs.invertTapZones = it
                },
            )
            SettingSwitchRow(
                title = "Volume keys turn pages",
                subtitle = "Use the hardware volume buttons while reading",
                checked = volumeKeyPaging,
                onCheckedChange = {
                    volumeKeyPaging = it
                    prefs.volumeKeyPaging = it
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                "Reset library",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
            Text(
                "Forgets the configured source and wipes every scanned series, chapter, and " +
                    "reading-progress row, plus all cached cover/banner files. Can't be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextButton(
                onClick = { showResetConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Reset library") }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset library?") },
            text = {
                Text(
                    "This deletes your entire scanned library and all cached cover/banner " +
                        "files, and forgets the configured source (including any saved SMB " +
                        "password). Your manga files themselves are untouched. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetLibrary()
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
