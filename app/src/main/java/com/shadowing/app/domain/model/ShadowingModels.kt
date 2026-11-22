package com.shadowing.app.domain.model

data class ShadowingSettings(
    val accuracyThreshold: Int = 80,
    val speechRate: Float = 1.0f,
    val useDefaultSentenceLength: Boolean = true,
    val maxWordsPerSentence: Int = 28,
    val drillFailThreshold: Int = 2,
    val maxDrillAttempts: Int = 3,
    val drillSpeechRate: Float = 0.65f,
    val azureApiKey: String = "",
    val azureRegion: String = "eastus",
    val useMockEvaluator: Boolean = true
)

data class PhonemeAssessment(
    val phoneme: String,
    val accuracyScore: Int
)

data class WordAssessment(
    val word: String,
    val accuracyScore: Int,
    val errorType: String,
    val phonemes: List<PhonemeAssessment> = emptyList()
)

data class PronunciationResult(
    val recognizedText: String,
    val accuracyScore: Int,
    val fluencyScore: Int,
    val completenessScore: Int,
    val words: List<WordAssessment>
)

sealed interface RecordingOutcome {
    data class Captured(val wavBytes: ByteArray, val durationMs: Long) : RecordingOutcome
    data object NoSpeechDetected : RecordingOutcome
}

interface MicRecorder {
    suspend fun recordUtterance(trailingSilenceMs: Long, maxDurationMs: Long): RecordingOutcome
}

enum class EnginePhase {
    IDLE,
    SENTENCE_PLAYBACK,
    LISTENING,
    EVALUATING,
    FEEDBACK_RETRY,
    WORD_DRILL,
    ADVANCING,
    PAUSED
}

data class ShadowingUiState(
    val documentId: Long = 0L,
    val documentTitle: String = "",
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentence: String = "",
    val phase: EnginePhase = EnginePhase.IDLE,
    val activeDrillWord: String? = null,
    val drillAttempt: Int = 0,
    val lastScore: Int? = null,
    val accuracyThreshold: Int = 80,
    val failedWords: Set<String> = emptySet(),
    val statusMessage: String = "Ready to start",
    val activeWordStart: Int? = null,
    val activeWordEnd: Int? = null
)

sealed interface ShadowingAction {
    data object StartOrResume : ShadowingAction
    data object Pause : ShadowingAction
    data object TogglePause : ShadowingAction
    data object SkipNext : ShadowingAction
    data object RepeatOrPrevious : ShadowingAction
    data object GpsAudioFocusLost : ShadowingAction
    data object GpsAudioFocusRegained : ShadowingAction
}
