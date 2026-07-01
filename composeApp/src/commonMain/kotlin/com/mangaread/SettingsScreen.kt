package com.mangaread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangaread.core.domain.ReadingMode

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
fun SettingsScreen(prefs: ReaderPreferences, onBack: () -> Unit) {
    var readingMode by remember { mutableStateOf(prefs.defaultReadingMode) }
    var invertTapZones by remember { mutableStateOf(prefs.invertTapZones) }
    var volumeKeyPaging by remember { mutableStateOf(prefs.volumeKeyPaging) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
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
        }
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
