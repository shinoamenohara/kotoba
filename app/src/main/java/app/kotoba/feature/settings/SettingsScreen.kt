package app.kotoba.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.BuildConfig
import app.kotoba.R
import app.kotoba.core.data.ThemeMode
import app.kotoba.core.data.UserSettings
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.rememberHaptics
import java.text.DateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

private enum class SettingsDialog { NAME, STUDY_LANGS, NATIVE, UI_LANG, LEARN_STEPS, RELEARN_STEPS, TIME, BACKUP_OPTIONS }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onImportExport: () -> Unit,
    onStorage: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s = vm.settings.collectAsStateWithLifecycle().value
    val ai by vm.aiStatus.collectAsStateWithLifecycle()
    val backup by vm.backupState.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    var includeDocs by rememberSaveable { mutableStateOf(true) }
    var includeMedia by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            val msg = when (e) {
                is SettingsEvent.StarterDecks ->
                    if (e.cards > 0) context.resources.getQuantityString(R.plurals.settings_starter_added, e.cards, e.cards)
                    else context.getString(R.string.settings_starter_none)
                SettingsEvent.BackupDone -> { haptics.success(); context.getString(R.string.settings_backup_done) }
                SettingsEvent.RestoreDone -> { haptics.success(); context.getString(R.string.settings_restore_done) }
                SettingsEvent.NoBackupInFolder -> context.getString(R.string.settings_backup_folder_empty)
                is SettingsEvent.Error -> context.getString(R.string.settings_error, e.message)
            }
            snackbar.showSnackbar(msg)
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.backupNow(it, includeDocs, includeMedia) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::prepareRestore) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::enableAutoBackup) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (s != null) vm.setReminders(granted, s.reminderHour, s.reminderMinute)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { KotobaTopBar(stringResource(R.string.settings_title), onBack, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { ProfileHeader(s) { dialog = SettingsDialog.NAME } }

            item {
                SettingsGroup(stringResource(R.string.settings_group_languages)) {
                    PrefRow(
                        stringResource(R.string.settings_study_languages), Icons.Default.Translate,
                        subtitle = s.studyLanguages.sortedBy { it.ordinal }.joinToString("  ") { "${it.flag} ${it.nativeName}" },
                        onClick = { dialog = SettingsDialog.STUDY_LANGS }, showChevron = true,
                    )
                    SettingsDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.settings_active_language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 40.dp))
                        FlowRow(Modifier.padding(start = 40.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.studyLanguages.sortedBy { it.ordinal }.forEach { l ->
                                FilterChip(
                                    selected = l == s.activeLanguage,
                                    onClick = { haptics.tick(); vm.setActiveLanguage(l) },
                                    label = { Text("${l.flag} ${l.nativeName}") },
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_native_language), Icons.Default.Language,
                        subtitle = s.nativeLanguage.nativeName, onClick = { dialog = SettingsDialog.NATIVE }, showChevron = true,
                    )
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_starter_decks), Icons.Default.Download,
                        subtitle = if (s.activeLanguage in vm.seedLanguages) stringResource(R.string.settings_starter_decks_desc, s.activeLanguage.nativeName)
                        else stringResource(R.string.settings_starter_unavailable),
                        enabled = s.activeLanguage in vm.seedLanguages && !installing,
                        onClick = vm::installStarterDecks,
                    ) { if (installing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_interface_language), Icons.Default.Translate,
                        subtitle = interfaceLanguageLabel(s.interfaceLanguage) +
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) " · " + stringResource(R.string.settings_ui_lang_old_android) else "",
                        onClick = { dialog = SettingsDialog.UI_LANG }, showChevron = true,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_learning)) {
                    StepperPref(stringResource(R.string.settings_new_per_day), s.newPerDay, vm::setNewPerDay, 0..200, 5, Icons.Default.School)
                    SettingsDivider()
                    StepperPref(stringResource(R.string.settings_max_reviews), s.maxReviewsPerDay, vm::setMaxReviews, 10..2000, 10, Icons.Default.Replay)
                    SettingsDivider()
                    SliderPref(
                        title = stringResource(R.string.settings_retention),
                        value = s.desiredRetention.toFloat().coerceIn(0.80f, 0.97f),
                        range = 0.80f..0.97f, steps = 16,
                        format = { "${(it * 100).toInt()}%" },
                        onChange = { vm.setRetention(Math.round(it * 100) / 100.0) },
                        icon = Icons.Default.Bolt,
                        description = stringResource(R.string.settings_retention_desc),
                    )
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_learning_steps), Icons.Default.Timer,
                        subtitle = stepsLabel(s.learningSteps), onClick = { dialog = SettingsDialog.LEARN_STEPS }, showChevron = true,
                    )
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_relearning_steps), Icons.Default.Restore,
                        subtitle = stepsLabel(s.relearningSteps), onClick = { dialog = SettingsDialog.RELEARN_STEPS }, showChevron = true,
                    )
                    SettingsDivider()
                    StepperPref(stringResource(R.string.settings_daily_xp), s.dailyXpGoal, vm::setDailyXpGoal, 10..1000, 10, Icons.Default.Today)
                    SettingsDivider()
                    SegmentedPref(
                        stringResource(R.string.settings_quick_review), listOf(1, 3, 5), s.quickReviewMinutes.takeIf { it in setOf(1, 3, 5) } ?: 3,
                        label = { stringResource(R.string.settings_minutes_short, it) }, onSelect = vm::setQuickMinutes, icon = Icons.Default.Speed,
                    )
                    SettingsDivider()
                    SwitchPref(stringResource(R.string.settings_autoplay), s.autoPlayAudio, vm::setAutoPlay, Icons.AutoMirrored.Filled.VolumeUp)
                    SettingsDivider()
                    SwitchPref(stringResource(R.string.settings_show_reading), s.showReading, vm::setShowReading, Icons.Default.Visibility, stringResource(R.string.settings_show_reading_desc))
                    SettingsDivider()
                    SliderPref(
                        title = stringResource(R.string.settings_tts_speed),
                        value = s.ttsRate.coerceIn(0.5f, 2f), range = 0.5f..2f, steps = 14,
                        format = { "%.1f×".format(it) }, onChange = vm::setTtsRate, icon = Icons.Default.RecordVoiceOver,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                    SegmentedPref(
                        stringResource(R.string.settings_theme), ThemeMode.entries, s.theme,
                        label = { themeLabel(it) }, onSelect = vm::setTheme, icon = Icons.Default.DarkMode,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingsDivider()
                        SwitchPref(stringResource(R.string.settings_dynamic_color), s.dynamicColor, vm::setDynamicColor, Icons.Default.ColorLens, stringResource(R.string.settings_dynamic_color_desc))
                    }
                    SettingsDivider()
                    SwitchPref(stringResource(R.string.settings_animations), s.animations, vm::setAnimations, Icons.Default.Animation)
                    SettingsDivider()
                    SwitchPref(stringResource(R.string.settings_sounds), s.sounds, vm::setSounds, Icons.AutoMirrored.Filled.VolumeUp)
                    SettingsDivider()
                    SwitchPref(stringResource(R.string.settings_haptics), s.haptics, vm::setHaptics, Icons.Default.Vibration)
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_notifications)) {
                    SwitchPref(
                        stringResource(R.string.settings_reminders), s.remindersEnabled,
                        onChange = { on ->
                            val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.setReminders(on, s.reminderHour, s.reminderMinute)
                        },
                        icon = Icons.Default.Notifications, subtitle = stringResource(R.string.settings_reminders_desc),
                    )
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_reminder_time), Icons.Default.Schedule,
                        subtitle = formatTime(s.reminderHour, s.reminderMinute), enabled = s.remindersEnabled,
                        onClick = { dialog = SettingsDialog.TIME }, showChevron = true,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_ai)) {
                    PrefRow(
                        stringResource(R.string.settings_ai), Icons.Default.AutoAwesome,
                        subtitle = ai?.let { st ->
                            if (st.configured) stringResource(R.string.settings_ai_ready, st.providerName, st.model)
                            else stringResource(R.string.settings_ai_not_configured, st.providerName)
                        },
                        onClick = onAiSettings, showChevron = true,
                    ) { ai?.let { StatusDot(it.configured) } }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_data)) {
                    PrefRow(stringResource(R.string.settings_import_export), Icons.Default.ImportExport, stringResource(R.string.settings_import_export_desc), onClick = onImportExport, showChevron = true)
                    SettingsDivider()
                    PrefRow(stringResource(R.string.settings_storage), Icons.Default.Storage, stringResource(R.string.settings_storage_desc), onClick = onStorage, showChevron = true)
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_backup)) {
                    if (backup.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    PrefRow(
                        stringResource(R.string.settings_backup_now), Icons.Default.Backup,
                        subtitle = if (s.lastBackupAt > 0) stringResource(R.string.settings_last_backup, formatDateTime(s.lastBackupAt))
                        else stringResource(R.string.settings_never_backed_up),
                        enabled = !backup.busy, onClick = { dialog = SettingsDialog.BACKUP_OPTIONS },
                    )
                    SettingsDivider()
                    PrefRow(
                        stringResource(R.string.settings_restore), Icons.Default.Restore, stringResource(R.string.settings_restore_desc),
                        enabled = !backup.busy, onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    )
                    SettingsDivider()
                    SwitchPref(
                        stringResource(R.string.settings_auto_backup), s.autoBackupEnabled && s.backupFolderUri.isNotBlank(),
                        onChange = { on -> if (on) folderLauncher.launch(null) else vm.disableAutoBackup() },
                        icon = Icons.Default.CloudSync, subtitle = stringResource(R.string.settings_auto_backup_desc), enabled = !backup.busy,
                    )
                    if (s.backupFolderUri.isNotBlank()) {
                        SettingsDivider()
                        PrefRow(
                            stringResource(R.string.settings_backup_folder), Icons.Default.FolderOpen,
                            subtitle = backup.folderName.ifBlank { "…" }, enabled = !backup.busy,
                            onClick = { folderLauncher.launch(null) }, showChevron = true,
                        )
                        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::backupToFolderNow, enabled = !backup.busy) { Text(stringResource(R.string.settings_backup_to_folder)) }
                            TextButton(onClick = vm::restoreFromFolder, enabled = !backup.busy) { Text(stringResource(R.string.settings_restore_from_folder)) }
                        }
                    }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_about)) {
                    PrefRow(
                        stringResource(R.string.app_name), Icons.Default.Info,
                        subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    if (s != null) when (dialog) {
        SettingsDialog.NAME -> TextDialog(
            title = stringResource(R.string.settings_profile_name), initial = s.userName,
            onDismiss = { dialog = null }, onConfirm = { vm.setUserName(it); dialog = null },
        )
        SettingsDialog.STUDY_LANGS -> StudyLanguagesDialog(s.studyLanguages, onDismiss = { dialog = null }) { vm.setStudyLanguages(it); dialog = null }
        SettingsDialog.NATIVE -> ChoiceDialog(
            stringResource(R.string.settings_native_language), NativeLanguage.entries, s.nativeLanguage, { it.nativeName },
            onDismiss = { dialog = null },
        ) { vm.setNativeLanguage(it); dialog = null }
        SettingsDialog.UI_LANG -> ChoiceDialog(
            stringResource(R.string.settings_interface_language), listOf("", "en", "ru"), s.interfaceLanguage.takeIf { it in setOf("", "en", "ru") } ?: "",
            { interfaceLanguageLabel(it) }, onDismiss = { dialog = null },
            footer = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) stringResource(R.string.settings_ui_lang_old_android_long) else null,
        ) { vm.setInterfaceLanguage(it); dialog = null }
        SettingsDialog.LEARN_STEPS -> StepsDialog(stringResource(R.string.settings_learning_steps), s.learningSteps, { dialog = null }) { vm.setLearningSteps(it); dialog = null }
        SettingsDialog.RELEARN_STEPS -> StepsDialog(stringResource(R.string.settings_relearning_steps), s.relearningSteps, { dialog = null }) { vm.setRelearningSteps(it); dialog = null }
        SettingsDialog.TIME -> TimeDialog(s.reminderHour, s.reminderMinute, { dialog = null }) { h, m -> vm.setReminders(s.remindersEnabled, h, m); dialog = null }
        SettingsDialog.BACKUP_OPTIONS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_backup_now)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_backup_options_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    CheckRow(stringResource(R.string.settings_backup_include_docs), includeDocs) { includeDocs = it }
                    CheckRow(stringResource(R.string.settings_backup_include_media), includeMedia) { includeMedia = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; backupLauncher.launch(vm.backupFileName()) }) { Text(stringResource(R.string.settings_backup_choose_location)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }

    backup.pendingRestore?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_restore_confirm_body, formatDateTime(p.summary.createdAt), p.summary.cards, p.summary.decks) +
                        if (p.summary.hasDocuments) "\n\n" + pluralStringResource(R.plurals.settings_restore_docs, p.summary.documents, p.summary.documents) else "",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text(stringResource(R.string.settings_restore_replace), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProfileHeader(s: UserSettings, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    s.userName.trim().firstOrNull()?.uppercase() ?: s.activeLanguage.flag,
                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(s.userName.ifBlank { stringResource(R.string.settings_profile_unnamed) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_profile_edit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun themeLabel(t: ThemeMode) = stringResource(
    when (t) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

@Composable
private fun interfaceLanguageLabel(tag: String) = when (tag) {
    "en" -> "English"
    "ru" -> "Русский"
    else -> stringResource(R.string.settings_ui_lang_system)
}

@Composable
private fun stepsLabel(steps: List<Int>) = steps.joinToString(" → ") { formatMinutes(it) }

private fun formatMinutes(m: Int) = when {
    m % 1440 == 0 -> "${m / 1440}d"
    m % 60 == 0 && m >= 60 -> "${m / 60}h"
    else -> "${m}m"
}

private fun formatTime(h: Int, m: Int): String = LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun formatDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    footer: String? = null,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .selectable(o == selected, role = Role.RadioButton) { onSelect(o) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (footer != null) {
                    Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun StudyLanguagesDialog(current: Set<StudyLanguage>, onDismiss: () -> Unit, onConfirm: (Set<StudyLanguage>) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    val featured = StudyLanguage.featuredLanguages
    val others = StudyLanguage.entries.filter { !it.featured }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_study_languages)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_featured_languages), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                featured.forEach { l -> LanguageCheck(l, l in selected, featured = true) { on -> selected = if (on) selected + l else selected - l } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.settings_other_languages), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                others.forEach { l -> LanguageCheck(l, l in selected, featured = false) { on -> selected = if (on) selected + l else selected - l } }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LanguageCheck(l: StudyLanguage, checked: Boolean, featured: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(l.flag, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.nativeName, style = MaterialTheme.typography.bodyLarge)
            if (featured) {
                Text(
                    stringResource(R.string.settings_language_levels, l.levelScale.name, l.levelScale.levels.first(), l.levelScale.levels.last()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Parses "1, 10, 60" into minutes; invalid input disables Save. */
internal fun parseSteps(text: String): List<Int>? {
    val parts = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > 10) return null
    val values = parts.map { it.toIntOrNull() ?: return null }
    return values.takeIf { v -> v.all { it in 1..43_200 } }
}

@Composable
private fun StepsDialog(title: String, current: List<Int>, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.joinToString(", ")) }
    val parsed = parseSteps(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(R.string.settings_steps_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, isError = parsed == null,
                    label = { Text(stringResource(R.string.settings_steps_label)) },
                    supportingText = { Text(parsed?.let { p -> p.joinToString(" → ") { formatMinutes(it) } } ?: stringResource(R.string.settings_steps_invalid)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(hour, minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder_time)) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
