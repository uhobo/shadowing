package com.shadowing.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shadowing.app.content.IngestedDocument
import com.shadowing.app.content.SentenceSegmenter
import com.shadowing.app.domain.model.ShadowingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class DocumentSummary(
    val id: Long,
    val title: String,
    val sourceType: String,
    val currentSentenceIndex: Int,
    val totalSentences: Int
)

private val Context.dataStore by preferencesDataStore(name = "shadowing_settings")

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope
) {
    private object Keys {
        val ACCURACY_THRESHOLD = intPreferencesKey("accuracy_threshold")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val USE_DEFAULT_SENTENCE_LENGTH = booleanPreferencesKey("use_default_sentence_length")
        val MAX_WORDS_PER_SENTENCE = intPreferencesKey("max_words_per_sentence")
        val DRILL_FAIL_THRESHOLD = intPreferencesKey("drill_fail_threshold")
        val MAX_DRILL_ATTEMPTS = intPreferencesKey("max_drill_attempts")
        val DRILL_SPEECH_RATE = floatPreferencesKey("drill_speech_rate")
        val AZURE_API_KEY = stringPreferencesKey("azure_api_key")
        val AZURE_REGION = stringPreferencesKey("azure_region")
        val USE_MOCK_EVALUATOR = booleanPreferencesKey("use_mock_evaluator")
    }

    val settingsFlow: StateFlow<ShadowingSettings> = context.dataStore.data
        .map { prefs ->
            ShadowingSettings(
                accuracyThreshold = prefs[Keys.ACCURACY_THRESHOLD] ?: 80,
                speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
                useDefaultSentenceLength = prefs[Keys.USE_DEFAULT_SENTENCE_LENGTH] ?: true,
                maxWordsPerSentence = prefs[Keys.MAX_WORDS_PER_SENTENCE] ?: 28,
                drillFailThreshold = prefs[Keys.DRILL_FAIL_THRESHOLD] ?: 2,
                maxDrillAttempts = prefs[Keys.MAX_DRILL_ATTEMPTS] ?: 3,
                drillSpeechRate = prefs[Keys.DRILL_SPEECH_RATE] ?: 0.65f,
                azureApiKey = prefs[Keys.AZURE_API_KEY] ?: "",
                azureRegion = prefs[Keys.AZURE_REGION] ?: "eastus",
                useMockEvaluator = prefs[Keys.USE_MOCK_EVALUATOR] ?: true
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ShadowingSettings())

    suspend fun updateSettings(transform: (ShadowingSettings) -> ShadowingSettings) {
        context.dataStore.edit { prefs ->
            val current = ShadowingSettings(
                accuracyThreshold = prefs[Keys.ACCURACY_THRESHOLD] ?: 80,
                speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
                useDefaultSentenceLength = prefs[Keys.USE_DEFAULT_SENTENCE_LENGTH] ?: true,
                maxWordsPerSentence = prefs[Keys.MAX_WORDS_PER_SENTENCE] ?: 28,
                drillFailThreshold = prefs[Keys.DRILL_FAIL_THRESHOLD] ?: 2,
                maxDrillAttempts = prefs[Keys.MAX_DRILL_ATTEMPTS] ?: 3,
                drillSpeechRate = prefs[Keys.DRILL_SPEECH_RATE] ?: 0.65f,
                azureApiKey = prefs[Keys.AZURE_API_KEY] ?: "",
                azureRegion = prefs[Keys.AZURE_REGION] ?: "eastus",
                useMockEvaluator = prefs[Keys.USE_MOCK_EVALUATOR] ?: true
            )
            val updated = transform(current)
            prefs[Keys.ACCURACY_THRESHOLD] = updated.accuracyThreshold.coerceIn(50, 98)
            prefs[Keys.SPEECH_RATE] = updated.speechRate.coerceIn(0.5f, 1.5f)
            prefs[Keys.USE_DEFAULT_SENTENCE_LENGTH] = updated.useDefaultSentenceLength
            prefs[Keys.MAX_WORDS_PER_SENTENCE] = updated.maxWordsPerSentence.coerceIn(1, 30)
            prefs[Keys.DRILL_FAIL_THRESHOLD] = updated.drillFailThreshold.coerceIn(1, 5)
            prefs[Keys.MAX_DRILL_ATTEMPTS] = updated.maxDrillAttempts.coerceIn(1, 5)
            prefs[Keys.DRILL_SPEECH_RATE] = updated.drillSpeechRate.coerceIn(0.4f, 1.0f)
            prefs[Keys.AZURE_API_KEY] = updated.azureApiKey.trim()
            prefs[Keys.AZURE_REGION] = updated.azureRegion.trim().ifEmpty { "eastus" }
            prefs[Keys.USE_MOCK_EVALUATOR] = updated.useMockEvaluator
        }
    }
}

class ShadowingDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "shadowing_library.db",
    null,
    1
) {
    private val _documentsFlow = MutableStateFlow<List<DocumentSummary>>(emptyList())
    val documentsFlow: StateFlow<List<DocumentSummary>> = _documentsFlow.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                source_type TEXT NOT NULL,
                current_index INTEGER NOT NULL DEFAULT 0,
                total_sentences INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sentences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                content TEXT NOT NULL,
                FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        seedStarterDocument(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun seedStarterDocument(db: SQLiteDatabase) {
        val starterSentences = SentenceSegmenter.segment(
            """
            Consistent daily shadowing while driving builds effortless English fluency and clear pronunciation.
            Focus on matching the rhythm, stress, and melody of each sentence you hear.
            When a particularly challenging word appears, we slow down and practice it thoroughly together.
            Press the Next button on your steering wheel at any time to skip forward safely.
            Press the Previous button on your steering wheel whenever you want to hear a sentence again.
            """.trimIndent()
        )
        insertDocumentInternal(
            db = db,
            title = "Driving Shadowing Starter Pack",
            sourceType = "STARTER",
            sentences = starterSentences
        )
    }

    private fun insertDocumentInternal(
        db: SQLiteDatabase,
        title: String,
        sourceType: String,
        sentences: List<String>
    ): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("source_type", sourceType)
            put("current_index", 0)
            put("total_sentences", sentences.size)
            put("created_at", System.currentTimeMillis())
        }
        val docId = db.insert("documents", null, values)
        sentences.forEachIndexed { index, text ->
            val sValues = ContentValues().apply {
                put("document_id", docId)
                put("position", index)
                put("content", text)
            }
            db.insert("sentences", null, sValues)
        }
        return docId
    }

    suspend fun refreshDocuments(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DocumentSummary>()
        readableDatabase.rawQuery(
            "SELECT id, title, source_type, current_index, total_sentences FROM documents ORDER BY id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    DocumentSummary(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        sourceType = cursor.getString(2),
                        currentSentenceIndex = cursor.getInt(3),
                        totalSentences = cursor.getInt(4)
                    )
                )
            }
        }
        _documentsFlow.value = list
        list
    }

    suspend fun saveIngestedDocument(doc: IngestedDocument): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        val id = try {
            val newId = insertDocumentInternal(db, doc.title, doc.sourceType, doc.sentences)
            db.setTransactionSuccessful()
            newId
        } finally {
            db.endTransaction()
        }
        refreshDocuments()
        id
    }

    suspend fun getSentencesForDocument(documentId: Long): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT content FROM sentences WHERE document_id = ? ORDER BY position ASC",
            arrayOf(documentId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0))
            }
        }
        result
    }

    suspend fun updateProgress(documentId: Long, sentenceIndex: Int) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("current_index", sentenceIndex)
        }
        writableDatabase.update(
            "documents",
            values,
            "id = ?",
            arrayOf(documentId.toString())
        )
        refreshDocuments()
    }
}
