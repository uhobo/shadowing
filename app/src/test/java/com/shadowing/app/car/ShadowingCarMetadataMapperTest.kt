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
