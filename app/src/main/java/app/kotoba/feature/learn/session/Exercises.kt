package app.kotoba.feature.learn.session

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import app.kotoba.feature.learn.logic.AnswerMatcher
import app.kotoba.feature.learn.logic.MatchResult

/** Shared scaffold: instruction, prompt area, then answer controls. */
@Composable
private fun ExerciseColumn(type: ExerciseType, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Instruction(type)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PromptCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth().heightIn(min = 140.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            content()
        }
    }
}

private fun optionState(option: CardEntity, picked: Long?, answer: CardEntity, answered: Boolean): OptionState = when {
    !answered || picked == null -> OptionState.IDLE
    option.id == answer.id -> OptionState.CORRECT
    option.id == picked -> OptionState.WRONG
    else -> OptionState.DIMMED
}

/** Options whose label duplicates the answer's label count as correct too. */
private fun isRight(option: CardEntity, answer: CardEntity, byTerm: Boolean) =
    option.id == answer.id || if (byTerm) option.term == answer.term else option.translation == answer.translation

@Composable
private fun OptionList(
    step: PreparedStep,
    answered: Boolean,
    showTerms: Boolean,
    onSubmit: (Attempt) -> Unit,
) {
    val haptics = rememberHaptics()
    val showReading = LocalUserSettings.current.showReading
    var picked by rememberSaveable(step.index) { mutableStateOf<Long?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        step.options.forEach { option ->
            OptionButton(
                text = if (showTerms) option.term else option.translation,
                sub = if (showTerms && showReading && answered) option.reading.takeIf { it.isNotBlank() } else null,
                state = optionState(option, picked, step.card, answered),
                enabled = !answered,
                onClick = {
                    picked = option.id
                    val right = isRight(option, step.card, showTerms)
                    if (right) haptics.success() else haptics.error()
                    onSubmit(
                        Attempt(
                            correct = right,
                            confusedWithCardId = if (right) null else option.id,
                            userAnswer = if (showTerms) option.term else option.translation,
                        ),
                    )
                },
            )
        }
    }
}

/** Multiple choice in both directions. */
@Composable
fun ChoiceExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String) -> Unit) {
    val reverse = step.exercise == ExerciseType.REVERSE_MULTIPLE_CHOICE
    val settings = LocalUserSettings.current
    LaunchedEffect(step.index) { if (!reverse && settings.autoPlayAudio) onSpeak(step.card.term) }
    ExerciseColumn(step.exercise) {
        PromptCard {
            if (reverse) MeaningPrompt(step.card) else TermHeadline(step.card, onSpeak = { onSpeak(step.card.term) })
        }
        OptionList(step, answered, showTerms = reverse, onSubmit = onSubmit)
    }
}

/** Fill in the blank: choose the word that completes the sentence. */
@Composable
fun FillBlankExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit) {
    val sentence = step.sentence
    ExerciseColumn(step.exercise) {
        PromptCard {
            if (sentence != null) {
                Text(
                    sentenceAnnotated(sentence, MaterialTheme.colorScheme.primary, blank = !answered),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                if (sentence.translation.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(sentence.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                MeaningPrompt(step.card)
            }
        }
        OptionList(step, answered, showTerms = true, onSubmit = onSubmit)
    }
}

/** Sentence with the word highlighted: pick its meaning. */
@Composable
fun ContextQuizExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String) -> Unit) {
    val sentence = step.sentence
    ExerciseColumn(step.exercise) {
        PromptCard {
            if (sentence != null) {
                Text(
                    sentenceAnnotated(sentence, MaterialTheme.colorScheme.primary, blank = false),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                if (sentence.cloze == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(step.card.term, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                FilledTonalIconButton(onClick = { onSpeak(sentence.text) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen)) }
                if (answered && sentence.translation.isNotBlank()) {
                    Text(sentence.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                TermHeadline(step.card)
            }
        }
        OptionList(step, answered, showTerms = false, onSubmit = onSubmit)
    }
}

/** Hear the word, then pick it (or type it). */
@Composable
fun ListeningExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String, Boolean) -> Unit) {
    var typing by rememberSaveable(step.index) { mutableStateOf(false) }
    LaunchedEffect(step.index) { onSpeak(step.card.term, false) }
    ExerciseColumn(step.exercise) {
        PromptCard {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onSpeak(step.card.term, false) }, modifier = Modifier.size(84.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen), Modifier.size(40.dp))
                }
                FilledTonalIconButton(onClick = { onSpeak(step.card.term, true) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SlowMotionVideo, stringResource(R.string.learn_slow))
                }
            }
            if (answered) {
                Spacer(Modifier.height(8.dp))
                TermHeadline(step.card)
            }
        }
        if (typing || step.options.isEmpty()) {
            TypedAnswer(step, answered, onSubmit, check = { input -> SessionViewModel.check(input, step.card) })
        } else {
            OptionList(step, answered, showTerms = true, onSubmit = onSubmit)
            if (!answered) {
                TextButton(onClick = { typing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Rounded.Keyboard, null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.learn_type_instead))
                }
            }
        }
    }
}

