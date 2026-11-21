# Hands-Free Driving English Shadowing App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app (Kotlin / Jetpack Compose / Media3) for English pronunciation shadowing that works hands-free over wired USB Android Auto with steering-wheel media buttons and on-screen with real-time TTS word bolding.

**Architecture:** A foreground `MediaLibraryService` (`ShadowingMediaLibraryService`) wraps a coroutine-driven state machine (`ShadowingEngine`) via a Media3 `SimpleBasePlayer` (`ShadowingSessionPlayer`) so USB Android Auto displays live sentence/score metadata and routes steering-wheel Next (Skip), Previous (Repeat), and Play/Pause buttons. TTS plays over `USAGE_MEDIA` while emitting real-time character ranges (`onRangeStart`) to bold the spoken word on the phone screen; user speech is recorded at 16 kHz mono PCM from the phone's wideband mic with adaptive noise-floor VAD and scored at word/phoneme granularity by Azure AI Speech Pronunciation Assessment REST API (with an offline demo scorer fallback).

**Tech Stack:** Kotlin 2.0, Jetpack Compose (Material 3), AndroidX Media3 (`media3-session`, `media3-common`), Room (`2.6.1`), DataStore Preferences, OkHttp 4, kotlinx-serialization-json, Jsoup, PdfBox-Android, JUnit 4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-21-shadowing-android-auto-design.md`

## Global Constraints

- Minimum SDK: `minSdk = 26` (required for `UtteranceProgressListener.onRangeStart`), `targetSdk = 35`, `compileSdk = 35`, Java/Kotlin toolchain `21`.
- Package namespace: `com.shadowing.app`.
- Microphone capture format: `16000 Hz`, `16-bit PCM mono` (`AudioFormat.CHANNEL_IN_MONO`, `AudioFormat.ENCODING_PCM_16BIT`) using `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
- VAD timings: `200 ms` ambient noise-floor calibration, `3500 ms` initial speech onset timeout (`2.2x` calibrated noise RMS floor), `1200 ms` trailing silence for sentences, `800 ms` trailing silence for single-word micro-drills, max recording duration `max(5000L, ttsDurationMs * 2)`.
- Adaptive word micro-drill defaults: trigger at `2` consecutive failures on the same normalized word, drill playback speed `0.65f`, exit drill back to sentence on pass (`wordAccuracy >= accuracyThreshold`) or after `3` drill attempts.
- Steering-wheel `Next (>>|)` is an unconditional escape hatch from any active or micro-drill state to the next sentence.
- Transient audio focus loss (`AUDIOFOCUS_LOSS_TRANSIENT` or `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` from GPS navigation) immediately aborts open mic recording and restarts the current sentence when `AUDIOFOCUS_GAIN` returns.

## Review Focus

1. **Punctuation-attached words in sentence text vs. normalized words from Azure (`"thoroughly."` vs `"thoroughly"`)**: Consecutive word failure tracking and drill target matching must strip leading/trailing punctuation and compare case-insensitively so sentence-final words still trigger the micro-drill after 2 failures (tested in Task 4).
2. **Out-of-bounds or inverted `onRangeStart(start, end)` character offsets from OEM TTS engines**: The word-bolding range clamping logic must clamp `start` and `end` to `0..text.length` with `start <= end` so Compose `AnnotatedString` never throws `IllegalArgumentException` (tested in Task 3).
3. **GPS navigation prompt (`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`) arriving mid-recording**: `ShadowingEngine` must immediately cancel the active mic capture (never sending GPS voice to Azure) and replay the current sentence when `AUDIOFOCUS_GAIN` fires (tested in Task 4).
4. **Continuous highway cabin noise without driver speech**: Adaptive VAD must calibrate against the initial 200 ms high noise floor and return `VadResult.NoSpeechDetected` at 3500 ms rather than recording forever (tested in Task 1).
5. **Abbreviations and decimals inside imported text (`"Dr. Smith paid $3.50 in the U.S. today."`)**: `SentenceSegmenter` must keep common abbreviations and decimal numbers inside a single sentence rather than splitting into unpronounceable 1-word fragments (tested in Task 5).

---

