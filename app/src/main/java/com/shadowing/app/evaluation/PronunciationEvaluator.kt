package com.shadowing.app.evaluation

import com.shadowing.app.domain.model.PhonemeAssessment
import com.shadowing.app.domain.model.PronunciationResult
import com.shadowing.app.domain.model.ShadowingSettings
import com.shadowing.app.domain.model.WordAssessment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    fun normalizeRegion(rawRegion: String): String =
        rawRegion.trim().lowercase().replace(Regex("\\s+"), "").ifEmpty { "eastus" }

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
            if (settings.useMockEvaluator) {
                val passScore = maxOf(92, settings.accuracyThreshold + 5).coerceAtMost(100)
                return@runCatching MockPronunciationEvaluator(
                    defaultScore = passScore,
                    threshold = settings.accuracyThreshold
                ).evaluate(wavBytes, referenceText).getOrThrow()
            }
            if (settings.azureApiKey.isBlank()) {
                throw IllegalStateException("Azure Speech API key is required when Demo Scorer is disabled")
            }

            val region = AzurePronunciationProtocol.normalizeRegion(settings.azureRegion)
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

            val bodyString = executeCancellable(httpClient.newCall(request))
            AzurePronunciationProtocol.parseResponseJson(bodyString)
        }
    }

    suspend fun verifyCredentials(apiKey: String, rawRegion: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) {
                throw IllegalArgumentException("API Key is empty. Paste KEY 1 from Azure Portal.")
            }
            val region = AzurePronunciationProtocol.normalizeRegion(rawRegion)
            val url = "https://$region.api.cognitive.microsoft.com/sts/v1.0/issueToken"
            val request = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", cleanKey)
                .post(ByteArray(0).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val token = executeCancellable(httpClient.newCall(request))
            if (token.isBlank()) {
                throw IOException("Empty token response from Azure ($region)")
            }
            "Azure Key Verified for region '$region'!"
        }
    }

    private suspend fun executeCancellable(call: Call): String =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            if (cont.isActive) {
                                cont.resumeWithException(IOException("Azure Speech HTTP ${resp.code}: $body"))
                            }
                        } else if (cont.isActive) {
                            cont.resume(body)
                        }
                    }
                }
            })
        }
}

class MockPronunciationEvaluator(
    var nextScoresByWord: Map<String, Int> = emptyMap(),
    var defaultScore: Int = 92,
    var threshold: Int = 80
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
            val errorType = if (lookupScore >= threshold) "None" else "Mispronunciation"
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
