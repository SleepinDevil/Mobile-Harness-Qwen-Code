package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReasoningResolutionTest {
    @Test
    fun chatOverrideWinsOverGlobalSettingsValue() {
        assertEquals(4, effectiveReasoningValue(reasoningOverride = 4, globalValue = 1))
    }

    @Test
    fun firstPickerEntryCountsAsOverrideAndOnlyMinusOneFollowsSettings() {
        assertEquals(0, effectiveReasoningValue(reasoningOverride = 0, globalValue = 2))
        assertEquals(2, effectiveReasoningValue(reasoningOverride = -1, globalValue = 2))
    }
}
