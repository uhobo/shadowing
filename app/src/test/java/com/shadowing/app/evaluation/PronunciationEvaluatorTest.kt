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
