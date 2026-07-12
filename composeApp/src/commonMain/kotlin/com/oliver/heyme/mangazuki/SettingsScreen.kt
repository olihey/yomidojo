package com.oliver.heyme.mangazuki

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oliver.heyme.mangazuki.core.domain.ReadingMode
import com.oliver.heyme.mangazuki.core.domain.formatDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun TitleLanguage.label(): String = when (this) {
    TitleLanguage.FILE -> stringResource(Res.string.title_language_file)
    TitleLanguage.ANILIST_ROMAJI -> stringResource(Res.string.title_language_romaji)
    TitleLanguage.ANILIST_ENGLISH -> stringResource(Res.string.title_language_english)
    TitleLanguage.ANILIST_NATIVE -> stringResource(Res.string.title_language_native)
}

@Composable
fun StartScreen.label(): String = when (this) {
    StartScreen.LIBRARY -> stringResource(Res.string.start_screen_library)
    StartScreen.YOUR_PAGE -> stringResource(Res.string.start_screen_your_page)
}

@Composable
fun MetadataProviderChoice.label(): String = when (this) {
    MetadataProviderChoice.ANILIST -> stringResource(Res.string.metadata_provider_anilist)
    MetadataProviderChoice.KITSU -> stringResource(Res.string.metadata_provider_kitsu)
}

/** Shared with the reader's chrome quick-switcher, so both use identical wording. */
@Composable
fun ReadingMode.label(): String = when (this) {
    ReadingMode.PAGED_LTR -> stringResource(Res.string.reading_mode_paged_ltr)
    ReadingMode.PAGED_RTL -> stringResource(Res.string.reading_mode_paged_rtl)
    ReadingMode.VERTICAL_PAGED -> stringResource(Res.string.reading_mode_vertical_paged)
    ReadingMode.VERTICAL_CONTINUOUS -> stringResource(Res.string.reading_mode_vertical_continuous)
}

/** Compact form for the chrome's quick-switcher button, where space is tight. */
@Composable
fun ReadingMode.shortLabel(): String = when (this) {
    ReadingMode.PAGED_LTR -> stringResource(Res.string.reading_mode_short_paged_ltr)
    ReadingMode.PAGED_RTL -> stringResource(Res.string.reading_mode_short_paged_rtl)
    ReadingMode.VERTICAL_PAGED -> stringResource(Res.string.reading_mode_short_vertical_paged)
    ReadingMode.VERTICAL_CONTINUOUS -> stringResource(Res.string.reading_mode_short_vertical_continuous)
}

// Design-specific tones from "Manga Settings Tablet" (Claude Design, imported 2026-07-09) that
// don't map onto an existing MangaColors token.
private val SectionDivider = Color(0xFF1C1A17)
private val SectionDescColor = Color(0xFF8A857F)
private val RadioOffBorder = Color(0xFF4A453F)
private val SwitchTrackOff = Color(0xFF2A2724)
private val SwitchKnobOff = Color(0xFF8F8A84)
private val SyncBylineColor = Color(0xFF5C5851)

/** A clearer, distinct red for irreversible actions (reset library, clearing a synced file) --
 * the design's own link color (`MangaColors.Accent`) doubles as every other action's color too
 * (sign in/out, view json), so a genuinely destructive action needs its own signal on top of
 * the confirmation dialog it already requires. */
private val DangerColor = Color(0xFFFF5449)

private val SettingsContentMaxWidth = 720.dp

/** "Manga Settings Tablet" (Claude Design, imported 2026-07-09): a full-bleed dark settings
 * list -- circular back button + Anton header, section title/description pairs each followed
 * by a hairline rule, custom radio dots and switches instead of Material's. The three existing
 * confirmation/debug `AlertDialog`s stay Material (PLAN.md's usual "a modal form isn't part of
 * this visual language" carve-out, same as `FixMetadataDialog`) -- the design only covers the
 * scrolling list itself.
 */
