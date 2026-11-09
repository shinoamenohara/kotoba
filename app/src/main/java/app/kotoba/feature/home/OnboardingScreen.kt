package app.kotoba.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage

private const val STEPS = 4

@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onFinished() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        LinearProgressIndicator(
            progress = { (s.step + 1f) / STEPS },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(24.dp))
        AnimatedContent(targetState = s.step, label = "onboarding", modifier = Modifier.weight(1f)) { step ->
            when (step) {
                0 -> WelcomeStep()
                1 -> LanguagesStep(s.languages, vm.seededLanguages, vm::toggleLanguage)
                2 -> NativeAndGoalStep(s.native, s.dailyGoal, vm::setNative, vm::setGoal)
                else -> NameStep(s.name, vm::setName)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.step > 0) TextButton(onClick = vm::back) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            if (s.installing) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.onboarding_installing))
            } else {
                Button(onClick = { if (s.step < STEPS - 1) vm.next() else vm.finish() }, modifier = Modifier.height(52.dp)) {
                    Text(stringResource(if (s.step < STEPS - 1) R.string.action_continue else R.string.onboarding_start))
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("言葉", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguagesStep(selected: Set<StudyLanguage>, seeded: Set<StudyLanguage>, onToggle: (StudyLanguage) -> Unit) {
    Column {
        Text(stringResource(R.string.onboarding_languages_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_languages_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(StudyLanguage.entries.sortedBy { if (it.featured) 0 else 1 }) { lang ->
                val on = lang in selected
                Card(
                    onClick = { onToggle(lang) },
                    border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = CardDefaults.cardColors(
                        containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(lang.flag, style = MaterialTheme.typography.headlineMedium)
                        Text(lang.nativeName, style = MaterialTheme.typography.titleMedium)
                        val hint = when {
                            lang.featured -> stringResource(R.string.onboarding_featured)
                            lang in seeded -> stringResource(R.string.onboarding_starter)
                            else -> stringResource(R.string.onboarding_own_words)
                        }
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAndGoalStep(native: NativeLanguage, goal: Int, onNative: (NativeLanguage) -> Unit, onGoal: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onboarding_native_title), style = MaterialTheme.typography.headlineSmall)
        NativeLanguage.entries.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = n == native, onClick = { onNative(n) })
                Text(n.nativeName, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_goal_title), style = MaterialTheme.typography.headlineSmall)
        listOf(
            20 to R.string.onboarding_goal_casual,
            50 to R.string.onboarding_goal_regular,
            100 to R.string.onboarding_goal_serious,
            200 to R.string.onboarding_goal_intense,
        ).forEach { (xp, label) ->
            Surface(
                onClick = { onGoal(xp) },
                shape = MaterialTheme.shapes.medium,
                color = if (xp == goal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp)) {
                    Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Text("$xp XP", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onboarding_name_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.onboarding_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.onboarding_ai_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
