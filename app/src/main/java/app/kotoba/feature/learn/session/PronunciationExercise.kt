package app.kotoba.feature.learn.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kotoba.R
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.learn.logic.AnswerMatcher
import app.kotoba.feature.learn.logic.MatchResult

private const val MAX_TRIES = 3

/**
 * Say the word: Android SpeechRecognizer in the study language compares what was heard to the
 * term (or reading). Without a recognizer or microphone permission it falls back to self-assessment.
 */
@Composable
fun PronunciationExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val card = step.card
    val lang = StudyLanguage.fromCode(card.language)
    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    var listening by remember(step.index) { mutableStateOf(false) }
    var heard by rememberSaveable(step.index) { mutableStateOf<String?>(null) }
    var tries by rememberSaveable(step.index) { mutableIntStateOf(0) }
    var recognizerError by remember(step.index) { mutableStateOf(false) }

    val recognizer = remember(recognizerAvailable) {
        if (recognizerAvailable) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }

    fun evaluate(candidates: List<String>) {
        val hit = candidates.firstOrNull { AnswerMatcher.match(it, card.term, card.reading, lang) != MatchResult.WRONG }
        heard = hit ?: candidates.firstOrNull().orEmpty()
        tries++
        when {
            hit != null -> onSubmit(Attempt(correct = true, userAnswer = hit))
            tries >= MAX_TRIES -> onSubmit(Attempt(correct = false, userAnswer = heard))
        }
    }

    fun listen() {
        val r = recognizer ?: return
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    evaluate(emptyList())
                } else {
                    recognizerError = true
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                evaluate(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching { r.startListening(intent) }.onFailure { recognizerError = true }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) listen()
    }

    val selfAssess = recognizer == null || denied || recognizerError
    val pulse = rememberInfiniteTransition(label = "mic")
    val scale by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "micScale")

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Instruction(step.exercise, Modifier.fillMaxWidth())
        TermHeadline(card, onSpeak = { onSpeak(card.term) })
        Text(card.translation, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (answered) return@Column
        if (selfAssess) {
            Text(
                stringResource(if (recognizer == null || recognizerError) R.string.learn_pron_unavailable else R.string.learn_pron_denied),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.learn_pron_self), style = MaterialTheme.typography.labelLarge)
            SelfAssessRow { r -> onSubmit(Attempt(correct = r != Rating.AGAIN, close = r == Rating.HARD, rating = r)) }
        } else {
            FilledIconButton(
                onClick = {
                    if (listening) return@FilledIconButton
                    if (granted) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.size(96.dp).scale(if (listening) scale else 1f),
            ) { Icon(Icons.Rounded.Mic, stringResource(R.string.learn_pron_speak), Modifier.size(44.dp)) }
            Text(
                stringResource(if (listening) R.string.learn_pron_listening else R.string.learn_pron_tap),
                style = MaterialTheme.typography.bodyMedium,
            )
            heard?.let {
                Text(
                    if (it.isBlank()) stringResource(R.string.learn_pron_nothing) else stringResource(R.string.learn_pron_heard, it),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AnswerColors.Wrong,
                )
                Text(stringResource(R.string.learn_pron_tries, MAX_TRIES - tries), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { onSubmit(Attempt(correct = false, userAnswer = heard)) }) {
                Text(stringResource(R.string.learn_dont_know))
            }
        }
    }
}