@Composable
fun SettingsScreen(
    prefs: ReaderPreferences,
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    onResetLibrary: () -> Unit,
    syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.SignedOut),
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onBackgroundSyncEnabledChanged: (Boolean) -> Unit = {},
    fetchProgressJson: suspend () -> String? = { null },
    fetchMetadataAliasesJson: suspend () -> String? = { null },
    fetchFavoritesJson: suspend () -> String? = { null },
    clearProgressJson: suspend () -> Unit = {},
    clearMetadataAliasesJson: suspend () -> Unit = {},
    clearFavoritesJson: suspend () -> Unit = {},
    exportJsonFile: suspend (fileName: String, content: String) -> Unit = { _, _ -> },
    pickJsonFile: suspend () -> String? = { null },
    importProgressJson: suspend (String) -> Unit = {},
    importMetadataAliasesJson: suspend (String) -> Unit = {},
    importFavoritesJson: suspend (String) -> Unit = {},
    isDebugBuild: Boolean = false,
) {
    ImmersiveMode(enabled = true)

    val archivo = mangaArchivo()
    val anton = mangaAnton()

    var readingMode by remember { mutableStateOf(prefs.defaultReadingMode) }
    var invertTapZones by remember { mutableStateOf(prefs.invertTapZones) }
    var volumeKeyPaging by remember { mutableStateOf(prefs.volumeKeyPaging) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var viewJsonDialog by remember { mutableStateOf<ViewJsonDialogState?>(null) }
    var clearTarget by remember { mutableStateOf<DebugFile?>(null) }
    var importTarget by remember { mutableStateOf<PendingImport?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val titleLanguage by appPreferences.titleLanguage.collectAsState()
    val startScreen by appPreferences.startScreen.collectAsState()
    val metadataProvider by appPreferences.metadataProvider.collectAsState()
    val syncEnabled by appPreferences.syncEnabled.collectAsState()
    val lastSyncedAt by appPreferences.lastSyncedAt.collectAsState()
    val metadataAliasSyncEnabled by appPreferences.metadataAliasSyncEnabled.collectAsState()
    val lastMetadataAliasSyncedAt by appPreferences.lastMetadataAliasSyncedAt.collectAsState()
    val favoriteSyncEnabled by appPreferences.favoriteSyncEnabled.collectAsState()
    val lastFavoriteSyncedAt by appPreferences.lastFavoriteSyncedAt.collectAsState()
    val backgroundSyncEnabled by appPreferences.backgroundSyncEnabled.collectAsState()
    val sync by syncState.collectAsState()

    Column(Modifier.fillMaxSize().background(MangaColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 40.dp, top = 30.dp, end = 40.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MangaColors.Panel)
                    .border(1.dp, MangaColors.PanelBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BackIcon(tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Text(stringResource(Res.string.settings_title).uppercase(), color = MangaColors.Text, fontFamily = anton, fontSize = 28.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SectionDivider))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = SettingsContentMaxWidth).fillMaxWidth().padding(horizontal = 32.dp).padding(top = 6.dp, bottom = 48.dp)) {
                SettingsSection(stringResource(Res.string.settings_section_series_title), stringResource(Res.string.settings_section_series_title_desc), archivo) {
                    TitleLanguage.entries.forEach { language ->
                        SettingsRadioRow(language.label(), selected = language == titleLanguage, archivo, onClick = { appPreferences.setTitleLanguage(language) })
                    }
                }

                SettingsSection(stringResource(Res.string.settings_section_start_screen), stringResource(Res.string.settings_section_start_screen_desc), archivo) {
                    StartScreen.entries.forEach { screen ->
                        SettingsRadioRow(screen.label(), selected = screen == startScreen, archivo, onClick = { appPreferences.setStartScreen(screen) })
                    }
                }

                SettingsSection(stringResource(Res.string.settings_section_metadata_provider), stringResource(Res.string.settings_section_metadata_provider_desc), archivo) {
                    MetadataProviderChoice.entries.forEach { choice ->
                        SettingsRadioRow(choice.label(), selected = choice == metadataProvider, archivo, onClick = { appPreferences.setMetadataProvider(choice) })
                    }
                }

                SettingsSection(stringResource(Res.string.settings_section_reading_mode), stringResource(Res.string.settings_section_reading_mode_desc), archivo) {
                    ReadingMode.entries.forEach { mode ->
                        SettingsRadioRow(
                            mode.label(), selected = mode == readingMode, archivo,
                            onClick = { readingMode = mode; prefs.defaultReadingMode = mode },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsSwitchRow(
                        stringResource(Res.string.settings_swap_tap_zones_title), stringResource(Res.string.settings_swap_tap_zones_subtitle),
                        checked = invertTapZones, archivo,
                        onCheckedChange = { invertTapZones = it; prefs.invertTapZones = it },
                    )
                    SettingsSwitchRow(
                        stringResource(Res.string.settings_volume_keys_title), stringResource(Res.string.settings_volume_keys_subtitle),
                        checked = volumeKeyPaging, archivo,
                        onCheckedChange = { volumeKeyPaging = it; prefs.volumeKeyPaging = it },
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SectionDivider))

                SettingsSection(stringResource(Res.string.settings_section_cloud_sync), stringResource(Res.string.settings_section_cloud_sync_desc), archivo) {
                    when (val state = sync) {
                        is SyncState.SignedOut -> {
                            SettingsLink(stringResource(Res.string.settings_sign_in_google), archivo, onClick = onSignIn)
                        }
                        is SyncState.SigningIn -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = MangaColors.Accent, strokeWidth = 2.dp)
                                Text(stringResource(Res.string.settings_signing_in), color = SectionDescColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                        }
                        is SyncState.SignedIn -> {
                            val notSyncedYet = stringResource(Res.string.settings_sync_not_synced_yet)
                            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                SettingsSwitchRow(
                                    stringResource(Res.string.settings_sync_progress_title), stringResource(Res.string.settings_sync_progress_subtitle),
                                    checked = syncEnabled, archivo, onCheckedChange = appPreferences::setSyncEnabled,
                                    byline = lastSyncedAt?.let { stringResource(Res.string.settings_sync_last_synced, formatDateTime(it)) } ?: notSyncedYet,
                                )
                                SettingsSwitchRow(
                                    stringResource(Res.string.settings_sync_metadata_title), stringResource(Res.string.settings_sync_metadata_subtitle),
                                    checked = metadataAliasSyncEnabled, archivo, onCheckedChange = appPreferences::setMetadataAliasSyncEnabled,
                                    byline = lastMetadataAliasSyncedAt?.let { stringResource(Res.string.settings_sync_last_synced, formatDateTime(it)) } ?: notSyncedYet,
                                )
                                SettingsSwitchRow(
                                    stringResource(Res.string.settings_sync_favorites_title), stringResource(Res.string.settings_sync_favorites_subtitle),
                                    checked = favoriteSyncEnabled, archivo, onCheckedChange = appPreferences::setFavoriteSyncEnabled,
                                    byline = lastFavoriteSyncedAt?.let { stringResource(Res.string.settings_sync_last_synced, formatDateTime(it)) } ?: notSyncedYet,
                                )
                                SettingsSwitchRow(
                                    stringResource(Res.string.settings_sync_background_title), stringResource(Res.string.settings_sync_background_subtitle),
                                    checked = backgroundSyncEnabled, archivo, onCheckedChange = onBackgroundSyncEnabledChanged,
                                )
                            }
                            SettingsLink(stringResource(Res.string.settings_sign_out), archivo, onClick = onSignOut, modifier = Modifier.padding(top = 20.dp))
                        }
                        is SyncState.Error -> {
                            Text(
                                state.message, color = DangerColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                            SettingsLink(stringResource(Res.string.settings_sign_in_google), archivo, onClick = onSignIn)
                        }
                    }
                }

                if (isDebugBuild && sync is SyncState.SignedIn) {
                    SettingsSection(stringResource(Res.string.settings_section_debug), stringResource(Res.string.settings_section_debug_desc), archivo) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsLink(
                                stringResource(Res.string.settings_debug_view_progress), archivo,
                                onClick = {
                                    viewJsonDialog = ViewJsonDialogState.Loading(DebugFile.PROGRESS)
                                    coroutineScope.launch { viewJsonDialog = ViewJsonDialogState.Loaded(DebugFile.PROGRESS, fetchProgressJson()) }
                                },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_view_metadata_aliases), archivo,
                                onClick = {
                                    viewJsonDialog = ViewJsonDialogState.Loading(DebugFile.METADATA_ALIASES)
                                    coroutineScope.launch { viewJsonDialog = ViewJsonDialogState.Loaded(DebugFile.METADATA_ALIASES, fetchMetadataAliasesJson()) }
                                },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_view_favorites), archivo,
                                onClick = {
                                    viewJsonDialog = ViewJsonDialogState.Loading(DebugFile.FAVORITES)
                                    coroutineScope.launch { viewJsonDialog = ViewJsonDialogState.Loaded(DebugFile.FAVORITES, fetchFavoritesJson()) }
                                },
                            )
                            SettingsLink(stringResource(Res.string.settings_debug_clear_progress), archivo, color = DangerColor, onClick = { clearTarget = DebugFile.PROGRESS })
                            SettingsLink(stringResource(Res.string.settings_debug_clear_metadata_aliases), archivo, color = DangerColor, onClick = { clearTarget = DebugFile.METADATA_ALIASES })
                            SettingsLink(stringResource(Res.string.settings_debug_clear_favorites), archivo, color = DangerColor, onClick = { clearTarget = DebugFile.FAVORITES })
                            SettingsLink(
                                stringResource(Res.string.settings_debug_export_progress), archivo,
                                onClick = { coroutineScope.launch { fetchProgressJson()?.let { exportJsonFile(DebugFile.PROGRESS.fileName, it) } } },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_export_metadata_aliases), archivo,
                                onClick = { coroutineScope.launch { fetchMetadataAliasesJson()?.let { exportJsonFile(DebugFile.METADATA_ALIASES.fileName, it) } } },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_import_progress), archivo,
                                onClick = { coroutineScope.launch { pickJsonFile()?.let { importTarget = PendingImport(DebugFile.PROGRESS, it) } } },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_import_metadata_aliases), archivo,
                                onClick = { coroutineScope.launch { pickJsonFile()?.let { importTarget = PendingImport(DebugFile.METADATA_ALIASES, it) } } },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_export_favorites), archivo,
                                onClick = { coroutineScope.launch { fetchFavoritesJson()?.let { exportJsonFile(DebugFile.FAVORITES.fileName, it) } } },
                            )
                            SettingsLink(
                                stringResource(Res.string.settings_debug_import_favorites), archivo,
                                onClick = { coroutineScope.launch { pickJsonFile()?.let { importTarget = PendingImport(DebugFile.FAVORITES, it) } } },
                            )
                        }
                    }
                }

                SettingsSection(
                    stringResource(Res.string.settings_reset_library_title), stringResource(Res.string.settings_reset_library_desc), archivo,
                    titleColor = DangerColor, showDivider = false,
                ) {
                    DangerButton(stringResource(Res.string.settings_reset_library_title), archivo, onClick = { showResetConfirm = true })
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(Res.string.settings_reset_confirm_title)) },
            text = {
                Text(stringResource(Res.string.settings_reset_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetLibrary()
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.settings_reset_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    viewJsonDialog?.let { state ->
        AlertDialog(
            onDismissRequest = { viewJsonDialog = null },
            title = { Text(state.file.fileName) },
            text = {
                when (state) {
                    is ViewJsonDialogState.Loading -> {
                        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text(stringResource(Res.string.settings_debug_fetching))
                        }
                    }
                    is ViewJsonDialogState.Loaded -> {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                state.json ?: stringResource(Res.string.settings_debug_not_signed_in),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewJsonDialog = null }) { Text(stringResource(Res.string.action_close)) }
            },
        )
    }

    clearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text(stringResource(Res.string.settings_debug_clear_confirm_title, target.fileName)) },
            text = {
                Text(stringResource(Res.string.settings_debug_clear_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearTarget = null
                        coroutineScope.launch {
                            when (target) {
                                DebugFile.PROGRESS -> clearProgressJson()
                                DebugFile.METADATA_ALIASES -> clearMetadataAliasesJson()
                                DebugFile.FAVORITES -> clearFavoritesJson()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.settings_debug_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    importTarget?.let { pending ->
        AlertDialog(
            onDismissRequest = { importTarget = null },
            title = { Text(stringResource(Res.string.settings_debug_import_confirm_title, pending.file.fileName)) },
            text = {
                Text(stringResource(Res.string.settings_debug_import_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importTarget = null
                        coroutineScope.launch {
                            runCatching {
                                when (pending.file) {
                                    DebugFile.PROGRESS -> importProgressJson(pending.json)
                                    DebugFile.METADATA_ALIASES -> importMetadataAliasesJson(pending.json)
                                    DebugFile.FAVORITES -> importFavoritesJson(pending.json)
                                }
                            }.onFailure { importError = it.message ?: pending.file.fileName }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.settings_debug_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { importTarget = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(Res.string.settings_debug_import_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(Res.string.action_close)) }
            },
        )
    }
}

/** A file picked for Import (PLAN.md §10), staged behind a confirmation dialog since pushing it
 * overwrites the Drive copy other devices merge against -- same "don't fire on the first tap"
 * caution as [DebugFile]'s own Clear actions, just carrying the already-read text along so
 * confirming doesn't need to re-open the picker. */
private data class PendingImport(val file: DebugFile, val json: String)

/** Settings' Debug section (PLAN.md §10) -- which of the three `appDataFolder` files a
 * view/clear/export/import action targets. */
private enum class DebugFile(val fileName: String) {
    PROGRESS("progress.json"),
    METADATA_ALIASES("metadata_aliases.json"),
    FAVORITES("favorites.json"),
}

/** A fetch takes a network round trip, so this needs a loading state distinct from "loaded but
 * null" (not signed in, or the file doesn't exist on Drive yet -- both normal). */
private sealed interface ViewJsonDialogState {
    val file: DebugFile
    data class Loading(override val file: DebugFile) : ViewJsonDialogState
    data class Loaded(override val file: DebugFile, val json: String?) : ViewJsonDialogState
}

/** One settings group: an extrabold title, a muted description, the radio/switch/link rows
 * passed as [content], then a hairline rule -- the design's recurring section shape. */
@Composable
private fun SettingsSection(
    title: String,
    description: String,
    archivo: FontFamily,
    titleColor: Color = MangaColors.Text,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp)) {
        Text(title, color = titleColor, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Text(
            description, color = SectionDescColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Column(Modifier.padding(top = 14.dp), content = content)
    }
    if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(SectionDivider))
}

@Composable
private fun SettingsRadioRow(label: String, selected: Boolean, archivo: FontFamily, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) MangaColors.Accent else RadioOffBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MangaColors.Accent))
        }
        Text(label, color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    archivo: FontFamily,
    onCheckedChange: (Boolean) -> Unit,
    byline: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MangaColors.Text, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = SectionDescColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            if (byline != null) {
                Text(byline, color = SyncBylineColor, fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        MangaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The design's own switch shape (46x27 track, 21dp knob) rather than Material's -- a plain
 * `Switch` reads visually out of place against the rest of this dark, custom-drawn screen. */
@Composable
private fun MangaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(if (checked) MangaColors.Accent else SwitchTrackOff)
    val knobColor by animateColorAsState(if (checked) MangaColors.Bg else SwitchKnobOff)
    val knobOffset: Dp by animateDpAsState(if (checked) 19.dp else 0.dp)
    Box(
        Modifier.size(width = 46.dp, height = 27.dp).clip(RoundedCornerShape(14.dp)).background(trackColor)
            .clickable(onClick = { onCheckedChange(!checked) }),
    ) {
        Box(Modifier.offset(x = 3.dp + knobOffset, y = 3.dp).size(21.dp).clip(CircleShape).background(knobColor))
    }
}

@Composable
private fun SettingsLink(text: String, archivo: FontFamily, color: Color = MangaColors.Accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** The one action on this whole screen that needs to read as a deliberate button press rather
 * than a plain link -- irreversible, so it gets a bordered pill instead of inline text. */
@Composable
private fun DangerButton(text: String, archivo: FontFamily, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(MangaColors.Panel).border(1.dp, DangerColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text.uppercase(), color = DangerColor, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.6.sp)
    }
}
