package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route mapping and provider-visibility rules for the Qwen Code bridge. */
class QwenBridgeTest {

    @Test
    fun anthropicProviderUsesAnthropicAuthBucket() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.ANTHROPIC, baseUrl = "https://api.anthropic.com/"),
            gatewayUrl = null,
        )
        assertEquals("anthropic", route.authType)
        assertEquals("ANTHROPIC_API_KEY", route.envKey)
        assertEquals("https://api.anthropic.com", route.baseUrl)
        assertNull(route.wireApi)
    }

    @Test
    fun gatewayStyleProvidersUseAnthropicAuth() {
        for (kind in listOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI)) {
            val route = QwenRouteMapper.forProfile(ProviderProfile(kind), gatewayUrl = null)
            assertEquals(kind.name, "anthropic", route.authType)
            assertEquals(kind.name, "ANTHROPIC_API_KEY", route.envKey)
        }
    }

    @Test
    fun llmRouterPrefersRoutingGatewayUrl() {
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        val routed = QwenRouteMapper.forProfile(profile, gatewayUrl = "http://127.0.0.1:9090")
        assertEquals("openai", routed.authType)
        assertEquals("OPENAI_API_KEY", routed.envKey)
        assertEquals("http://127.0.0.1:9090/v1", routed.baseUrl)

        val direct = QwenRouteMapper.forProfile(profile, gatewayUrl = null)
        assertEquals("https://openrouter.ai/api/v1", direct.baseUrl)
    }

    @Test
    fun openCodeZenUsesResponsesWireApi() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN), gatewayUrl = null)
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
        assertEquals("https://opencode.ai/zen/v1", route.baseUrl)
    }

    @Test
    fun nvidiaNimUsesOpenAiChatWithoutWireApi() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM), gatewayUrl = null)
        assertEquals("openai", route.authType)
        assertNull(route.wireApi)
        assertEquals("https://integrate.api.nvidia.com/v1", route.baseUrl)
    }

    @Test
    fun customAnthropicMessagesRouteKeepsBaseUrlRoot() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/anthropic/", dshApi = "anthropic-messages"),
            gatewayUrl = null,
        )
        assertEquals("anthropic", route.authType)
        assertEquals("https://gw.example.com/anthropic", route.baseUrl)
    }

    @Test
    fun customOpenAiCompletionsRouteNormalizesToVersionRoot() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/api/v1/chat/completions", dshApi = "openai-completions"),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("https://gw.example.com/api/v1", route.baseUrl)
        assertNull(route.wireApi)
    }

    @Test
    fun customOpenAiResponsesRouteAddsVersionRootAndWireApi() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/responses", dshApi = "openai-responses"),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
        assertEquals("https://gw.example.com/v1", route.baseUrl)
    }

    @Test
    fun customRouteFallsBackToUrlInferenceWhenApiBlank() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1/responses", dshApi = ""),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
    }

    @Test(expected = IllegalStateException::class)
    fun claudeSubscriptionProfileIsRejected() {
        QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE), gatewayUrl = null)
    }

    @Test
    fun modelFallsBackToProviderDefault() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK, model = ""), gatewayUrl = null)
        assertEquals(ProviderKind.DEEPSEEK.defaultModel, route.model)
    }

    @Test
    fun qwenCodeSupportsEveryApiKeyProviderButNotClaudeLogin() {
        val providers = providersForAgent(AgentKind.QWEN_CODE)
        assertFalse(ProviderKind.CLAUDE in providers)
        assertTrue(ProviderKind.ANTHROPIC in providers)
        assertTrue(ProviderKind.LLM_ROUTER in providers)
        assertTrue(ProviderKind.DEEPSEEK in providers)
        assertTrue(ProviderKind.KIMI in providers)
        assertTrue(ProviderKind.OPENCODE_ZEN in providers)
        assertTrue(ProviderKind.NVIDIA_NIM in providers)
        assertTrue(ProviderKind.CUSTOM in providers)
    }

    @Test
    fun qwenValidationUsesSelectedCustomWire() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.QWEN_CODE))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun qwenRuntimePathsAreExcludedFromChanges() {
        val tempDir = java.nio.file.Files.createTempDirectory("mh-qwen-ignore").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(tempDir)
            assertTrue(checkpoints.isInternalRuntimePath(".qwen/settings.json"))
            assertTrue(checkpoints.isInternalRuntimePath("app/.qwen/logs/session.jsonl"))
            assertFalse(checkpoints.isInternalRuntimePath("src/main/Main.kt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun settingsJsonPinsContextWindowOnTheProviderEntry() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 16_384,
            autoCompactPercent = 85,
            subagentsEnabled = false,
        )
        val entry = settings.getJSONObject("modelProviders")
            .getJSONArray("openai")
            .getJSONObject(0)
        assertEquals(16_384, entry.getJSONObject("generationConfig").getInt("contextWindowSize"))
    }

    @Test
    fun settingsJsonOmitsZeroContextWindowSoQwenGuesses() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = false,
        )
        val entry = settings.getJSONObject("modelProviders")
            .getJSONArray("openai")
            .getJSONObject(0)
        assertFalse(entry.has("generationConfig"))
    }

    @Test
    fun settingsJsonConvertsAutoCompactPercentToFraction() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 8_192,
            autoCompactPercent = 70,
            subagentsEnabled = false,
        )
        assertEquals(0.7, settings.getJSONObject("context").getDouble("autoCompactThreshold"), 0.0001)
    }

    @Test
    fun settingsJsonUnregistersSubagentToolsWhenDisabled() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = false,
        )
        val disabled = settings.getJSONObject("tools").getJSONArray("disabled")
        val disabledNames = (0 until disabled.length()).map(disabled::getString)
        for (tool in listOf("agent", "list_agents", "send_message", "task_stop")) {
            assertTrue("missing $tool", disabledNames.contains(tool))
        }
        assertEquals(4, disabled.length())
        assertFalse(settings.getJSONObject("model").has("maxSubagentDepth"))
    }

    @Test
    fun settingsJsonKeepsSubagentsFlatWhenEnabled() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = true,
        )
        assertEquals(1, settings.getJSONObject("model").getInt("maxSubagentDepth"))
        assertFalse(settings.getJSONObject("tools").has("disabled"))
    }

    @Test
    fun launchEnvironmentCapsSubagentsOnlyWhenEnabled() {
        val disabled = qwenLaunchEnvironment("OPENAI_API_KEY", "secret", false, 10)
        assertFalse(disabled.containsKey("QWEN_CODE_MAX_BACKGROUND_AGENTS"))
        assertEquals("secret", disabled["OPENAI_API_KEY"])

        val enabled = qwenLaunchEnvironment("OPENAI_API_KEY", "secret", true, 3)
        assertEquals("3", enabled["QWEN_CODE_MAX_BACKGROUND_AGENTS"])

        val floored = qwenLaunchEnvironment("OPENAI_API_KEY", "secret", true, 0)
        assertEquals("1", floored["QWEN_CODE_MAX_BACKGROUND_AGENTS"])
    }

    @Test
    fun historyBudgetScalesWithConfiguredContextWindow() {
        assertEquals(160_000, qwenHistoryCharacterBudget(0))
        assertEquals(32_768, qwenHistoryCharacterBudget(16_384))
        assertEquals(4_000, qwenHistoryCharacterBudget(1_024))
        assertEquals(160_000, qwenHistoryCharacterBudget(2_000_000))
    }

    @Test
    fun parentToolUseIdIdentifiesSubagentEnvelopes() {
        assertNull(qwenParentToolUseId(JSONObject().put("type", "assistant")))
        assertNull(qwenParentToolUseId(JSONObject().put("parent_tool_use_id", JSONObject.NULL)))
        assertEquals("call-1", qwenParentToolUseId(JSONObject().put("parent_tool_use_id", "call-1")))
    }

    @Test
    fun tokenUsageSplitsPromptCacheAndCompletion() {
        val message = JSONObject().put(
            "usage",
            JSONObject()
                .put("input_tokens", 120)
                .put("cache_read_input_tokens", 3_400)
                .put("cache_creation_input_tokens", 80)
                .put("output_tokens", 45),
        )
        val usage = qwenTokenUsage(message)
        assertNotNull(usage)
        assertEquals(3_600, usage!!.promptTokens)
        assertEquals(3_480, usage.cachedTokens)
        assertEquals(45, usage.outputTokens)
        assertEquals(3_645, usage.totalTokens)
        assertNull(qwenTokenUsage(JSONObject()))
        assertNull(qwenTokenUsage(JSONObject().put("usage", JSONObject())))
    }

    // Envelopes below are byte-faithful captures from the shipped qwen-code
    // 0.25.3 CLI running a detached (background) agent.

    @Test
    fun taskStartedEnvelopeNamesDetachedSubagent() {
        val envelope = JSONObject(
            """{"type":"system","subtype":"task_started","session_id":"e98e",
               "parent_tool_use_id":null,
               "data":{"task_id":"Explore-call_agent_1","tool_use_id":"call_agent_1",
                       "description":"research X","subagent_type":"Explore"}}""",
        )
        val start = qwenTaskStarted(envelope.optJSONObject("data"))
        assertNotNull(start)
        assertEquals("call_agent_1", start!!.callId)
        assertEquals("research X · Explore", start.description)
        assertNull(qwenTaskStarted(JSONObject()))
        assertNull(qwenTaskStarted(JSONObject().put("tool_use_id", "  ")))
    }

    @Test
    fun taskNotificationCarriesTerminalStatusAndCumulativeTokens() {
        val notification = qwenTaskNotification(
            JSONObject(
                """{"task_id":"Explore-call_agent_1","tool_use_id":"call_agent_1",
                   "status":"completed",
                   "usage":{"total_tokens":1280,"tool_uses":3,"duration_ms":5400}}""",
            ),
        )
        assertNotNull(notification)
        assertEquals("call_agent_1", notification!!.callId)
        assertEquals("completed", notification.status)
        assertEquals(1_280, notification.totalTokens)
        assertEquals(3, notification.toolUses)
        assertEquals(5_400, notification.durationMs)
        assertNull(qwenTaskNotification(null))
    }

    @Test
    fun subagentMetaFileReportsRunningAndFinishedTasks() = withQwenTempDirectory { root ->
        val running = File(root, "agent-Explore-call_1.meta.json")
        running.writeText(
            """{"agentId":"Explore-call_1","agentType":"Explore","description":"research X",
               "toolUseId":"call_1","status":"running","isBackgrounded":true,"depth":0}""",
        )
        val meta = qwenSubagentMeta(running)
        assertNotNull(meta)
        assertEquals("call_1", meta!!.callId)
        assertEquals("research X · Explore", meta.description)
        assertFalse(meta.finished)
        File(root, "agent-Explore-call_1.meta.json").writeText(
            """{"toolUseId":"call_1","agentType":"Explore","description":"research X","status":"completed"}""",
        )
        assertTrue(qwenSubagentMeta(running)!!.finished)
        File(root, "broken.meta.json").writeText("{")
        assertNull(qwenSubagentMeta(File(root, "broken.meta.json")))
    }

    @Test
    fun metaFileScanSkipsStaleAndNonMetaFiles() = withQwenTempDirectory { root ->
        val session = File(File(root, "projects/-workspace-demo/subagents"), "32e4")
        check(session.mkdirs())
        val fresh = File(session, "agent-Explore-call_1.meta.json")
        fresh.writeText("{}")
        File(session, "agent-Explore-call_1.jsonl").writeText("{}")
        val stale = File(session, "agent-old-call_0.meta.json")
        stale.writeText("{}")
        stale.setLastModified(System.currentTimeMillis() - 24L * 3_600_000L)
        val found = qwenSubagentMetaFiles(File(root, "projects"), System.currentTimeMillis() - 60_000L)
        assertEquals(listOf(fresh), found)
    }

    @Test
    fun transcriptDeltaTracksTokensAndActivityPerAppend() = withQwenTempDirectory { root ->
        val transcript = File(root, "agent-Explore-call_1.jsonl")
        transcript.writeText(
            """{"uuid":"u1","type":"user","agentId":"Explore-call_1","isSidechain":true,"message":{"role":"user","parts":[{"text":"Research X and report the answer."}]}}""" + "\n",
        )
        val first = qwenSubagentTranscriptDelta(transcript, 0L)
        assertNotNull(first)
        assertEquals(0, first!!.usageTokens)
        assertEquals("Research X and report the answer.", first.activity)
        assertEquals(listOf("Prompt" to "Research X and report the answer."), first.entries.map { it.kind to it.text })
        assertNull(qwenSubagentTranscriptDelta(transcript, first.newOffset))
        transcript.appendText(
            """{"uuid":"u2","type":"assistant","agentId":"Explore-call_1","isSidechain":true,"message":{"role":"model","parts":[{"text":"Research complete: X is 42."}]},"usageMetadata":{"promptTokenCount":450,"candidatesTokenCount":40,"totalTokenCount":490}}""" + "\n",
        )
        val second = qwenSubagentTranscriptDelta(transcript, first.newOffset)
        assertNotNull(second)
        assertEquals(490, second!!.usageTokens)
        assertEquals("Research complete: X is 42.", second.activity)
        // A trailing half line (still being written) is held back for the next tick.
        transcript.appendText("""{"uuid":"u3","type":"assistant","message":{"role":"model","parts":[{"text":"half""")
        assertNull(qwenSubagentTranscriptDelta(transcript, second.newOffset))
        transcript.appendText("\"}]}}\n")
        val third = qwenSubagentTranscriptDelta(transcript, second.newOffset)
        assertEquals("half", third!!.activity)
        transcript.appendText(
            """{"uuid":"u4","type":"assistant","message":{"role":"model","parts":[{"text":"Let me check.","thought":true},{"text":"Final answer."}]}}""" + "\n",
        )
        val fourth = qwenSubagentTranscriptDelta(transcript, third.newOffset)
        assertEquals(
            listOf("Thinking" to "Let me check.", "Reply" to "Final answer."),
            fourth?.entries?.map { it.kind to it.text },
        )
        assertEquals(490, qwenSubagentTranscriptDelta(transcript, 0L)?.usageTokens)
    }

    private fun withQwenTempDirectory(block: (java.io.File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("mh-qwen-subagent").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun qwenTestRoute() = QwenRoute(
        authType = "openai",
        envKey = "OPENAI_API_KEY",
        baseUrl = "https://api.example.com/v1",
        model = "glm-5.3",
    )
    @Test
    fun settingsJsonOmitsHarnessTuningWhenUnset() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = false,
        )
        val model = settings.getJSONObject("model")
        assertFalse(model.has("reasoningEffort"))
        assertFalse(model.has("maxToolCalls"))
        assertFalse(settings.getJSONObject("tools").has("shell"))
    }

    @Test
    fun settingsJsonMapsEffortPickerIndexToWireName() {
        fun effortAt(index: Int) = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = false,
            reasoningEffort = index,
        ).getJSONObject("model").getString("reasoningEffort")
        assertEquals("low", effortAt(1))
        assertEquals("xhigh", effortAt(4))
        assertEquals("max", effortAt(5))
    }

    @Test
    fun settingsJsonEmitsToolCallBudgetAndShellTimeout() {
        val settings = qwenSettingsJson(
            route = qwenTestRoute(),
            maxSessionTurns = 25,
            contextWindowTokens = 0,
            autoCompactPercent = 85,
            subagentsEnabled = false,
            maxToolCalls = 40,
            shellTimeoutMs = 300_000,
        )
        assertEquals(40, settings.getJSONObject("model").getInt("maxToolCalls"))
        assertEquals(300_000, settings.getJSONObject("tools").getJSONObject("shell").getInt("defaultTimeoutMs"))
    }
}

