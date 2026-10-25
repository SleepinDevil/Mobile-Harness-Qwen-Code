package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun contextTokensSumPromptCacheAndCompletion() {
        val message = JSONObject().put(
            "usage",
            JSONObject()
                .put("input_tokens", 120)
                .put("cache_read_input_tokens", 3_400)
                .put("cache_creation_input_tokens", 80)
                .put("output_tokens", 45),
        )
        assertEquals(3_645, qwenContextTokens(message))
        assertEquals(0, qwenContextTokens(JSONObject()))
    }

    private fun qwenTestRoute() = QwenRoute(
        authType = "openai",
        envKey = "OPENAI_API_KEY",
        baseUrl = "https://api.example.com/v1",
        model = "glm-5.3",
    )
}