/** Translation shown → type the word. Accepts readings (kana/pinyin). */
@Composable
fun TypingExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit) {
    ExerciseColumn(step.exercise) {
        PromptCard { MeaningPrompt(step.card) }
        TypedAnswer(step, answered, onSubmit, check = { input -> SessionViewModel.check(input, step.card) })
    }
}

/** Text field + check / don't know, shared by typed exercises. */
@Composable
private fun TypedAnswer(
    step: PreparedStep,
    answered: Boolean,
    onSubmit: (Attempt) -> Unit,
    check: (String) -> MatchResult,
    usedHint: Boolean = false,
    prefill: String = "",
) {
    val haptics = rememberHaptics()
    var text by rememberSaveable(step.index) { mutableStateOf(prefill) }
    LaunchedEffect(prefill) { if (prefill.length > text.length) text = prefill }
    val focus = remember { FocusRequester() }
    LaunchedEffect(step.index) { runCatching { focus.requestFocus() } }
    val lang = StudyLanguage.fromCode(step.card.language)
    val submit = {
        if (text.isNotBlank() && !answered) {
            val r = check(text)
            if (r == MatchResult.WRONG) haptics.error() else haptics.success()
            onSubmit(
                Attempt(
                    correct = r != MatchResult.WRONG,
                    close = r == MatchResult.CLOSE,
                    usedHint = usedHint,
                    typed = true,
                    answerLength = text.trim().length,
                    userAnswer = text.trim(),
                ),
            )
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = !answered,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = {
            Text(
                stringResource(
                    when (lang) {
                        StudyLanguage.JA -> R.string.learn_type_hint_ja
                        StudyLanguage.ZH -> R.string.learn_type_hint_zh
                        else -> R.string.learn_type_hint
                    },
                ),
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
    if (!answered) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onSubmit(Attempt(correct = false, typed = true, userAnswer = text.trim().ifBlank { null })) },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(stringResource(R.string.learn_dont_know)) }
            Button(onClick = submit, enabled = text.isNotBlank(), modifier = Modifier.weight(1f).height(52.dp)) {
                Text(stringResource(R.string.learn_check))
            }
        }
    }
}

/** Dictation with letter hints. Revealing the whole word counts as a miss. */
@Composable
fun SpellingExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String, Boolean) -> Unit) {
    val term = step.card.term
    var revealed by rememberSaveable(step.index) { mutableIntStateOf(0) }
    LaunchedEffect(step.index) { onSpeak(term, false) }
    ExerciseColumn(step.exercise) {
        PromptCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { onSpeak(term, false) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen), Modifier.size(32.dp))
                }
                FilledTonalIconButton(onClick = { onSpeak(term, true) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.SlowMotionVideo, stringResource(R.string.learn_slow))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(step.card.translation, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            LetterSlots(term, if (answered) term.length else revealed)
        }
        if (!answered) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (revealed < term.length) revealed++ }) { Text(stringResource(R.string.learn_hint_letter)) }
                TextButton(onClick = {
                    revealed = term.length
                    onSubmit(Attempt(correct = false, usedHint = true, typed = true))
                }) { Text(stringResource(R.string.learn_reveal)) }
            }
        }
        TypedAnswer(
            step, answered, onSubmit,
            check = { input -> SessionViewModel.check(input, step.card) },
            usedHint = revealed > 0,
            prefill = term.take(revealed),
        )
    }
}

