package com.shadowing.app.evaluation

import com.shadowing.app.domain.model.PhonemeAssessment
import com.shadowing.app.domain.model.PronunciationResult
import com.shadowing.app.domain.model.ShadowingSettings
import com.shadowing.app.domain.model.WordAssessment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import kotlin.math.roundToInt

interface PronunciationEvaluator {
    suspend fun evaluate(wavBytes: ByteArray, referenceText: String): Result<PronunciationResult>
}

object AzurePronunciationProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class AssessmentConfigHeader(
        @SerialName("ReferenceText") val referenceText: String,
        @SerialName("GradingSystem") val gradingSystem: String = "HundredMark",
        @SerialName("Granularity") val granularity: String = "Phoneme",
        @SerialName("Dimension") val dimension: String = "Comprehensive",
        @SerialName("EnableMiscue") val enableMiscue: Boolean = true
    )

    @Serializable
    private data class AzureSpeechResponse(
        @SerialName("RecognitionStatus") val recognitionStatus: String = "",
        @SerialName("DisplayText") val displayText: String = "",
        @SerialName("NBest") val nBest: List<AzureNBestEntry> = emptyList()
    )

    @Serializable
    private data class AzureNBestEntry(
        @SerialName("Display") val display: String = "",
        @SerialName("PronunciationAssessment") val pronunciationAssessment: AzureScoreSummary? = null,
        @SerialName("Words") val words: List<AzureWordEntry> = emptyList()
    )

    @Serializable
    private data class AzureScoreSummary(
        @SerialName("AccuracyScore") val accuracyScore: Double = 0.0,
        @SerialName("FluencyScore") val fluencyScore: Double = 0.0,
        @SerialName("CompletenessScore") val completenessScore: Double = 0.0
    )

    @Serializable
    private data class AzureWordEntry(
        @SerialName("Word") val word: String = "",
        @SerialName("PronunciationAssessment") val pronunciationAssessment: AzureWordScore? = null,
        @SerialName("Phonemes") val phonemes: List<AzurePhonemeEntry> = emptyList()
    )

    @Serializable
    private data class AzureWordScore(
        @SerialName("AccuracyScore") val accuracyScore: Double = 0.0,
        @SerialName("ErrorType") val errorType: String = "None"
    )

    @Serializable
    private data class AzurePhonemeEntry(
        @SerialName("Phoneme") val phoneme: String = "",
        @SerialName("PronunciationAssessment") val pronunciationAssessment: AzurePhonemeScore? = null
    )

    @Serializable
    private data class AzurePhonemeScore(
        @SerialName("AccuracyScore") val accuracyScore: Double = 0.0
    )

    fun buildHeaderBase64(referenceText: String): String {
        val payload = json.encodeToString(AssessmentConfigHeader(referenceText = referenceText))
        return Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun parseResponseJson(jsonString: String): PronunciationResult {
        val parsed = json.decodeFromString<AzureSpeechResponse>(jsonString)
        val best = parsed.nBest.firstOrNull()
        val summary = best?.pronunciationAssessment

        val words = best?.words?.map { wordEntry ->
            val wordScore = wordEntry.pronunciationAssessment
            WordAssessment(
                word = wordEntry.word,
                accuracyScore = (wordScore?.accuracyScore ?: 0.0).roundToInt(),
                errorType = wordScore?.errorType ?: "None",
                phonemes = wordEntry.phonemes.map { p ->
                    PhonemeAssessment(
                        phoneme = p.phoneme,
                        accuracyScore = (p.pronunciationAssessment?.accuracyScore ?: 0.0).roundToInt()
                    )
                }
            )
        } ?: emptyList()

        return PronunciationResult(
            recognizedText = parsed.displayText.ifBlank { best?.display.orEmpty() },
            accuracyScore = (summary?.accuracyScore ?: 0.0).roundToInt(),
            fluencyScore = (summary?.fluencyScore ?: 0.0).roundToInt(),
            completenessScore = (summary?.completenessScore ?: 0.0).roundToInt(),
            words = words
        )
    }
}

class AzurePronunciationEvaluator(
    private val httpClient: OkHttpClient,
    private val settingsProvider: suspend () -> ShadowingSettings
) : PronunciationEvaluator {

    override suspend fun evaluate(
        wavBytes: ByteArray,
        referenceText: String
    ): Result<PronunciationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsProvider()
            if (settings.useMockEvaluator || settings.azureApiKey.isBlank()) {
                return@runCatching MockPronunciationEvaluator().evaluate(wavBytes, referenceText).getOrThrow()
            }

            val region = settings.azureRegion.trim().ifEmpty { "eastus" }
            val url = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US&format=detailed"
            val assessmentHeader = AzurePronunciationProtocol.buildHeaderBase64(referenceText)
            val requestBody = wavBytes.toRequestBody("audio/wav; codecs=audio/pcm; samplerate=16000".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", settings.azureApiKey.trim())
                .addHeader("Pronunciation-Assessment", assessmentHeader)
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Azure Speech HTTP ${response.code}: $bodyString")
                }
                AzurePronunciationProtocol.parseResponseJson(bodyString)
            }
        }
    }
}

class MockPronunciationEvaluator(
    var nextScoresByWord: Map<String, Int> = emptyMap(),
    var defaultScore: Int = 88
) : PronunciationEvaluator {

    override suspend fun evaluate(
        wavBytes: ByteArray,
        referenceText: String
    ): Result<PronunciationResult> {
        val rawTokens = referenceText
            .split(Regex("\\s+"))
            .map { it.trim { ch -> !ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

        val wordAssessments = rawTokens.map { token ->
            val lookupScore = nextScoresByWord[token.lowercase()] ?: defaultScore
            val errorType = if (lookupScore >= 80) "None" else "Mispronunciation"
            WordAssessment(
                word = token,
                accuracyScore = lookupScore,
                errorType = errorType,
                phonemes = emptyList()
            )
        }

        val overall = if (wordAssessments.isNotEmpty()) {
            wordAssessments.sumOf { it.accuracyScore } / wordAssessments.size
        } else {
            defaultScore
        }

        return Result.success(
            PronunciationResult(
                recognizedText = referenceText,
                accuracyScore = overall,
                fluencyScore = overall,
                completenessScore = 100,
                words = wordAssessments
            )
        )
    }
}
