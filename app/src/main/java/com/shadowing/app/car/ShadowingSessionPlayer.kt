package com.shadowing.app.car

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.domain.model.ShadowingUiState
import com.shadowing.app.engine.ShadowingEngine

object ShadowingCarMetadataMapper {
    fun mapPlayerCommandToAction(command: Int): ShadowingAction? = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> ShadowingAction.SkipNext
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> ShadowingAction.RepeatOrPrevious
        Player.COMMAND_PLAY_PAUSE -> ShadowingAction.TogglePause
        else -> null
    }

    fun toDisplayTitle(state: ShadowingUiState): String {
        if (state.phase == EnginePhase.WORD_DRILL && !state.activeDrillWord.isNullOrBlank()) {
            return "Drill: ${state.activeDrillWord}"
        }
        return state.currentSentence.ifBlank { "Shadowing Coach Ready" }
    }

    fun toDisplaySubtitle(state: ShadowingUiState): String {
        val scorePart = state.lastScore?.let { "$it% (Goal ${state.accuracyThreshold}%)" }
            ?: "Goal ${state.accuracyThreshold}%"
        val phaseLabel = when (state.phase) {
            EnginePhase.IDLE -> "Press Play to start"
            EnginePhase.SENTENCE_PLAYBACK -> "Listening to TTS"
            EnginePhase.LISTENING -> "Speak Now"
            EnginePhase.EVALUATING -> "Scoring..."
            EnginePhase.FEEDBACK_RETRY -> "Retry: ${state.failedWords.take(2).joinToString(", ").ifEmpty { "Try again" }}"
            EnginePhase.WORD_DRILL -> "Slow Word Drill"
            EnginePhase.ADVANCING -> "Passed!"
            EnginePhase.PAUSED -> "Paused"
        }
        return "$scorePart • $phaseLabel"
    }
}

@UnstableApi
class ShadowingSessionPlayer(
    looper: Looper,
    private val engine: ShadowingEngine
) : SimpleBasePlayer(looper) {

    fun notifyEngineStateChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val ui = engine.uiState.value
        val isActive = ui.phase != EnginePhase.IDLE && ui.phase != EnginePhase.PAUSED

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA
            )
            .build()

        val metadata = MediaMetadata.Builder()
            .setTitle(ShadowingCarMetadataMapper.toDisplayTitle(ui))
            .setArtist(ShadowingCarMetadataMapper.toDisplaySubtitle(ui))
            .setAlbumTitle(ui.documentTitle.ifBlank { "English Shadowing" })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()

        // Include previous, current, and next virtual items in the playlist so Android Auto
        // always renders and enables both the Previous (|<<) and Next (>>|) buttons on screen
        // and routes steering-wheel Next/Prev keys.
        val prevItem = MediaItemData.Builder("shadowing_prev")
            .setMediaItem(MediaItem.fromUri("shadowing://prev"))
            .setMediaMetadata(metadata)
            .build()
        val currentItem = MediaItemData.Builder("shadowing_current")
            .setMediaItem(MediaItem.Builder().setMediaId("shadowing_current").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()
        val nextItem = MediaItemData.Builder("shadowing_next")
            .setMediaItem(MediaItem.fromUri("shadowing://next"))
            .setMediaMetadata(metadata)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(isActive, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(prevItem, currentItem, nextItem))
            .setCurrentMediaItemIndex(1)
            .setPlaylistMetadata(metadata)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            engine.dispatch(ShadowingAction.StartOrResume)
        } else {
            engine.dispatch(ShadowingAction.Pause)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.dispatch(ShadowingAction.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val mapped = ShadowingCarMetadataMapper.mapPlayerCommandToAction(seekCommand)
        if (mapped != null) {
            engine.dispatch(mapped)
        } else if (mediaItemIndex > 1) {
            engine.dispatch(ShadowingAction.SkipNext)
        } else if (mediaItemIndex < 1) {
            engine.dispatch(ShadowingAction.RepeatOrPrevious)
        }
        return Futures.immediateVoidFuture()
    }
}
