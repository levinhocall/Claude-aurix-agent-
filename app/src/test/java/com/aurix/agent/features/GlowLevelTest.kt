package com.aurix.agent.features

import com.aurix.agent.core.mission.MissionStatus.*
import com.aurix.agent.features.glow.glowLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class GlowLevelTest {
    @Test fun idleWorkingAndApprovalMapToLevels() {
        assertEquals(0, glowLevel(emptyList())); assertEquals(0, glowLevel(listOf(COMPLETED, FAILED, CANCELLED, PAUSED, CREATED)))
        assertEquals(1, glowLevel(listOf(COMPLETED, RUNNING))); assertEquals(1, glowLevel(listOf(RECOVERING)))
        assertEquals(2, glowLevel(listOf(RUNNING, WAITING_FOR_APPROVAL)))
    }
}
