package com.shadowing.app.engine

import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.MicRecorder
import com.shadowing.app.domain.model.PronunciationResult
import com.shadowing.app.domain.model.RecordingOutcome
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.domain.model.ShadowingSettings
import com.shadowing.app.domain.model.WordAssessment
import com.shadowing.app.evaluation.PronunciationEvaluator
import com.shadowing.app.tts.EarconType
import com.shadowing.app.tts.HighlightRange
import com.shadowing.app.tts.SpeechSynthesizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShadowingEngineTest {

    private class FakeTts : SpeechSynthesizer {
        override val activeWordRange = MutableStateFlow<HighlightRange?>(null)
        val spokenLog = mutableListOf<Pair<String, Float>>()
        val earcons = mutableListOf<EarconType>()
        var pauseGate: CompletableDeferred<Unit>? = null

        override suspend fun speak(text: String, rate: Float) {
            spokenLog.add(text to rate)
            pauseGate?.await()
        }

        override suspend fun playEarcon(type: EarconType) {
            earcons.add(type)
        }

        override fun stop() {
            activeWordRange.value = null
        }
    }

    private class FakeRecorder : MicRecorder {
        val queuedOutcomes = ArrayDeque<RecordingOutcome>()
        var blockGate: CompletableDeferred<RecordingOutcome>? = null
        var recordCallCount = 0

        override suspend fun recordUtterance(
            trailingSilenceMs: Long,
            maxDurationMs: Long
        ): RecordingOutcome {
            recordCallCount++
            blockGate?.let { return it.await() }
            return if (queuedOutcomes.isNotEmpty()) {
                queuedOutcomes.removeFirst()
            } else {
                RecordingOutcome.Captured(ByteArray(32), 1000L)
            }
        }
    }

    private class FakeEvaluator : PronunciationEvaluator {
        val queuedResults = ArrayDeque<Result<PronunciationResult>>()
        val evaluatedReferences = mutableListOf<String>()

        override suspend fun evaluate(
            wavBytes: ByteArray,
            referenceText: String
        ): Result<PronunciationResult> {
            evaluatedReferences.add(referenceText)
            return if (queuedResults.isNotEmpty()) {
                queuedResults.removeFirst()
            } else {
                Result.success(
                    PronunciationResult(
                        recognizedText = referenceText,
                        accuracyScore = 90,
                        fluencyScore = 90,
                        completenessScore = 100,
                        words = emptyList()
                    )
                )
            }
        }
    }

    @Test
    fun test_belowThresholdScore_announcesFeedbackAndDoesNotAdvanceSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val tts = FakeTts()
        val recorder = FakeRecorder()
        val evaluator = FakeEvaluator()
        val settings = MutableStateFlow(ShadowingSettings(accuracyThreshold = 80))

        // First attempt fails (70%), second attempt blocks in recorder so we can inspect state
        evaluator.queuedResults.add(
            Result.success(
                PronunciationResult(
                    recognizedText = "Hello world.",
                    accuracyScore = 70,
                    fluencyScore = 75,
                    completenessScore = 100,
                    words = listOf(
                        WordAssessment("Hello", 90, "None"),
                        WordAssessment("world", 50, "Mispronunciation")
                    )
                )
            )
        )
        val secondAttemptGate = CompletableDeferred<RecordingOutcome>()
        recorder.queuedOutcomes.add(RecordingOutcome.Captured(ByteArray(16), 800L))

        var callIdx = 0
        val gatedRecorder = object : MicRecorder {
            override suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome {
                callIdx++
                return if (callIdx == 1) RecordingOutcome.Captured(ByteArray(16), 800L)
                else secondAttemptGate.await()
            }
        }

        val engine = ShadowingEngine(scope, tts, gatedRecorder, evaluator, settings)
        engine.loadSession(1L, "Test Doc", listOf("Hello world.", "Second sentence."))
        engine.dispatch(ShadowingAction.StartOrResume)
        advanceUntilIdle()

        assertEquals(0, engine.uiState.value.currentSentenceIndex)
        assertEquals(70, engine.uiState.value.lastScore)
        assertTrue(engine.uiState.value.failedWords.contains("world"))
        assertTrue(tts.spokenLog.any { it.first.contains("70") && it.first.contains("world") })
        engine.dispatch(ShadowingAction.Pause)
    }

    @Test
    fun test_aboveThresholdScore_advancesToNextSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val tts = FakeTts()
        val evaluator = FakeEvaluator()
        val settings = MutableStateFlow(ShadowingSettings(accuracyThreshold = 80))

        evaluator.queuedResults.add(
            Result.success(
                PronunciationResult(
                    recognizedText = "First sentence.",
                    accuracyScore = 85,
                    fluencyScore = 88,
                    completenessScore = 100,
                    words = listOf(
                        WordAssessment("First", 85, "None"),
                        WordAssessment("sentence", 85, "None")
                    )
                )
            )
        )

        val secondSentenceGate = CompletableDeferred<RecordingOutcome>()
        var callIdx = 0
        val recorder = object : MicRecorder {
            override suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome {
                callIdx++
                return if (callIdx == 1) RecordingOutcome.Captured(ByteArray(16), 800L)
                else secondSentenceGate.await()
            }
        }

        val engine = ShadowingEngine(scope, tts, recorder, evaluator, settings)
        engine.loadSession(1L, "Test Doc", listOf("First sentence.", "Second sentence."))
        engine.dispatch(ShadowingAction.StartOrResume)
        advanceUntilIdle()

        assertEquals(1, engine.uiState.value.currentSentenceIndex)
        assertEquals("Second sentence.", engine.uiState.value.currentSentence)
        engine.dispatch(ShadowingAction.Pause)
    }

    @Test
    fun test_punctuationAttachedWord_matchesAzureWordAndTriggersDrillAfterTwoFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val tts = FakeTts()
        val evaluator = FakeEvaluator()
        val settings = MutableStateFlow(
            ShadowingSettings(
                accuracyThreshold = 80,
                drillFailThreshold = 2,
                drillSpeechRate = 0.65f
            )
        )

        val failResult = Result.success(
            PronunciationResult(
                recognizedText = "He studied thoroughly.",
                accuracyScore = 65,
                fluencyScore = 70,
                completenessScore = 100,
                words = listOf(
                    WordAssessment("He", 95, "None"),
                    WordAssessment("studied", 90, "None"),
                    // Note Azure returns normalized "thoroughly" without trailing period
                    WordAssessment("thoroughly", 45, "Mispronunciation")
                )
            )
        )
        val drillPassResult = Result.success(
            PronunciationResult(
                recognizedText = "thoroughly",
                accuracyScore = 88,
                fluencyScore = 90,
                completenessScore = 100,
                words = listOf(WordAssessment("thoroughly", 88, "None"))
            )
        )
        evaluator.queuedResults.add(failResult) // Attempt 1 fails on thoroughly
        evaluator.queuedResults.add(failResult) // Attempt 2 fails on thoroughly -> triggers Word Drill!
        evaluator.queuedResults.add(drillPassResult) // Word Drill passes!

        val fourthAttemptGate = CompletableDeferred<RecordingOutcome>()
        var callIdx = 0
        val recorder = object : MicRecorder {
            override suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome {
                callIdx++
                return if (callIdx <= 3) RecordingOutcome.Captured(ByteArray(16), 800L)
                else fourthAttemptGate.await()
            }
        }

        val engine = ShadowingEngine(scope, tts, recorder, evaluator, settings)
        engine.loadSession(1L, "Test Doc", listOf("He studied thoroughly.", "Next sentence."))
        engine.dispatch(ShadowingAction.StartOrResume)
        advanceUntilIdle()

        // Verify the micro-drill spoke "thoroughly" at 0.65f rate and evaluated single word "thoroughly"
        assertTrue(tts.spokenLog.any { it.first == "thoroughly" && it.second == 0.65f })
        assertTrue(evaluator.evaluatedReferences.contains("thoroughly"))
        // And after passing the drill, it returned to the full sentence at index 0
        assertEquals(0, engine.uiState.value.currentSentenceIndex)
        engine.dispatch(ShadowingAction.Pause)
    }

    @Test
    fun test_steeringWheelSkipNext_unconditionallyAdvancesEvenDuringWordDrill() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val tts = FakeTts()
        val evaluator = FakeEvaluator()
        val settings = MutableStateFlow(ShadowingSettings(accuracyThreshold = 80, drillFailThreshold = 2))

        val failResult = Result.success(
            PronunciationResult(
                recognizedText = "Hard word.",
                accuracyScore = 50,
                fluencyScore = 60,
                completenessScore = 100,
                words = listOf(WordAssessment("Hard", 40, "Mispronunciation"))
            )
        )
        evaluator.queuedResults.add(failResult)
        evaluator.queuedResults.add(failResult)

        val drillGate = CompletableDeferred<RecordingOutcome>()
        val secondSentenceGate = CompletableDeferred<RecordingOutcome>()
        var callIdx = 0
        val recorder = object : MicRecorder {
            override suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome {
                callIdx++
                return when (callIdx) {
                    1, 2 -> RecordingOutcome.Captured(ByteArray(16), 500L)
                    3 -> drillGate.await() // Pause inside Word Drill listening
                    else -> secondSentenceGate.await()
                }
            }
        }

        val engine = ShadowingEngine(scope, tts, recorder, evaluator, settings)
        engine.loadSession(1L, "Test Doc", listOf("Hard word.", "Easy second sentence."))
        engine.dispatch(ShadowingAction.StartOrResume)
        advanceUntilIdle()

        assertEquals(EnginePhase.WORD_DRILL, engine.uiState.value.phase)

        // Driver presses Steering-Wheel NEXT (>>|)
        engine.dispatch(ShadowingAction.SkipNext)
        advanceUntilIdle()

        assertEquals(1, engine.uiState.value.currentSentenceIndex)
        assertEquals("Easy second sentence.", engine.uiState.value.currentSentence)
        engine.dispatch(ShadowingAction.Pause)
    }

    @Test
    fun test_gpsAudioFocusLossDuringListening_abortsRecordingAndReplaysOnFocusGain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val tts = FakeTts()
        val evaluator = FakeEvaluator()
        val settings = MutableStateFlow(ShadowingSettings(accuracyThreshold = 80))

        val micGate = CompletableDeferred<RecordingOutcome>()
        val recorder = FakeRecorder().apply { blockGate = micGate }

        val engine = ShadowingEngine(scope, tts, recorder, evaluator, settings)
        engine.loadSession(1L, "Test Doc", listOf("Navigate safely today."))
        engine.dispatch(ShadowingAction.StartOrResume)
        advanceUntilIdle()

        assertEquals(EnginePhase.LISTENING, engine.uiState.value.phase)
        assertEquals(1, tts.spokenLog.count { it.first == "Navigate safely today." })

        // Google Maps speaks a turn prompt -> AUDIOFOCUS_LOSS_TRANSIENT
        engine.dispatch(ShadowingAction.GpsAudioFocusLost)
        advanceUntilIdle()

        assertEquals(EnginePhase.PAUSED, engine.uiState.value.phase)
        assertEquals(0, evaluator.evaluatedReferences.size) // Never sent GPS audio to Azure!

        // Google Maps finishes -> AUDIOFOCUS_GAIN
        engine.dispatch(ShadowingAction.GpsAudioFocusRegained)
        advanceUntilIdle()

        assertEquals(EnginePhase.LISTENING, engine.uiState.value.phase)
        assertEquals(2, tts.spokenLog.count { it.first == "Navigate safely today." })
        engine.dispatch(ShadowingAction.Pause)
    }
}