@Composable
private fun LetterSlots(term: String, revealed: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        term.forEachIndexed { i, c ->
            if (c.isWhitespace()) {
                Spacer(Modifier.size(12.dp))
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 36.dp),
                ) {
                    Text(
                        if (i < revealed) c.toString() else " ",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Tap tiles in order. Used by word scramble (letters/characters) and sentence builder (tokens). */
@Composable
private fun TileArranger(
    stepIndex: Int,
    pieces: List<String>,
    answered: Boolean,
    onComplete: (List<String>) -> Unit,
) {
    val haptics = rememberHaptics()
    // Indices into [pieces] so duplicate letters/words stay distinct.
    var chosen by rememberSaveable(stepIndex) { mutableStateOf(listOf<Int>()) }
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        FlowRow(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chosen.forEachIndexed { pos, idx ->
                Tile(pieces[idx], enabled = !answered, filled = true) {
                    haptics.tick()
                    chosen = chosen.toMutableList().also { it.removeAt(pos) }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pieces.forEachIndexed { idx, p ->
            val used = idx in chosen
            Tile(p, enabled = !answered && !used, filled = false, ghost = used) {
                haptics.tick()
                chosen = chosen + idx
            }
        }
    }
    if (!answered) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { if (chosen.isNotEmpty()) chosen = chosen.dropLast(1) }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, null)
            }
            Button(
                onClick = { onComplete(chosen.map { pieces[it] }) },
                enabled = chosen.size == pieces.size,
                modifier = Modifier.weight(2f).height(52.dp),
            ) { Text(stringResource(R.string.learn_check)) }
        }
    }
}

@Composable
private fun Tile(text: String, enabled: Boolean, filled: Boolean, ghost: Boolean = false, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = if (filled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            disabledContainerColor = if (ghost) MaterialTheme.colorScheme.surfaceVariant else if (filled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Text(
            if (ghost) " ".repeat(text.length.coerceAtMost(3)) else text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun ScrambleExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit) {
    ExerciseColumn(step.exercise) {
        PromptCard {
            MeaningPrompt(step.card)
            if (answered) TermHeadline(step.card)
        }
        TileArranger(step.index, step.letters, answered) { pieces ->
            val built = pieces.joinToString("")
            val target = step.card.term.filter { !it.isWhitespace() }
            val ok = AnswerMatcher.normalize(built, stripDiacritics = false) == AnswerMatcher.normalize(target, stripDiacritics = false)
            onSubmit(Attempt(correct = ok, userAnswer = built))
        }
    }
}

@Composable
fun SentenceBuilderExercise(step: PreparedStep, answered: Boolean, onSubmit: (Attempt) -> Unit, onSpeak: (String) -> Unit) {
    val sentence = step.sentence ?: return
    val lang = StudyLanguage.fromCode(step.card.language)
    val sep = if (lang.usesSpaces) " " else ""
    ExerciseColumn(step.exercise) {
        PromptCard {
            if (sentence.translation.isNotBlank()) {
                Text(sentence.translation, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.learn_builder_uses, step.card.term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (answered) {
                Spacer(Modifier.height(8.dp))
                Text(sentence.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                FilledTonalIconButton(onClick = { onSpeak(sentence.text) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen)) }
            }
        }
        TileArranger(step.index, step.shuffledTokens, answered) { pieces ->
            // Punctuation/spacing-insensitive so "Hello , world" == "Hello, world!".
            val ok = AnswerMatcher.normalize(pieces.joinToString(""), stripDiacritics = false) ==
                AnswerMatcher.normalize(sentence.tokens.joinToString(""), stripDiacritics = false)
            onSubmit(Attempt(correct = ok, userAnswer = pieces.joinToString(sep)))
        }
    }
}

/**
 * Translate an example sentence. With AI configured the answer is graded by the model;
 * otherwise the reference is revealed and the learner grades themselves.
 */
@Composable
fun TranslationExercise(
    step: PreparedStep,
    answered: Boolean,
    aiAvailable: Boolean,
    grade: TranslationGradeUi,
    onGrade: (String) -> Unit,
    onSubmit: (Attempt) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val sentence = step.sentence ?: return
    var text by rememberSaveable(step.index) { mutableStateOf("") }
    var revealed by rememberSaveable(step.index) { mutableStateOf(false) }
    val selfAssess = !aiAvailable || grade.failed
    ExerciseColumn(step.exercise) {
        PromptCard {
            Text(
                sentenceAnnotated(sentence, MaterialTheme.colorScheme.primary, blank = false),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            FilledTonalIconButton(onClick = { onSpeak(sentence.text) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen)) }
            Text("${step.card.term} — ${step.card.translation}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !answered && !grade.loading && !revealed,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.learn_translation_hint)) },
            minLines = 2,
            shape = RoundedCornerShape(16.dp),
        )
        if (grade.failed) {
            Text(stringResource(R.string.learn_ai_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            answered -> Unit
            grade.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.learn_ai_checking))
            }
            !selfAssess -> Button(onClick = { onGrade(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.learn_ai_check))
            }
            !revealed -> Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.learn_show_answer))
            }
            else -> {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        sentence.translation.ifBlank { stringResource(R.string.learn_no_reference) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Text(stringResource(R.string.learn_self_assess), style = MaterialTheme.typography.labelLarge)
                SelfAssessRow(onRate = { r ->
                    onSubmit(Attempt(correct = r != Rating.AGAIN, close = r == Rating.HARD, rating = r, userAnswer = text.ifBlank { null }))
                })
            }
        }
    }
}

/** Wrong / Almost / Correct for self-graded exercises. */
@Composable
fun SelfAssessRow(onRate: (Rating) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            Rating.AGAIN to R.string.learn_self_wrong,
            Rating.HARD to R.string.learn_self_almost,
            Rating.GOOD to R.string.learn_self_right,
        ).forEach { (r, label) ->
            val c = when (r) {
                Rating.AGAIN -> AnswerColors.Wrong
                Rating.HARD -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
                else -> AnswerColors.Correct
            }
            Button(
                onClick = { onRate(r) },
                modifier = Modifier.weight(1f).height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.copy(alpha = 0.16f), contentColor = c),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}
