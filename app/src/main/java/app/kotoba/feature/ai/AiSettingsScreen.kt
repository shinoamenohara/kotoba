package app.kotoba.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.LabeledProgress
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.rememberHaptics

@Composable
fun AiSettingsScreen(onBack: () -> Unit, vm: AiSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val provider = state.provider
    Scaffold(topBar = { KotobaTopBar(stringResource(R.string.ai_settings_title), onBack) }) { padding ->
        // Fields seed local text from settings once, so wait for the real values.
        if (provider == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.ai_provider))
            ProviderPicker(state, vm.providers.map { it.id to it.displayName }, vm::selectProvider)

            run {
                if (provider.requiresApiKey) {
                    SectionHeader(stringResource(R.string.ai_api_key))
                    ApiKeySection(state.maskedKey, provider.keyHelpUrl, onSave = vm::saveKey, onRemove = vm::removeKey)
                }

                SectionHeader(stringResource(R.string.ai_connection))
                CommittedTextField(
                    key = provider.id,
                    value = state.ai.baseUrl,
                    label = stringResource(R.string.ai_base_url),
                    placeholder = provider.defaultBaseUrl,
                    supporting = stringResource(if (provider.requiresApiKey) R.string.ai_base_url_hint else R.string.ai_base_url_hint_local),
                    keyboardType = KeyboardType.Uri,
                    onCommit = vm::setBaseUrl,
                )
                Spacer(Modifier.height(8.dp))
                ModelField(state, provider.suggestedModels, onModel = vm::setModel, onFetch = vm::fetchModels)
                Spacer(Modifier.height(12.dp))
                ConnectionTest(state.test, vm::testConnection)
            }

            SectionHeader(stringResource(R.string.ai_generation))
            SliderRow(
                label = stringResource(R.string.ai_temperature),
                valueText = "%.1f".format(state.ai.temperature),
                value = state.ai.temperature.toFloat(),
                range = 0f..2f,
                steps = 19,
                onCommit = { vm.setTemperature(it.toDouble()) },
            )
            Text(
                stringResource(R.string.ai_temperature_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            NumberField(stringResource(R.string.ai_max_tokens), state.ai.maxOutputTokens, 64..32_000, vm::setMaxTokens)

            SectionHeader(stringResource(R.string.ai_limits))
            Text(
                stringResource(R.string.ai_limits_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(stringResource(R.string.ai_daily_tokens), state.ai.dailyTokenLimit, 1_000..100_000_000, vm::setDailyTokens, Modifier.weight(1f))
                NumberField(stringResource(R.string.ai_daily_requests), state.ai.dailyRequestLimit, 1..100_000, vm::setDailyRequests, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.ai_usage_today), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LabeledProgress(stringResource(R.string.ai_usage_tokens), state.usageToday.tokens, state.ai.dailyTokenLimit)
            Spacer(Modifier.height(8.dp))
            LabeledProgress(stringResource(R.string.ai_usage_requests), state.usageToday.requests, state.ai.dailyRequestLimit)

            SectionHeader(stringResource(R.string.ai_privacy))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_share_stats))
                    Text(
                        stringResource(R.string.ai_share_stats_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(state.ai.shareLearningStats, vm::setShareStats)
            }
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ai_keys_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(state: AiSettingsUiState, providers: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.provider?.displayName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ai_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun ApiKeySection(masked: String?, helpUrl: String, onSave: (String) -> Unit, onRemove: () -> Unit) {
    val uri = LocalUriHandler.current
    val haptics = rememberHaptics()
    if (masked != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_key_saved), fontWeight = FontWeight.SemiBold)
                Text(masked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.ai_key_remove)) }
        }
    } else {
        var key by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.ai_api_key)) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { runCatching { uri.openUri(helpUrl) } }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_get_key))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { haptics.success(); onSave(key); key = "" }, enabled = key.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(state: AiSettingsUiState, suggested: List<String>, onModel: (String) -> Unit, onFetch: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(state.ai.providerId) { mutableStateOf(state.ai.model) }
    val options = (suggested + state.fetchedModels).distinct()
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onModel(it) },
                label = { Text(stringResource(R.string.ai_model)) },
                placeholder = { Text(suggested.firstOrNull().orEmpty()) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m + if (m in suggested) "  ★" else "") },
                        onClick = { expanded = false; text = m; onModel(m) },
                    )
                }
            }
        }
        IconButton(onClick = onFetch, enabled = !state.fetchingModels) {
            if (state.fetchingModels) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, stringResource(R.string.ai_fetch_models))
        }
    }
    if (state.fetchedModels.isNotEmpty()) {
        Text(
            stringResource(R.string.ai_models_fetched, state.fetchedModels.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

@Composable
private fun ConnectionTest(result: ConnectionResult?, onTest: () -> Unit) {
    Column {
        FilledTonalButton(onClick = onTest, enabled = result != ConnectionResult.Running, modifier = Modifier.fillMaxWidth()) {
            if (result == ConnectionResult.Running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.ai_test_connection))
        }
        when (result) {
            is ConnectionResult.Ok -> Text(
                "✅ " + stringResource(R.string.ai_test_ok, result.reply.ifBlank { "—" }, result.modelCount),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            is ConnectionResult.Failed -> Column(Modifier.padding(top = 8.dp)) {
                Text("⚠️ " + aiErrorTitle(result.failure), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                if (result.failure.showDetail) Text(result.failure.detail, style = MaterialTheme.typography.bodySmall)
            }
            else -> Unit
        }
    }
}

@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("%.1f".format(local).takeIf { local != value } ?: valueText, fontWeight = FontWeight.SemiBold)
    }
    Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local) }, valueRange = range, steps = steps)
}

/** Keeps the typed text locally and commits it when it parses. */
@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Not keyed on value: settings round-trip asynchronously and would overwrite what is being typed.
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(9)
            text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CommittedTextField(
    key: Any,
    value: String,
    label: String,
    placeholder: String,
    supporting: String,
    keyboardType: KeyboardType,
    onCommit: (String) -> Unit,
) {
    var text by remember(key) { mutableStateOf(value) }
    val insecure = !BaseUrlPolicy.isAllowed(text)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        isError = insecure,
        supportingText = { Text(if (insecure) stringResource(R.string.ai_base_url_insecure) else supporting) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
