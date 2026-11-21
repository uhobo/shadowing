package com.shadowing.app.car

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shadowing.app.audio.PhoneMicRecorder
import com.shadowing.app.content.DocumentIngestor
import com.shadowing.app.data.SettingsRepository
import com.shadowing.app.data.ShadowingDatabase
import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.engine.ShadowingEngine
import com.shadowing.app.evaluation.AzurePronunciationEvaluator
import com.shadowing.app.tts.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ShadowingAppContainer {
    @Volatile
    private var initialized = false

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var httpClient: OkHttpClient
        private set
    lateinit var database: ShadowingDatabase
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var ingestor: DocumentIngestor
        private set
    lateinit var ttsController: TtsController
        private set
    lateinit var engine: ShadowingEngine
        private set

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            httpClient = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
            database = ShadowingDatabase(appContext)
            settingsRepository = SettingsRepository(appContext, appScope)
            ingestor = DocumentIngestor(appContext, httpClient)
            ttsController = TtsController(appContext)
            val recorder = PhoneMicRecorder()
            val evaluator = AzurePronunciationEvaluator(httpClient) {
                settingsRepository.settingsFlow.value
            }
            engine = ShadowingEngine(
                scope = appScope,
                tts = ttsController,
                recorder = recorder,
                evaluator = evaluator,
                settingsFlow = settingsRepository.settingsFlow,
                onProgressSaved = { docId, index ->
                    database.updateProgress(docId, index)
                }
            )
            appScope.launch {
                val docs = database.refreshDocuments()
                val first = docs.firstOrNull()
                if (first != null && engine.uiState.value.totalSentences == 0) {
                    val sentences = database.getSentencesForDocument(first.id)
                    engine.loadSession(
                        documentId = first.id,
                        title = first.title,
                        sentences = sentences,
                        startIndex = first.currentSentenceIndex
                    )
                }
            }
            initialized = true
        }
    }

    fun selectAndStartDocument(docId: Long) {
        appScope.launch {
            val docs = database.documentsFlow.value.ifEmpty { database.refreshDocuments() }
            val doc = docs.firstOrNull { it.id == docId } ?: return@launch
            val sentences = database.getSentencesForDocument(doc.id)
            engine.loadSession(
                documentId = doc.id,
                title = doc.title,
                sentences = sentences,
                startIndex = doc.currentSentenceIndex
            )
            engine.dispatch(ShadowingAction.StartOrResume)
        }
    }
}

@UnstableApi
class ShadowingMediaLibraryService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ShadowingSessionPlayer
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var uiCollectorJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ShadowingAppContainer.engine.dispatch(ShadowingAction.GpsAudioFocusLost)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                ShadowingAppContainer.engine.dispatch(ShadowingAction.Pause)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                ShadowingAppContainer.engine.dispatch(ShadowingAction.GpsAudioFocusRegained)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ShadowingAppContainer.ensureInitialized(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        player = ShadowingSessionPlayer(
            looper = Looper.getMainLooper(),
            engine = ShadowingAppContainer.engine,
            onSelectDocumentFromCar = { docId ->
                ShadowingAppContainer.selectAndStartDocument(docId)
            }
        )
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            player,
            LibrarySessionCallback()
        ).build()

        uiCollectorJob = ShadowingAppContainer.appScope.launch {
            ShadowingAppContainer.engine.uiState
                .distinctUntilChanged { old, new ->
                    val oldActive = old.phase != EnginePhase.IDLE && old.phase != EnginePhase.PAUSED
                    val newActive = new.phase != EnginePhase.IDLE && new.phase != EnginePhase.PAUSED
                    oldActive == newActive &&
                        ShadowingCarMetadataMapper.toDisplayTitle(old) == ShadowingCarMetadataMapper.toDisplayTitle(new) &&
                        ShadowingCarMetadataMapper.toDisplaySubtitle(old) == ShadowingCarMetadataMapper.toDisplaySubtitle(new) &&
                        old.documentTitle == new.documentTitle
                }
                .collect { state ->
                    val isActive = state.phase != EnginePhase.IDLE && state.phase != EnginePhase.PAUSED
                    if (isActive && !hasAudioFocus) {
                        requestCarAudioFocus()
                    } else if (!isActive && hasAudioFocus && !ShadowingAppContainer.engine.isPausedByAudioFocus) {
                        abandonCarAudioFocus()
                    }
                    mainHandler.post {
                        player.notifyEngineStateChanged()
                    }
                }
        }
    }

    private fun requestCarAudioFocus() {
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()
            .also { audioFocusRequest = it }

        val res = audioManager.requestAudioFocus(req)
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonCarAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val phase = ShadowingAppContainer.engine.uiState.value.phase
        if (phase == EnginePhase.IDLE || phase == EnginePhase.PAUSED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        uiCollectorJob?.cancel()
        uiCollectorJob = null
        abandonCarAudioFocus()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootMetadata = MediaMetadata.Builder()
                .setTitle("Shadowing Coach")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(rootMetadata)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val docs = ShadowingAppContainer.database.documentsFlow.value
            val items = docs.map { doc ->
                val meta = MediaMetadata.Builder()
                    .setTitle(doc.title)
                    .setArtist("Sentence ${doc.currentSentenceIndex + 1} of ${doc.totalSentences}")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build()
                MediaItem.Builder()
                    .setMediaId("doc_${doc.id}")
                    .setMediaMetadata(meta)
                    .build()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val selectedId = mediaItems.firstOrNull()?.mediaId?.let {
                ShadowingCarMetadataMapper.parseDocumentMediaId(it)
            }
            if (selectedId != null) {
                ShadowingAppContainer.selectAndStartDocument(selectedId)
            }
            return Futures.immediateFuture(mediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val selectedId = mediaItems.firstOrNull()?.mediaId?.let {
                ShadowingCarMetadataMapper.parseDocumentMediaId(it)
            }
            if (selectedId != null) {
                ShadowingAppContainer.selectAndStartDocument(selectedId)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }
    }
}
