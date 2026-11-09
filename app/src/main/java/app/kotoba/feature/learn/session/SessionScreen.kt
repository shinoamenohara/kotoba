package app.kotoba.feature.learn.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.theme.LocalUserSettings
import app.kotoba.feature.learn.logic.ExercisePlanner

@Composable
fun SessionScreen(
    onBack: () -> Unit,
    onEditCard: (Long) -> Unit,
    onOpenCard: (Long) -> Unit,
    vm: SessionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalUserSettings.current
    val snackbar = remember { SnackbarHostState() }

    // Leaving mid-session shows the summary first; everything answered is already saved.
    val inProgress = state.summary == null && state.answered > 0
    val back = { if (inProgress) vm.finish() else onBack() }
    BackHandler(enabled = inProgress) { vm.finish() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        topBar = {
            KotobaTopBar(
                title = when {
                    state.summary != null -> stringResource(R.string.learn_sum_header)
                    state.mode == SessionMode.EXERCISE && state.requestedExercise != null -> exerciseName(state.requestedExercise!!)
                    else -> modeTitle(state.mode)
                },
                onBack = back,
                actions = {
                    if (inProgress) {
                        IconButton(onClick = vm::finish) { Icon(Icons.Rounded.Flag, stringResource(R.string.learn_finish)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                state.loading || state.finishing -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.summary != null -> SummaryContent(state.summary!!, onContinue = vm::restart, onDone = onBack)
                state.empty -> EmptySession(state.mode, onBack)
                state.mode == SessionMode.QUICK -> QuickReviewContent(state, onAnswer = vm::quick, onSpeak = { vm.speak(it) })
                else -> SessionBody(state, vm, onEditCard, onOpenCard)
            }
            XpBurstOverlay(state.xpBurst, settings.animations, Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
        }
    }
}

@Composable
private fun SessionBody(
    state: SessionUiState,
    vm: SessionViewModel,
    onEditCard: (Long) -> Unit,
    onOpenCard: (Long) -> Unit,
) {
    val animations = LocalUserSettings.current.animations
    val showReading = LocalUserSettings.current.showReading
    Column(Modifier.fillMaxSize()) {
        SessionProgress(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state.step,
                contentKey = { it?.index },
                transitionSpec = {
                    if (animations) {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                    } else {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    }
                },
                label = "step",
            ) { step ->
                if (step != null) ExerciseHost(step, state, vm, onEditCard, onOpenCard)
            }
        }
        val step = state.step
        val selfGraded = step == null || ExercisePlanner.isFlashcard(step.exercise) || step.exercise == ExerciseType.MATCHING
        AnimatedVisibility(
            visible = state.feedback != null && !selfGraded,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            state.feedback?.let { fb ->
                FeedbackPanel(fb, showReading, onSpeak = { vm.speak(fb.card.term) }, onContinue = vm::next)
            }
        }
    }
}

@Composable
private fun ExerciseHost(
    step: PreparedStep,
    state: SessionUiState,
    vm: SessionViewModel,
    onEditCard: (Long) -> Unit,
    onOpenCard: (Long) -> Unit,
) {
    val answered = state.feedback != null && state.step?.index == step.index
    val speak: (String) -> Unit = { vm.speak(it) }
    val speakSlow: (String, Boolean) -> Unit = { t, slow -> vm.speak(t, slow) }
    when (step.exercise) {
        ExerciseType.FLASHCARD_TERM_TO_TRANSLATION, ExerciseType.FLASHCARD_TRANSLATION_TO_TERM,
        ExerciseType.FLASHCARD_TERM_TO_CONTEXT, ExerciseType.FLASHCARD_AUDIO_TO_TERM, ExerciseType.QUICK_REVIEW,
        -> FlashcardExercise(
            step = step,
            onRate = vm::rate,
            onSpeak = speak,
            onFavorite = vm::toggleFavorite,
            onEdit = { onEditCard(step.card.id) },
            onMore = { onOpenCard(step.card.id) },
        )
        ExerciseType.MULTIPLE_CHOICE, ExerciseType.REVERSE_MULTIPLE_CHOICE -> ChoiceExercise(step, answered, vm::submit, speak)
        ExerciseType.TYPING -> TypingExercise(step, answered, vm::submit)
        ExerciseType.LISTENING -> ListeningExercise(step, answered, vm::submit, speakSlow)
        ExerciseType.SPELLING -> SpellingExercise(step, answered, vm::submit, speakSlow)
        ExerciseType.WORD_SCRAMBLE -> ScrambleExercise(step, answered, vm::submit)
        ExerciseType.FILL_BLANK -> FillBlankExercise(step, answered, vm::submit)
        ExerciseType.SENTENCE_BUILDER -> SentenceBuilderExercise(step, answered, vm::submit, speak)
        ExerciseType.MATCHING -> MatchingExercise(step, state.matchedIds, vm::matchPair, speak)
        ExerciseType.CONTEXT_QUIZ -> ContextQuizExercise(step, answered, vm::submit, speak)
        ExerciseType.TRANSLATION -> TranslationExercise(
            step, answered, state.aiAvailable, state.translationGrade,
            onGrade = vm::gradeTranslation, onSubmit = vm::submit, onSpeak = speak,
        )
        ExerciseType.PRONUNCIATION -> PronunciationExercise(step, answered, vm::submit, speak)
    }
}

@Composable
private fun SessionProgress(state: SessionUiState) {
    val progress by animateFloatAsState(state.progress, label = "progress")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f).height(10.dp),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "${(state.index + 1).coerceAtMost(state.stepCount)}/${state.stepCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(state.combo >= 2, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    stringResource(R.string.learn_combo, state.combo),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Floating "+10 XP" that rises and fades after each recorded answer. */
@Composable
private fun XpBurstOverlay(burst: XpBurst?, animate: Boolean, modifier: Modifier = Modifier) {
    if (burst == null || burst.xp <= 0) return
    val anim = remember(burst.id) { Animatable(0f) }
    LaunchedEffect(burst.id) { anim.animateTo(1f, tween(if (animate) 1100 else 600)) }
    val t = anim.value
    if (t >= 1f) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.graphicsLayer {
            translationY = if (animate) -60f * t else 0f
            alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
            val s = if (animate) 0.8f + 0.2f * minOf(1f, t * 4) else 1f
            scaleX = s; scaleY = s
        },
    ) {
        Text(
            stringResource(R.string.learn_xp_plus, burst.xp) + if (burst.combo >= 3) "  🔥${burst.combo}" else "",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EmptySession(mode: SessionMode, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            emoji = if (mode == SessionMode.WEAK) "💪" else "✅",
            title = stringResource(if (mode == SessionMode.WEAK) R.string.learn_empty_weak_title else R.string.learn_empty_title),
            message = stringResource(if (mode == SessionMode.WEAK) R.string.learn_empty_weak_msg else R.string.learn_empty_msg),
        ) {
            Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.action_back)) }
        }
    }
}

@Composable
fun modeTitle(mode: SessionMode): String = stringResource(
    when (mode) {
        SessionMode.DAILY -> R.string.learn_mode_daily
        SessionMode.NEW -> R.string.learn_mode_new
        SessionMode.REVIEW -> R.string.learn_mode_review
        SessionMode.WEAK -> R.string.learn_mode_weak
        SessionMode.QUICK -> R.string.learn_quick_title
        SessionMode.FLASHCARDS -> R.string.learn_mode_flashcards
        SessionMode.EXERCISE -> R.string.learn_mode_exercise
        SessionMode.MIXED -> R.string.learn_mode_mixed
    },
)
