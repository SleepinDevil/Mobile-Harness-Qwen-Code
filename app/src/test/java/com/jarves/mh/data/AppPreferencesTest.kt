package com.jarves.mh.data

import com.jarves.mh.model.ProjectChat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun chatExportJsonWrapsMessagesWithMetadataAndPersistedTokenTotal() {
        val chat = ProjectChat(
            id = "c1",
            title = "My chat",
            createdAtMillis = 111L,
            updatedAtMillis = 222L,
            totalTokens = 12345L,
        )
        val messages = JSONArray().apply {
            put(JSONObject().put("fromUser", true).put("text", "hello"))
        }

        val parsed = JSONObject(chatExportJson(chat, messages))

        assertEquals("c1", parsed.getString("id"))
        assertEquals("My chat", parsed.getString("title"))
        assertEquals(111L, parsed.getLong("createdAtMillis"))
        assertEquals(222L, parsed.getLong("updatedAtMillis"))
        assertEquals(12345L, parsed.getLong("totalTokens"))
        assertEquals(1, parsed.getJSONArray("messages").length())
        assertEquals("hello", parsed.getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test
    fun legacyChatsWithoutPersistedTotalDefaultToZero() {
        assertEquals(0L, ProjectChat(id = "legacy", title = "t").totalTokens)
    }
    @Test
    fun chatIndexJsonRoundTripsChatScopedReasoningOverride() {
        val chats = listOf(
            ProjectChat(id = "c1", title = "Boosted", createdAtMillis = 1L, updatedAtMillis = 2L, reasoningOverride = 3),
            ProjectChat(id = "c2", title = "Following", createdAtMillis = 3L, updatedAtMillis = 4L),
        )

        val parsed = chatsFromIndexJson(JSONArray(chatIndexJson(chats).toString()))

        assertEquals(3, parsed.first { it.id == "c1" }.reasoningOverride)
        assertEquals(-1, parsed.first { it.id == "c2" }.reasoningOverride)
    }

    @Test
    fun legacyChatIndexEntriesWithoutReasoningFieldFollowSettings() {
        val legacy = JSONArray().apply {
            put(JSONObject().put("id", "old").put("title", "Legacy chat"))
        }

        assertEquals(-1, chatsFromIndexJson(legacy).single().reasoningOverride)
    }
}