## File Structure

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`: Gradle configuration and dependencies.
- `app/src/main/AndroidManifest.xml`: Permissions (`RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE`), `ShadowingMediaLibraryService`, and Android Auto `com.google.android.gms.car.application` metadata.
- `app/src/main/res/xml/automotive_app_desc.xml`: Declares `<automotiveApp><uses name="media"/></automotiveApp>` for USB Android Auto projection.
- `app/src/main/java/com/shadowing/app/domain/model/ShadowingModels.kt`: Immutable domain models (`PracticeSentence`, `WordAssessment`, `PhonemeAssessment`, `PronunciationResult`, `ShadowingSettings`, `EnginePhase`, `ShadowingUiState`, `ShadowingAction`).
- `app/src/main/java/com/shadowing/app/audio/WavEncoder.kt`: Pure Kotlin 16 kHz mono 16-bit PCM to 44-byte RIFF WAV encoder.
- `app/src/main/java/com/shadowing/app/audio/AdaptiveVadDetector.kt`: Pure Kotlin streaming RMS frame processor implementing 200 ms noise calibration, speech onset detection, and trailing-silence endpointing.
- `app/src/main/java/com/shadowing/app/audio/PhoneMicRecorder.kt`: Android `AudioRecord` implementation of `MicRecorder` feeding frames into `AdaptiveVadDetector` and returning `WavEncoder` bytes.
- `app/src/main/java/com/shadowing/app/evaluation/PronunciationEvaluator.kt`: Interface + `AzurePronunciationEvaluator` (Azure REST API client & JSON parser) + `MockPronunciationEvaluator` (deterministic/configurable demo evaluator).
- `app/src/main/java/com/shadowing/app/tts/WordRangeTracker.kt`: Pure helper that clamps TTS `onRangeStart(start, end)` offsets, maps fallback word timers, and produces word-bolding spans.
- `app/src/main/java/com/shadowing/app/tts/TtsController.kt`: Interface `SpeechSynthesizer` + Android `TextToSpeech` implementation with `onRangeStart` emissions and `ToneGenerator` earcons.
- `app/src/main/java/com/shadowing/app/engine/ShadowingEngine.kt`: Coroutine state machine driving the shadowing loop, threshold gating, adaptive 0.65x word micro-drills, steering-wheel actions, and GPS audio-focus interruption recovery.
- `app/src/main/java/com/shadowing/app/content/SentenceSegmenter.kt`: Splits raw text into clean, speakable shadowing sentences while preserving abbreviations and decimals.
- `app/src/main/java/com/shadowing/app/content/DocumentIngestor.kt`: Extracts text from pasted strings, public Web URLs (Jsoup), `.txt`, `.epub` (ZIP + OPF spine + Jsoup), and `.pdf` (PdfBox-Android).
- `app/src/main/java/com/shadowing/app/data/ShadowingDatabase.kt`: Room entities (`DocumentEntity`, `SentenceEntity`), `ShadowingDao`, and DataStore `SettingsRepository`.
- `app/src/main/java/com/shadowing/app/car/ShadowingSessionPlayer.kt`: Media3 `SimpleBasePlayer` translating `play()`, `pause()`, `seekToNext()`, `seekToPrevious()` into `ShadowingAction` calls and publishing live `MediaMetadata`.
- `app/src/main/java/com/shadowing/app/car/ShadowingMediaLibraryService.kt`: Foreground `MediaLibraryService` managing Android Auto connection and audio focus listener.
- `app/src/main/java/com/shadowing/app/ui/ShadowingScreens.kt` & `MainActivity.kt`: Jetpack Compose UI including real-time bold word highlighting (`ShadowingHighlightedSentence`), Big-Button Practice/Driving screen, Library/Import screen, and Settings screen.

---

### Task 1: Project Scaffolding, Domain Models & Adaptive Car-Cabin VAD Recorder

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/com/shadowing/app/domain/model/ShadowingModels.kt`
- Create: `app/src/main/java/com/shadowing/app/audio/WavEncoder.kt`
- Create: `app/src/main/java/com/shadowing/app/audio/AdaptiveVadDetector.kt`
- Create: `app/src/main/java/com/shadowing/app/audio/PhoneMicRecorder.kt`
- Test: `app/src/test/java/com/shadowing/app/audio/AdaptiveVadAndWavTest.kt`

**Interfaces:**
- Consumes: none
- Produces:
  - `data class ShadowingSettings(val accuracyThreshold: Int = 80, val speechRate: Float = 1.0f, val drillFailThreshold: Int = 2, val maxDrillAttempts: Int = 3, val drillSpeechRate: Float = 0.65f, val azureApiKey: String = "", val azureRegion: String = "eastus", val useMockEvaluator: Boolean = true)`
  - `data class PhonemeAssessment(val phoneme: String, val accuracyScore: Int)`
  - `data class WordAssessment(val word: String, val accuracyScore: Int, val errorType: String, val phonemes: List<PhonemeAssessment> = emptyList())`
  - `data class PronunciationResult(val recognizedText: String, val accuracyScore: Int, val fluencyScore: Int, val completenessScore: Int, val words: List<WordAssessment>)`
  - `sealed interface RecordingOutcome { data class Captured(val wavBytes: ByteArray, val durationMs: Long) : RecordingOutcome; data object NoSpeechDetected : RecordingOutcome }`
  - `interface MicRecorder { suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome }`
  - `object WavEncoder { fun encodePcm16MonoToWav(pcmSamples: ShortArray, sampleRate: Int = 16000): ByteArray }`
  - `class AdaptiveVadDetector(val sampleRate: Int = 16000, val calibrationMs: Long = 200L, val onsetTimeoutMs: Long = 3500L, val trailingSilenceMs: Long = 1200L, val maxDurationMs: Long = 8000L)`

- [ ] **Step 1: Ensure Android SDK & Gradle wrapper exist and scaffold project files**

Install or locate Android SDK (`platforms;android-35`, `build-tools;35.0.0`) via `android` CLI if not present, and create `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `ShadowingModels.kt`.

- [ ] **Step 2: Write the failing unit tests for `WavEncoder` and `AdaptiveVadDetector`**

Create `app/src/test/java/com/shadowing/app/audio/AdaptiveVadAndWavTest.kt`:

```kotlin
package com.shadowing.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdaptiveVadAndWavTest {

    @Test
    fun test_encodePcm16MonoToWav_writesValid44ByteHeaderAndLittleEndianPayload() {
        val samples = shortArrayOf(1000, -1000, 500, -500)
        val wav = WavEncoder.encodePcm16MonoToWav(samples, sampleRate = 16000)

        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28)) // byteRate = 16000 * 1 * 2
        assertEquals(8, buffer.getInt(40)) // data chunk size
        assertEquals(1000.toShort(), buffer.getShort(44))
    }

    @Test
    fun test_steadyHighNoiseFloorWithoutSpeech_emitsNoSpeechDetectedAtTimeout() {
        val vad = AdaptiveVadDetector(
            sampleRate = 16000,
            calibrationMs = 200L,
            onsetTimeoutMs = 3500L,
            trailingSilenceMs = 1200L,
            maxDurationMs = 8000L
        )
        val frame20ms = ShortArray(320) { 600 } // steady highway cabin hum
        var state = AdaptiveVadDetector.StepState.CONTINUE
        var elapsedMs = 0L
        while (state == AdaptiveVadDetector.StepState.CONTINUE && elapsedMs < 5000L) {
            state = vad.processFrame(frame20ms)
            elapsedMs += 20L
        }
        assertEquals(AdaptiveVadDetector.StepState.NO_SPEECH_TIMEOUT, state)
        assertEquals(3700L, elapsedMs) // 200ms calibration + 3500ms onset timeout
    }

    @Test
    fun test_speechAboveNoiseFloorFollowedByTrailingSilence_completesUtterance() {
        val vad = AdaptiveVadDetector(
            sampleRate = 16000,
            calibrationMs = 200L,
            onsetTimeoutMs = 3500L,
            trailingSilenceMs = 1200L,
            maxDurationMs = 8000L
        )
        val noiseFrame = ShortArray(320) { 400 }
        val speechFrame = ShortArray(320) { 2500 }

        repeat(10) { vad.processFrame(noiseFrame) } // 200ms calibration
        repeat(25) { vad.processFrame(speechFrame) } // 500ms speech
        var state = AdaptiveVadDetector.StepState.CONTINUE
        repeat(60) { state = vad.processFrame(noiseFrame) } // 1200ms trailing silence

        assertEquals(AdaptiveVadDetector.StepState.UTTERANCE_COMPLETE, state)
        assertTrue(vad.getCapturedPcm().isNotEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.audio.AdaptiveVadAndWavTest"`
Expected: FAIL (classes `WavEncoder` and `AdaptiveVadDetector` not implemented).

- [ ] **Step 4: Implement `WavEncoder`, `AdaptiveVadDetector`, and `PhoneMicRecorder`**

Implement `WavEncoder.encodePcm16MonoToWav`, `AdaptiveVadDetector.processFrame(frame: ShortArray): StepState` (calibrating RMS for the first `calibrationMs`, triggering speech onset when `frameRms >= maxOf(noiseRms * 2.2, 350.0)`, and completing when post-onset silence reaches `trailingSilenceMs` or total time hits `maxDurationMs`), and `PhoneMicRecorder : MicRecorder`.

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.audio.AdaptiveVadAndWavTest"`
Expected: PASS (3 tests passing).

- [ ] **Step 6: Commit**

```powershell
git add .
git commit -m "feat: add Android project scaffold, domain models, WAV encoder, and adaptive car VAD"
```

---

### Task 2: Azure Pronunciation Assessment REST Client & Mock Evaluator

**Files:**
- Create: `app/src/main/java/com/shadowing/app/evaluation/PronunciationEvaluator.kt`
- Test: `app/src/test/java/com/shadowing/app/evaluation/PronunciationEvaluatorTest.kt`

**Interfaces:**
- Consumes: `PronunciationResult`, `WordAssessment`, `PhonemeAssessment`, `ShadowingSettings` from Task 1.
- Produces:
  - `interface PronunciationEvaluator { suspend fun evaluate(wavBytes: ByteArray, referenceText: String): Result<PronunciationResult> }`
  - `class AzurePronunciationEvaluator(private val httpClient: OkHttpClient, private val settingsProvider: suspend () -> ShadowingSettings) : PronunciationEvaluator`
  - `object AzurePronunciationProtocol { fun buildHeaderBase64(referenceText: String): String; fun parseResponseJson(jsonString: String): PronunciationResult }`
  - `class MockPronunciationEvaluator(var nextScoresByWord: Map<String, Int> = emptyMap(), var defaultScore: Int = 88) : PronunciationEvaluator`

- [ ] **Step 1: Write the failing unit tests for Azure header construction and JSON response parsing**

Create `app/src/test/java/com/shadowing/app/evaluation/PronunciationEvaluatorTest.kt`:

```kotlin
package com.shadowing.app.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PronunciationEvaluatorTest {

    @Test
    fun test_buildHeaderBase64_encodesReferenceTextAndPhonemeGranularity() {
        val encoded = AzurePronunciationProtocol.buildHeaderBase64("The phenomenon was thoroughly studied.")
        val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(decoded.contains("\"ReferenceText\":\"The phenomenon was thoroughly studied.\""))
        assertTrue(decoded.contains("\"Granularity\":\"Phoneme\""))
        assertTrue(decoded.contains("\"GradingSystem\":\"HundredMark\""))
    }

    @Test
    fun test_parseResponseJson_extractsSentenceWordAndPhonemeScores() {
        val sampleJson = """
            {
              "RecognitionStatus": "Success",
              "DisplayText": "The phenomenon was thoroughly studied.",
              "NBest": [
                {
                  "PronunciationAssessment": {
                    "AccuracyScore": 74.0,
                    "FluencyScore": 82.0,
                    "CompletenessScore": 100.0,
                    "PronScore": 76.4
                  },
                  "Words": [
                    {
                      "Word": "the",
                      "PronunciationAssessment": { "AccuracyScore": 95.0, "ErrorType": "None" },
                      "Phonemes": [
                        { "Phoneme": "dh", "PronunciationAssessment": { "AccuracyScore": 94.0 } },
                        { "Phoneme": "ah", "PronunciationAssessment": { "AccuracyScore": 96.0 } }
                      ]
                    },
                    {
                      "Word": "thoroughly",
                      "PronunciationAssessment": { "AccuracyScore": 52.0, "ErrorType": "Mispronunciation" },
                      "Phonemes": [
                        { "Phoneme": "th", "PronunciationAssessment": { "AccuracyScore": 45.0 } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = AzurePronunciationProtocol.parseResponseJson(sampleJson)
        assertEquals(74, result.accuracyScore)
        assertEquals(2, result.words.size)
        assertEquals("thoroughly", result.words[1].word)
        assertEquals(52, result.words[1].accuracyScore)
        assertEquals("Mispronunciation", result.words[1].errorType)
        assertEquals("th", result.words[1].phonemes.first().phoneme)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.evaluation.PronunciationEvaluatorTest"`
Expected: FAIL (`AzurePronunciationProtocol` not defined).

- [ ] **Step 3: Implement `PronunciationEvaluator`, `AzurePronunciationProtocol`, `AzurePronunciationEvaluator`, and `MockPronunciationEvaluator`**

Implement `AzurePronunciationProtocol` using `kotlinx.serialization.json.Json { ignoreUnknownKeys = true }`, `AzurePronunciationEvaluator` posting `audio/wav; codecs=audio/pcm; samplerate=16000` to `https://${settings.azureRegion}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US` with headers `Ocp-Apim-Subscription-Key` and `Pronunciation-Assessment`, plus `MockPronunciationEvaluator`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.evaluation.PronunciationEvaluatorTest"`
Expected: PASS (2 tests passing).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/shadowing/app/evaluation app/src/test/java/com/shadowing/app/evaluation
git commit -m "feat: add Azure Pronunciation Assessment REST evaluator and mock scorer"
```

---

### Task 3: TTS Controller with Real-Time Synchronized Word Bolding & Earcons

**Files:**
- Create: `app/src/main/java/com/shadowing/app/tts/WordRangeTracker.kt`
- Create: `app/src/main/java/com/shadowing/app/tts/TtsController.kt`
- Test: `app/src/test/java/com/shadowing/app/tts/WordRangeTrackerTest.kt`

**Interfaces:**
- Consumes: domain models from Task 1.
- Produces:
  - `data class HighlightRange(val start: Int, val end: Int)`
  - `object WordRangeTracker { fun clampRange(text: String, rawStart: Int, rawEnd: Int): HighlightRange?; fun extractWordRanges(text: String): List<HighlightRange> }`
  - `enum class EarconType { LISTEN_START, CAPTURED, PASS }`
  - `interface SpeechSynthesizer { val activeWordRange: StateFlow<HighlightRange?>; suspend fun speak(text: String, rate: Float); suspend fun playEarcon(type: EarconType); fun stop() }`
  - `class TtsController(context: Context) : SpeechSynthesizer`

- [ ] **Step 1: Write the failing unit tests for `WordRangeTracker`**

Create `app/src/test/java/com/shadowing/app/tts/WordRangeTrackerTest.kt`:

```kotlin
package com.shadowing.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordRangeTrackerTest {

    @Test
    fun test_highlightRange_clampsOutOfBoundsTtsOffsetsWithoutCrashing() {
        val sentence = "Practice while driving."
        val clamped = WordRangeTracker.clampRange(sentence, rawStart = -4, rawEnd = 99)
        assertEquals(HighlightRange(0, sentence.length), clamped)

        val inverted = WordRangeTracker.clampRange(sentence, rawStart = 12, rawEnd = 5)
        assertNull(inverted)
    }

    @Test
    fun test_extractWordRanges_identifiesEachWordCharacterSpanForSyncBolding() {
        val sentence = "Shadowing builds fluency."
        val ranges = WordRangeTracker.extractWordRanges(sentence)
        assertEquals(3, ranges.size)
        assertEquals("Shadowing", sentence.substring(ranges[0].start, ranges[0].end))
        assertEquals("builds", sentence.substring(ranges[1].start, ranges[1].end))
        assertEquals("fluency.", sentence.substring(ranges[2].start, ranges[2].end))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.tts.WordRangeTrackerTest"`
Expected: FAIL (`WordRangeTracker` not defined).

- [ ] **Step 3: Implement `WordRangeTracker` and `TtsController`**

Implement `WordRangeTracker` and `TtsController : SpeechSynthesizer`, hooking `UtteranceProgressListener.onRangeStart(utteranceId, start, end, frame)` to emit `WordRangeTracker.clampRange(currentText, start, end)` into `activeWordRange`, and clearing `activeWordRange` in `onDone`/`onStop`/`stop()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.tts.WordRangeTrackerTest"`
Expected: PASS (2 tests passing).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/shadowing/app/tts app/src/test/java/com/shadowing/app/tts
git commit -m "feat: add TTS word range tracker for real-time word bolding and earcon synthesizer"
```

---

### Task 4: Core Shadowing State Machine (Threshold Gating, Word Micro-Drill, Steering-Wheel & GPS Focus)

**Files:**
- Create: `app/src/main/java/com/shadowing/app/engine/ShadowingEngine.kt`
- Test: `app/src/test/java/com/shadowing/app/engine/ShadowingEngineTest.kt`

**Interfaces:**
- Consumes: `MicRecorder`, `RecordingOutcome` (Task 1), `PronunciationEvaluator` (Task 2), `SpeechSynthesizer`, `HighlightRange`, `EarconType` (Task 3).
- Produces:
  - `class ShadowingEngine(private val scope: CoroutineScope, private val tts: SpeechSynthesizer, private val recorder: MicRecorder, private val evaluator: PronunciationEvaluator, private val settingsFlow: StateFlow<ShadowingSettings>, private val onProgressSaved: suspend (documentId: Long, sentenceIndex: Int) -> Unit = { _, _ -> })`
  - `val uiState: StateFlow<ShadowingUiState>`
  - `fun loadSession(documentId: Long, title: String, sentences: List<String>, startIndex: Int = 0)`
  - `fun dispatch(action: ShadowingAction)` where `ShadowingAction` covers `StartOrResume`, `Pause`, `TogglePause`, `SkipNext`, `RepeatOrPrevious`, `GpsAudioFocusLost`, `GpsAudioFocusRegained`.

- [ ] **Step 1: Write the failing unit tests for `ShadowingEngine`**

Create `app/src/test/java/com/shadowing/app/engine/ShadowingEngineTest.kt` covering:
1. `test_belowThresholdScore_announcesFeedbackAndDoesNotAdvanceSentence`
2. `test_aboveThresholdScore_advancesToNextSentence`
3. `test_punctuationAttachedWord_matchesAzureWordAndTriggersDrillAfterTwoFails` (verifying `"thoroughly."` enters `EnginePhase.WORD_DRILL` at `0.65f` speech rate after 2 consecutive failures and returns to the full sentence once passed)
4. `test_steeringWheelSkipNext_unconditionallyAdvancesEvenDuringWordDrill`
5. `test_gpsAudioFocusLossDuringListening_abortsRecordingAndReplaysOnFocusGain`

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.engine.ShadowingEngineTest"`
Expected: FAIL (`ShadowingEngine` not defined).

- [ ] **Step 3: Implement `ShadowingEngine`**

Implement the coroutine loop in `ShadowingEngine.kt`:
- Normalize words via `fun normalizeWord(raw: String): String = raw.trim { !it.isLetterOrDigit() }.lowercase()`.
- On sentence evaluation: if `result.accuracyScore >= settings.accuracyThreshold`, play `EarconType.PASS`, clear word fail counts, advance `currentIndex`, and save progress.
- If `result.accuracyScore < settings.accuracyThreshold`, increment `consecutiveWordFails[normWord]` for any word with `accuracyScore < settings.accuracyThreshold` or `errorType != "None"`, reset passed words to `0`.
- If any word reaches `consecutiveWordFails >= settings.drillFailThreshold` (`2`), enter `WORD_DRILL` for the lowest-scoring failed word: speak `"Let's practice: $drillWord"`, loop up to `settings.maxDrillAttempts` (`3`) speaking `$drillWord` at `settings.drillSpeechRate` (`0.65f`), recording with `trailingSilenceMs = 800L`, and evaluating with `referenceText = drillWord`. On pass or 3 tries, reset `consecutiveWordFails[normWord] = 0` and replay the full sentence.
- On `ShadowingAction.SkipNext`: cancel current turn job immediately, clear drill state and word fail map, advance to `(currentIndex + 1)`, and start playback.
- On `ShadowingAction.GpsAudioFocusLost`: cancel current turn job immediately (aborting any open mic capture), set `pausedByAudioFocus = true` and `phase = EnginePhase.PAUSED`; on `GpsAudioFocusRegained`: if `pausedByAudioFocus`, clear flag and restart the current sentence from TTS playback.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.engine.ShadowingEngineTest"`
Expected: PASS (5 tests passing).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/shadowing/app/engine app/src/test/java/com/shadowing/app/engine
git commit -m "feat: implement ShadowingEngine state machine, word micro-drill, and GPS focus recovery"
```

---

### Task 5: Content Ingestion (Paste, Web URL, TXT, EPUB, PDF), Sentence Segmentation & Persistence

**Files:**
- Create: `app/src/main/java/com/shadowing/app/content/SentenceSegmenter.kt`
- Create: `app/src/main/java/com/shadowing/app/content/DocumentIngestor.kt`
- Create: `app/src/main/java/com/shadowing/app/data/ShadowingDatabase.kt`
- Test: `app/src/test/java/com/shadowing/app/content/SentenceSegmenterAndEpubTest.kt`

**Interfaces:**
- Consumes: `ShadowingSettings` (Task 1).
- Produces:
  - `object SentenceSegmenter { fun segment(rawText: String, maxWordsPerChunk: Int = 28): List<String> }`
  - `class DocumentIngestor(private val context: Context, private val httpClient: OkHttpClient)` with `suspend fun ingestRawText(title: String, text: String): IngestedDocument`, `suspend fun ingestWebUrl(url: String): IngestedDocument`, `suspend fun ingestUri(uri: Uri): IngestedDocument`, and `fun parseEpubBytes(bytes: ByteArray): String`
  - `ShadowingDatabase`, `ShadowingDao`, `SettingsRepository`

- [ ] **Step 1: Write the failing unit tests for `SentenceSegmenter` and EPUB extraction**

Create `app/src/test/java/com/shadowing/app/content/SentenceSegmenterAndEpubTest.kt`:

```kotlin
package com.shadowing.app.content

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SentenceSegmenterAndEpubTest {

    @Test
    fun test_segmenter_preservesAbbreviationsAndDecimalsInSingleSentence() {
        val raw = "Dr. Smith paid $3.50 in the U.S. today. He enjoyed the coffee very much."
        val sentences = SentenceSegmenter.segment(raw)
        assertEquals(
            listOf(
                "Dr. Smith paid $3.50 in the U.S. today.",
                "He enjoyed the coffee very much."
            ),
            sentences
        )
    }

    @Test
    fun test_parseEpubBytes_extractsChaptersInSpineOrder() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <package><manifest>
                  <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine>
                  <itemref idref="c1"/><itemref idref="c2"/>
                </spine></package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zip.write("<html><body><p>First chapter sentence today.</p></body></html>".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            zip.write("<html><body><p>Second chapter sentence here.</p></body></html>".toByteArray())
            zip.closeEntry()
        }

        val extracted = DocumentIngestor.extractTextFromEpubStream(baos.toByteArray().inputStream())
        val sentences = SentenceSegmenter.segment(extracted)
        assertEquals(
            listOf("First chapter sentence today.", "Second chapter sentence here."),
            sentences
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.content.SentenceSegmenterAndEpubTest"`
Expected: FAIL (`SentenceSegmenter` and `DocumentIngestor` not defined).

- [ ] **Step 3: Implement `SentenceSegmenter`, `DocumentIngestor`, and `ShadowingDatabase`**

Implement `SentenceSegmenter.segment` (temporarily masking common abbreviations `Dr.`, `Mr.`, `Mrs.`, `Ms.`, `Prof.`, `Sr.`, `Jr.`, `vs.`, `etc.`, `U.S.`, `U.K.` and decimals `\d+\.\d+` before splitting with `BreakIterator.getSentenceInstance(Locale.US)` and filtering fragments with `< 2` alphabetic words), `DocumentIngestor` (handling Paste, Web URL via Jsoup, `.txt`, `.epub` via `ZipInputStream` + Jsoup, and `.pdf` via `PDFBoxResourceLoader` + `PDFTextStripper`), and Room/DataStore persistence in `ShadowingDatabase.kt` (including a built-in starter document seeded on first launch so the app is ready to drive out of the box).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.content.SentenceSegmenterAndEpubTest"`
Expected: PASS (2 tests passing).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/shadowing/app/content app/src/main/java/com/shadowing/app/data app/src/test/java/com/shadowing/app/content
git commit -m "feat: add SentenceSegmenter, TXT/EPUB/PDF/URL ingestors, and Room/DataStore persistence"
```

---

### Task 6: USB Android Auto Media3 Service & Steering-Wheel Button Bridge

**Files:**
- Create: `app/src/main/res/xml/automotive_app_desc.xml`
- Create: `app/src/main/java/com/shadowing/app/car/ShadowingSessionPlayer.kt`
- Create: `app/src/main/java/com/shadowing/app/car/ShadowingMediaLibraryService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/shadowing/app/car/ShadowingCarMetadataMapperTest.kt`

**Interfaces:**
- Consumes: `ShadowingEngine`, `ShadowingUiState`, `ShadowingAction` (Task 4).
- Produces:
  - `object ShadowingCarMetadataMapper { fun toDisplayTitle(state: ShadowingUiState): String; fun toDisplaySubtitle(state: ShadowingUiState): String; fun mapPlayerCommandToAction(command: Int): ShadowingAction? }`
  - `class ShadowingSessionPlayer(looper: Looper, private val engine: ShadowingEngine) : SimpleBasePlayer(looper)`
  - `class ShadowingMediaLibraryService : MediaLibraryService()`

- [ ] **Step 1: Write the failing unit tests for Android Auto metadata and steering-wheel command mapping**

Create `app/src/test/java/com/shadowing/app/car/ShadowingCarMetadataMapperTest.kt`:

```kotlin
package com.shadowing.app.car

import androidx.media3.common.Player
import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.domain.model.ShadowingUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowingCarMetadataMapperTest {

    @Test
    fun test_mapPlayerCommandToAction_mapsSteeringWheelNextPrevAndPlayPause() {
        assertEquals(ShadowingAction.SkipNext, ShadowingCarMetadataMapper.mapPlayerCommandToAction(Player.COMMAND_SEEK_TO_NEXT))
        assertEquals(ShadowingAction.SkipNext, ShadowingCarMetadataMapper.mapPlayerCommandToAction(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertEquals(ShadowingAction.RepeatOrPrevious, ShadowingCarMetadataMapper.mapPlayerCommandToAction(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertEquals(ShadowingAction.RepeatOrPrevious, ShadowingCarMetadataMapper.mapPlayerCommandToAction(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertEquals(ShadowingAction.TogglePause, ShadowingCarMetadataMapper.mapPlayerCommandToAction(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun test_toDisplayTitleAndSubtitle_formatsActiveSentenceAndWordDrillForCarDashboard() {
        val drillState = ShadowingUiState(
            documentTitle = "Atomic Habits",
            currentSentence = "The phenomenon was thoroughly studied.",
            phase = EnginePhase.WORD_DRILL,
            activeDrillWord = "thoroughly",
            lastScore = 68,
            accuracyThreshold = 80
        )
        assertEquals("Drill: thoroughly", ShadowingCarMetadataMapper.toDisplayTitle(drillState))
        assertEquals("68% (Goal 80%) • Slow Word Drill", ShadowingCarMetadataMapper.toDisplaySubtitle(drillState))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.car.ShadowingCarMetadataMapperTest"`
Expected: FAIL (`ShadowingCarMetadataMapper` not defined).

- [ ] **Step 3: Implement `ShadowingCarMetadataMapper`, `ShadowingSessionPlayer`, `ShadowingMediaLibraryService`, and manifest entries**

Implement `ShadowingCarMetadataMapper`, `ShadowingSessionPlayer` (exposing `COMMAND_PLAY_PAUSE`, `COMMAND_SEEK_TO_NEXT`, `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`, `COMMAND_SEEK_TO_PREVIOUS`, `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, `COMMAND_GET_CURRENT_MEDIA_ITEM`, `COMMAND_GET_METADATA`), `ShadowingMediaLibraryService` (registering `AudioManager.OnAudioFocusChangeListener` to dispatch `GpsAudioFocusLost` / `GpsAudioFocusRegained`), and `automotive_app_desc.xml` + `AndroidManifest.xml`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.car.ShadowingCarMetadataMapperTest"`
Expected: PASS (2 tests passing).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/res/xml/automotive_app_desc.xml app/src/main/AndroidManifest.xml app/src/main/java/com/shadowing/app/car app/src/test/java/com/shadowing/app/car
git commit -m "feat: add USB Android Auto MediaLibraryService and steering-wheel button player bridge"
```

---

### Task 7: Jetpack Compose Phone UI with Real-Time Synchronized Word Bold Highlighting

**Files:**
- Create: `app/src/main/java/com/shadowing/app/ui/SentenceHighlightFormatter.kt`
- Create: `app/src/main/java/com/shadowing/app/ui/ShadowingScreens.kt`
- Create: `app/src/main/java/com/shadowing/app/MainActivity.kt`
- Test: `app/src/test/java/com/shadowing/app/ui/SentenceHighlightFormatterTest.kt`

**Interfaces:**
- Consumes: `HighlightRange` (Task 3), `ShadowingEngine`, `ShadowingUiState` (Task 4), `DocumentIngestor`, `ShadowingDatabase`, `SettingsRepository` (Task 5).
- Produces:
  - `object SentenceHighlightFormatter { fun buildAnnotatedSentence(sentence: String, activeRange: HighlightRange?, failedWords: Set<String>): AnnotatedString }`
  - `@Composable fun ShadowingApp(...)` and `class MainActivity : ComponentActivity()`

- [ ] **Step 1: Write the failing unit test for real-time word bolding in `SentenceHighlightFormatter`**

Create `app/src/test/java/com/shadowing/app/ui/SentenceHighlightFormatterTest.kt`:

```kotlin
package com.shadowing.app.ui

import androidx.compose.ui.text.font.FontWeight
import com.shadowing.app.tts.HighlightRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceHighlightFormatterTest {

    @Test
    fun test_buildAnnotatedSentence_appliesBoldSpanToCurrentlySpokenWordRange() {
        val sentence = "Practice English while driving."
        // "English" is indices 9..16
        val annotated = SentenceHighlightFormatter.buildAnnotatedSentence(
            sentence = sentence,
            activeRange = HighlightRange(9, 16),
            failedWords = setOf("driving")
        )

        assertEquals(sentence, annotated.text)
        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(9, boldSpans.first().start)
        assertEquals(16, boldSpans.first().end)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.shadowing.app.ui.SentenceHighlightFormatterTest"`
Expected: FAIL (`SentenceHighlightFormatter` not defined).

- [ ] **Step 3: Implement `SentenceHighlightFormatter`, `ShadowingScreens.kt`, and `MainActivity.kt`**

Implement `SentenceHighlightFormatter.buildAnnotatedSentence` (bolding `activeRange` during TTS playback and coloring words in `failedWords` so the learner sees both the live spoken word in bold and any mispronounced words), plus the three tabs in `ShadowingScreens.kt` (**Practice / Driving** with large steering-wheel-equivalent Skip/Repeat/Play-Pause buttons and synchronized bold sentence display, **Library / Import** for Paste, URL, TXT, EPUB, PDF, and **Settings** for Accuracy Threshold %, Speech Rate, Drill Threshold, Azure Key/Region, and Demo Scorer toggle), wired in `MainActivity.kt`.

- [ ] **Step 4: Run all unit tests and assemble the debug APK**

Run: `.\gradlew.bat testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL` with all unit tests passing and `app/build/outputs/apk/debug/app-debug.apk` generated.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/shadowing/app/ui app/src/main/java/com/shadowing/app/MainActivity.kt app/src/test/java/com/shadowing/app/ui
git commit -m "feat: add Jetpack Compose UI with real-time TTS word bolding, import library, and settings"
```
