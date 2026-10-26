package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.inferredDshApiForUrl
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs Alibaba's open Qwen Code CLI inside the PRoot guest.
 *
 * Qwen Code emits the same newline-delimited stream-json envelope as Claude Code
 * (`system`/`content_block_*`/`assistant`/`user`/`result`), so the event reducer is
 * a port of [ClaudeRuntimeBridge.consumeClaudeJsonEvent] with Qwen's own tool
 * names. Sessions are one-shot (`-p`): history is replayed through the prompt and
 * the structured `result` line is the authoritative completion signal.
 */
class QwenRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val maxSessionTurns: () -> Int = { DEFAULT_MAX_SESSION_TURNS },
    private val maxWallMinutes: () -> Int = { DEFAULT_MAX_WALL_MINUTES },
    private val contextWindowTokens: () -> Int = { DEFAULT_CONTEXT_WINDOW_TOKENS },
    private val autoCompactPercent: () -> Int = { DEFAULT_AUTO_COMPACT_PERCENT },
    private val subagentsEnabled: () -> Boolean = { DEFAULT_SUBAGENTS_ENABLED },
    private val maxConcurrentSubagents: () -> Int = { DEFAULT_MAX_CONCURRENT_SUBAGENTS },
    private val memoryEnabled: () -> Boolean = { DEFAULT_MEMORY_ENABLED },
    private val reasoningEffort: () -> Int = { 0 },
    private val maxToolCalls: () -> Int = { 0 },
    private val shellTimeoutMs: () -> Int = { 0 },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val runtimeLogs = RuntimeLogStore(File(context.filesDir, "runtime-logs"))
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val subagentDescriptions = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    /** Wall clock of the newest stdout envelope; the gap before `result` is hidden CLI work. */
    @Volatile private var lastStdoutEnvelopeAt = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L
    private var generatedCharsSinceUsage = 0
    private var lastEstimateEmittedTokens = 0
    private val subagentUsageBase = ConcurrentHashMap<String, Int>()
    private val subagentGenChars = ConcurrentHashMap<String, Int>()
    private val subagentEstEmitted = ConcurrentHashMap<String, Int>()
    /** Agents detached by `system/task_started`; their tool_result is a launch receipt, not a finish. */
    private val backgroundedSubagents = ConcurrentHashMap.newKeySet<String>()
    private val subagentPollStates = ConcurrentHashMap<String, SubagentPollState>()
    /** Launch-order number per subagent; attributes child tool rows as "(subagent N)". */
    private val subagentNumbers = ConcurrentHashMap<String, Int>()
    private val subagentCounter = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastStdoutEnvelopeAt = taskStartedAtElapsedRealtime
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        toolNames.clear()
        seenToolCalls.clear()
        subagentDescriptions.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        generatedCharsSinceUsage = 0
        lastEstimateEmittedTokens = 0
        subagentUsageBase.clear()
        subagentGenChars.clear()
        subagentEstEmitted.clear()
        backgroundedSubagents.clear()
        subagentPollStates.clear()
        subagentNumbers.clear()
        streamedText.clear()
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress(R.string.rt_bridge_starting, "Qwen Code")
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    AppStrings.get(context, R.string.vm_qwen_claude_unsupported),
                ),
            )
            return@withContext sessionId
        }
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API key is saved for ${provider.kind.title}.",
                ),
            )
            return@withContext sessionId
        }

        val sessionStartedAt = System.currentTimeMillis()
        val sessionLog = runtimeLogs.newLogFile(AgentKind.QWEN_CODE.stableId, projectSlug, sessionId)
        var openRouterGateway: OpenRouterRoutingGateway? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            // Qwen Code speaks the OpenAI wire format natively, so no format
            // translation gateway is needed; only OpenRouter's provider routing
            // body field requires the loopback proxy.
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret).start() else null
            val route = QwenRouteMapper.forProfile(provider, openRouterGateway?.url)
            writeQwenSettings(installed.rootfs, route)
            writeQwenMemory(installed.rootfs)
            val launchEnvironment = buildQwenEnvironment(route, secret)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)

            val command = buildList {
                add(QWEN_GUEST_PATH)
                add("-p")
                add(contextPrompt)
                add("--output-format")
                add("stream-json")
                add("--include-partial-messages")
                add("--yolo")
                add("--model")
                add(route.model)
                add("--max-session-turns")
                add(maxSessionTurns().toString())
                add("--max-wall-time")
                add("${maxWallMinutes()}m")
            }
            Log.d("QwenBridge", "Provider: ${provider.kind}, Model: ${route.model}, BaseUrl: ${route.baseUrl}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                launchEnvironment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                outputFile = sessionLog,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            coroutineScope {
                if (subagentsEnabled()) {
                    // A detached subagent streams NOTHING while it runs, so the
                    // CLI's per-task files under the guest HOME are polled once a
                    // second: that is what keeps the monitor badge, per-subagent
                    // context tokens, and activity lines live (>=1 Hz by design).
                    val qwenProjectsDir =
                        File(File(installed.rootfs, QWEN_HOME_GUEST_PATH.removePrefix("/")), "projects")
                    launch(Dispatchers.IO) {
                        while (process.isAlive) {
                            runCatching { pollSubagentTaskFiles(sessionId, qwenProjectsDir, sessionStartedAt) }
                            delay(SUBAGENT_POLL_INTERVAL_MS)
                        }
                    }
                }
                var lastDiagnostic = ""
                val pendingOutput = StringBuilder()
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                var outputOffset = 0L
                while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
                    val available = nativeProcess.outputFile.length() - outputOffset
                    if (available <= 0) {
                        delay(50)
                        continue
                    }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                        file.seek(outputOffset)
                        file.read(bytes)
                    }
                    if (count > 0) {
                        outputOffset += count
                        pendingOutput.append(bytes.decodeToString(0, count))
                        var newline = pendingOutput.indexOf("\n")
                        while (newline >= 0) {
                            val line = pendingOutput.substring(0, newline).trimEnd('\r')
                            pendingOutput.delete(0, newline + 1)
                            if (line.isNotBlank()) {
                                Log.d("QwenBridge", "OUTPUT: $line")
                                ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                                    process.destroyForcibly()
                                    throw ProviderSessionException(reason)
                                }
                                if (!consumeQwenEvent(sessionId, line)) lastDiagnostic = line.takeLast(500)
                            }
                            newline = pendingOutput.indexOf("\n")
                        }
                    }
                }
                pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let { line ->
                    Log.d("QwenBridge", "TRAILING OUTPUT: $line")
                    if (!consumeQwenEvent(sessionId, line)) lastDiagnostic = line.takeLast(500)
                }
                val exit = process.waitFor()
                Log.d("QwenBridge", "Process exited with code $exit")
                runtimeLogs.record(
                    sessionLog, AgentKind.QWEN_CODE.stableId, projectSlug, sessionStartedAt, exit,
                    failed = exit != 0 && !finishedSessions.contains(sessionId),
                )
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = loadPendingChanges(projectId)
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                    acceptLastChanges(projectId)
                }
                if (finishedSessions.contains(sessionId)) {
                    // The structured result line already completed the session; a
                    // late non-zero exit from the killed wrapper is not a failure.
                } else if (exit == 0) {
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = AppStrings.get(context, R.string.rt_bridge_finished_in, "Qwen Code", projectSlug),
                    )
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    error(lastDiagnostic.ifBlank { exitCodeHint(exit) ?: "Qwen Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            Log.e("QwenBridge", "Session failed", error)
            val message = friendlyError(error)
            runtimeLogs.record(sessionLog, AgentKind.QWEN_CODE.stableId, projectSlug, sessionStartedAt, null, failed = true, reason = message)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        openRouterGateway?.close()
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Sessions run with --yolo; Qwen Code never asks.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = runCatching {
            val array = JSONArray(File(checkpoint, "changes.json").readText())
            (0 until array.length()).map(array::getString)
        }.getOrElse { return@withContext false }.filterNot(checkpoints::isInternalRuntimePath)
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private suspend fun consumeQwenEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeQwenJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeQwenJsonEvent(sessionId: String, json: JSONObject) {
        // Subagent turns share this stdout stream with the main agent's, tagged
        // with parent_tool_use_id. They must never complete the session, feed the
        // chat text, or count as the main agent's own assistant end_turn; only
        // their tool calls are surfaced as attributed progress rows.

        // Stamp every stdout envelope: when `result` arrives after a long silence,
        // the gap is CLI-private post-turn work (auto-memory extraction/dreaming)
        // that streams nothing, and the chat deserves an explanation line.
        val envelopeAtMs = android.os.SystemClock.elapsedRealtime()
        val stdoutSilenceMs = (envelopeAtMs - lastStdoutEnvelopeAt).coerceAtLeast(0L)
        lastStdoutEnvelopeAt = envelopeAtMs
        val parentCallId = qwenParentToolUseId(json)
        if (parentCallId != null) {
            consumeSubagentJsonEvent(sessionId, parentCallId, json)
            return
        }
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeQwenJsonEvent(sessionId, it) }
            "system" -> when (json.optString("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.optString("decision_reason").ifBlank { json.optString("message") }),
                    ),
                )
                "task_started" -> consumeQwenTaskStarted(sessionId, json.optJSONObject("data"))
                "task_notification" -> consumeQwenTaskNotification(sessionId, json.optJSONObject("data"))
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.optString("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let {
                        generatedCharsSinceUsage += it.length
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        generatedCharsSinceUsage += it.length
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
                maybeEmitContextEstimate(sessionId)
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.optString("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                qwenTokenUsage(message)?.let { usage ->
                    generatedCharsSinceUsage = 0
                    lastEstimateEmittedTokens = 0
                    eventBus.emit(
                        RuntimeEvent.ContextUsage(
                            sessionId,
                            usage.totalTokens,
                            usage.promptTokens,
                            usage.cachedTokens,
                            usage.outputTokens,
                        ),
                    )
                }
                streamedText.clear()
                // Some providers omit the final `result` envelope. An assistant
                // end_turn is still authoritative; tool_use means another turn follows.
                if (message.optString("stop_reason") == "end_turn") {
                    emitCompletedOnce(sessionId)
                    terminateActiveProcessGracefully()
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val result = block.optString("content")
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(
                            RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result), fullDetail = sanitizeFull(result)),
                        )
                        if (toolName == "agent" && !backgroundedSubagents.remove(toolId) &&
                            subagentDescriptions.remove(toolId) != null
                        ) {
                            // Foreground agent: the result is the finish. Background
                            // (detached) agents keep running; their launch receipt must
                            // NOT mark them done — `task_notification` finishes those.
                            eventBus.emit(RuntimeEvent.SubagentFinished(sessionId, toolId))
                        }
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.optString("result").ifBlank { "Qwen Code reported an error" }
                    throw IllegalStateException(message)
                }
                // `result.usage` sums every call the CLI made, including the
                // invisible post-turn ones; surface it so hidden cost is visible.
                if (stdoutSilenceMs >= HIDDEN_FINALIZATION_WARN_MS) {
                    eventBus.emit(
                        RuntimeEvent.RuntimeLog(
                            sessionId,
                            AppStrings.get(context, R.string.rt_qwen_finalizing_title),
                            AppStrings.get(context, R.string.rt_qwen_finalizing_detail, stdoutSilenceMs / 1000),
                        ),
                    )
                }
                qwenTokenUsage(json)?.let { usage ->
                    eventBus.emit(
                        RuntimeEvent.RuntimeLog(
                            sessionId,
                            AppStrings.get(context, R.string.rt_qwen_usage_title),
                            AppStrings.get(
                                context,
                                R.string.rt_qwen_usage_detail,
                                usage.promptTokens,
                                usage.outputTokens,
                                usage.cachedTokens,
                            ),
                        ),
                    )
                }
                // The structured result is Qwen Code's authoritative terminal event.
                // Update the UI immediately instead of waiting for a PRoot/Node
                // wrapper that may remain alive after the answer completed.
                emitCompletedOnce(sessionId)
                terminateActiveProcessGracefully()
            }
        }
    }

    /**
     * Surfaces subagent tool activity as attributed progress rows and collects the
     * subagent's thinking/reply blocks into a reviewable transcript. The subagent's
     * own end_turn is intentionally dropped: honouring it would complete (and kill)
     * the whole session while the main agent is still working.
     */
    private suspend fun consumeSubagentJsonEvent(sessionId: String, parentCallId: String, json: JSONObject) {
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeSubagentJsonEvent(sessionId, parentCallId, it) }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return
                val text = delta.optString("text").ifEmpty { delta.optString("thinking") }
                if (text.isNotEmpty()) maybeEmitSubagentEstimate(sessionId, parentCallId, text.length)
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "tool_use" -> {
                            val (name, detail) = emitToolStarted(sessionId, block, subagentParentCallId = parentCallId)
                            eventBus.emit(
                                RuntimeEvent.SubagentActivity(
                                    sessionId,
                                    parentCallId,
                                    subagentDescriptions[parentCallId] ?: "Subagent",
                                    listOf(name, detail).filter(String::isNotBlank).joinToString(" · "),
                                ),
                            )
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            eventBus.emit(RuntimeEvent.SubagentThinking(sessionId, parentCallId, sanitizeFull(it)))
                        }
                        "text" -> block.optString("text").takeIf(String::isNotBlank)?.let {
                            eventBus.emit(RuntimeEvent.SubagentReply(sessionId, parentCallId, sanitizeFull(it)))
                        }
                    }
                }
                qwenTokenUsage(message)?.let { usage ->
                    subagentUsageBase[parentCallId] = usage.promptTokens
                    subagentGenChars.remove(parentCallId)
                    subagentEstEmitted.remove(parentCallId)
                    eventBus.emit(RuntimeEvent.SubagentUsage(sessionId, parentCallId, usage.totalTokens))
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Subagent tool"
                        val result = block.optString("content")
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(
                            RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result), fullDetail = sanitizeFull(result)),
                        )
                    }
                }
            }
        }
    }

    /**
     * `system/task_started`: the agent tool detached its subagent (background
     * mode, always on because the bridge exports QWEN_CODE_MAX_BACKGROUND_AGENTS).
     * From here the subagent's `agent` tool_result is only a launch receipt — the
     * real finish signal is the matching `system/task_notification`.
     */
    private suspend fun consumeQwenTaskStarted(sessionId: String, data: JSONObject?) {
        val started = qwenTaskStarted(data) ?: return
        backgroundedSubagents.add(started.callId)
        subagentNumber(started.callId)
        if (!subagentDescriptions.containsKey(started.callId)) {
            subagentDescriptions[started.callId] = started.description
            eventBus.emit(RuntimeEvent.SubagentStarted(sessionId, started.callId, started.description))
        }
    }

    /**
     * `system/task_notification`: terminal status of a detached subagent,
     * including the cumulative token usage the live stream never exposes.
     */
    private suspend fun consumeQwenTaskNotification(sessionId: String, data: JSONObject?) {
        val notification = qwenTaskNotification(data) ?: return
        val state = subagentPollStates.getOrPut(notification.callId) { SubagentPollState() }
        if (notification.totalTokens > 0 && notification.totalTokens != state.lastUsageTokens) {
            state.lastUsageTokens = notification.totalTokens
            eventBus.emit(RuntimeEvent.SubagentUsage(sessionId, notification.callId, notification.totalTokens))
        }
        val description = subagentDescriptions[notification.callId] ?: "Subagent"
        val summary = buildString {
            append(notification.status.replaceFirstChar(Char::titlecase))
            if (notification.toolUses > 0) append(" · ${notification.toolUses} tool calls")
            if (notification.durationMs > 0) append(" · ${(notification.durationMs + 999) / 1000}s")
        }
        eventBus.emit(RuntimeEvent.SubagentActivity(sessionId, notification.callId, description, summary))
        backgroundedSubagents.remove(notification.callId)
        state.finished = true
        if (subagentDescriptions.remove(notification.callId) != null) {
            eventBus.emit(RuntimeEvent.SubagentFinished(sessionId, notification.callId))
        }
    }

    /**
     * One subagent-monitor refresh tick: while a detached subagent runs, stdout
     * carries nothing about it, so the CLI's per-task files under `/root/.qwen`
     * are polled once per second for status, per-round token usage, and a fresh
     * activity line. Also the safety net when `task_started`/`task_notification`
     * envelopes are missed (status stays honest from the meta file alone).
     */
    private suspend fun pollSubagentTaskFiles(sessionId: String, projectsDir: File, startedAtMillis: Long) {
        for (metaFile in qwenSubagentMetaFiles(projectsDir, startedAtMillis)) {
            val meta = qwenSubagentMeta(metaFile) ?: continue
            val state = subagentPollStates.getOrPut(meta.callId) { SubagentPollState() }
            if (!state.started) {
                state.started = true
                subagentNumber(meta.callId)
                if (subagentDescriptions.putIfAbsent(meta.callId, meta.description) == null) {
                    eventBus.emit(RuntimeEvent.SubagentStarted(sessionId, meta.callId, meta.description))
                }
            }
            if (state.finished) continue
            val transcript = File(metaFile.parentFile, metaFile.name.removeSuffix(".meta.json") + ".jsonl")
            qwenSubagentTranscriptDelta(transcript, state.offset)?.let { delta ->
                state.offset = delta.newOffset
                if (delta.usageTokens > 0 && delta.usageTokens != state.lastUsageTokens) {
                    state.lastUsageTokens = delta.usageTokens
                    eventBus.emit(RuntimeEvent.SubagentUsage(sessionId, meta.callId, delta.usageTokens))
                }
                delta.entries.forEach { entry ->
                    when (entry.kind) {
                        "Prompt" -> eventBus.emit(RuntimeEvent.SubagentPrompt(sessionId, meta.callId, entry.text))
                        "Thinking" -> eventBus.emit(RuntimeEvent.SubagentThinking(sessionId, meta.callId, entry.text))
                        else -> eventBus.emit(RuntimeEvent.SubagentReply(sessionId, meta.callId, entry.text))
                    }
                }
                delta.activity?.let {
                    eventBus.emit(
                        RuntimeEvent.SubagentActivity(
                            sessionId,
                            meta.callId,
                            subagentDescriptions[meta.callId] ?: "Subagent",
                            it,
                        ),
                    )
                }
            }
            if (meta.finished) {
                state.finished = true
                backgroundedSubagents.remove(meta.callId)
                if (subagentDescriptions.remove(meta.callId) != null) {
                    eventBus.emit(RuntimeEvent.SubagentFinished(sessionId, meta.callId))
                }
            }
        }
    }

    /** Per-subagent poll bookkeeping: transcript read offset and emit dedupe. */
    private class SubagentPollState(
        var offset: Long = 0L,
        var started: Boolean = false,
        var finished: Boolean = false,
        var lastUsageTokens: Int = 0,
    )

    /**
     * Emits a live token estimate while the model is generating: streamed chars
     * since the last reported usage, throttled to >=50-token steps so the gauge
     * ticks smoothly without flooding the UI; exact [RuntimeEvent.ContextUsage]
     * replaces it the moment the call completes.
     */
    private suspend fun maybeEmitContextEstimate(sessionId: String) {
        val estimate = generatedCharsSinceUsage / HISTORY_CHARACTERS_PER_CONTEXT_TOKEN
        if (estimate >= lastEstimateEmittedTokens + 50) {
            lastEstimateEmittedTokens = estimate
            eventBus.emit(RuntimeEvent.ContextEstimate(sessionId, estimate))
        }
    }

    /** Subagent counterpart: last call's prompt tokens plus streamed generation. */
    private suspend fun maybeEmitSubagentEstimate(sessionId: String, callId: String, chars: Int) {
        val base = subagentUsageBase[callId] ?: return
        val totalChars = ((subagentGenChars[callId] ?: 0) + chars).also { subagentGenChars[callId] = it }
        val estimate = base + totalChars / HISTORY_CHARACTERS_PER_CONTEXT_TOKEN
        if (estimate >= (subagentEstEmitted[callId] ?: 0) + 50) {
            subagentEstEmitted[callId] = estimate
            eventBus.emit(RuntimeEvent.SubagentUsage(sessionId, callId, estimate))
        }
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                    fullText = sanitizeFull(text),
                ),
            )
            pushForegroundProgress(R.string.rt_bridge_thinking)
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress(R.string.rt_bridge_thinking)
        }
    }

    /** Display order of one subagent (1-based, assigned at first sight). */
    private fun subagentNumber(callId: String): Int =
        subagentNumbers.getOrPut(callId) { subagentCounter.incrementAndGet() }

    /** Emits the progress row for one tool_use block and returns (name, detail) for subagent attribution. */
    private suspend fun emitToolStarted(sessionId: String, block: JSONObject, subagentParentCallId: String? = null): Pair<String, String> {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return block.optString("name", "Tool") to ""
        val name = block.optString("name", "Tool")
        // The ViewModel matches ToolCompleted rows by tool name, so store and emit
        // the same attributed ("… (subagent N)") label for child calls.
        val displayName = subagentParentCallId?.let { "$name (subagent ${subagentNumber(it)})" } ?: name
        if (id.isNotBlank()) toolNames[id] = displayName
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "run_shell_command" -> input.optString("command").ifBlank { input.optString("description") }
            "write_file", "read_file", "edit", "read_many_files", "replace" ->
                input.optString("file_path").ifBlank { input.optString("paths").ifBlank { input.optString("notebook_path") } }
            "glob" -> input.optString("pattern")
            "grep", "grep_search", "search_file_content" -> input.optString("pattern").let { pattern ->
                input.optString("dir_path").takeIf(String::isNotBlank)?.let { "$pattern in $it" }
                    ?: input.optString("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" }
                    ?: pattern
            }
            "web_fetch", "websearch" -> input.optString("url").ifBlank { input.optString("query") }
            "list_directory" -> input.optString("dir_path")
            "agent" -> input.optString("description").ifBlank { "Launching subagent" }.let { description ->
                input.optString("subagent_type").takeIf(String::isNotBlank)?.let { "$description · $it" } ?: description
            }
            else -> input.optString("description").ifBlank { "Running $name" }
        }
        if (subagentParentCallId == null && name == "agent" && id.isNotBlank()) {
            // The agent-tool call spawns one live subagent; its child turns stream
            // back tagged with this call id, so remember the ask for attribution.
            subagentDescriptions[id] = detail
            eventBus.emit(RuntimeEvent.SubagentStarted(sessionId, id, detail))
            subagentNumber(id)
        }
        val rawDetail = detail.ifBlank { "Running $displayName" }
        eventBus.emit(
            RuntimeEvent.ToolStarted(sessionId, displayName, sanitizeForDisplay(rawDetail), fullDetail = sanitizeFull(rawDetail)),
        )
        pushForegroundProgress(R.string.rt_bridge_running_tool, displayName, detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { displayName })
        return name to detail
    }

    private fun exitCodeHint(exit: Int): String? = when (exit) {
        53 -> "Qwen Code reached its ${maxSessionTurns()}-turn limit before finishing. Raise the turn limit in Settings → Harness settings or narrow the request and try again."
        55 -> "Qwen Code hit the ${maxWallMinutes()}-minute wall-time or tool-call budget. Raise it in Settings → Harness settings or narrow the request and try again."
        else -> null
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            // Post the completion notification immediately. Waiting for process
            // teardown is unsafe: the PRoot/Node wrapper can hang after the answer
            // is already done, which would freeze the notification on its last step.
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                detail = AppStrings.get(context, R.string.rt_bridge_finished, "Qwen Code"),
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                    detail = reason,
                )
            }
        }
    }

    private fun sanitizeForDisplay(value: String): String = redactSecrets(value)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(600)

    /**
     * Redacted, newline-preserving copy of [value] for the long-press "full detail"
     * dialog: same secret scrubbing as [sanitizeForDisplay] but no whitespace
     * collapsing and a generous cap so real output survives.
     */
    private fun sanitizeFull(value: String): String =
        if (value.isBlank()) "" else redactSecrets(value).trim().take(100_000)

    private fun redactSecrets(value: String): String = value
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
        .replace(Regex("(?i)(authorization|api[_-]?key)\\s*[:=]\\s*[^\\s,}]+"), "${'$'}1: ••••")
        .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]{12,}={0,2}", RegexOption.IGNORE_CASE), "Bearer ••••")

    /** Per-session Qwen Code settings: provider route, yolo approvals, hard limits. */
    private fun writeQwenSettings(rootfs: File, route: QwenRoute) {
        val home = File(rootfs, QWEN_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        val settings = qwenSettingsJson(
            route = route,
            maxSessionTurns = maxSessionTurns(),
            contextWindowTokens = contextWindowTokens(),
            autoCompactPercent = autoCompactPercent(),
            subagentsEnabled = subagentsEnabled(),
            reasoningEffort = reasoningEffort(),
            maxToolCalls = maxToolCalls(),
            memoryEnabled = memoryEnabled(),
            shellTimeoutMs = shellTimeoutMs(),
        )
        File(home, "settings.json").writeText(settings.toString(2))
    }

    /** Teaches Qwen Code about the PRoot/Android toolchain limits every session. */
    private fun writeQwenMemory(rootfs: File) {
        val home = File(rootfs, QWEN_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        File(home, "QWEN.md").writeText(
            """
            # PocketDev runtime
            - You run inside PRoot (userspace isolation, no root, no Docker, no KVM, no systemd).
            - The project root is /workspace/<project>; keep every file and command inside it.
            - The file-editing tool is named `edit`. There is no `edit_file` tool; call `edit`
              directly instead of guessing tool names.
            - Android builds: JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3 and an
              offline Maven repository are preinstalled. Use the installed `gradle` command.
            - Newly created Android projects: AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, Java 17,
              android.useAndroidX=true. The ARM64 aapt2 is configured globally; never add
              android.aapt2FromMavenOverride.
            - Never ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.
            - Long-running dev servers are fine; never use a kill command whose pattern matches
              itself (pgrep -f matching its own text can kill the session).
            """.trimIndent() + "\n",
        )
    }

    private fun buildQwenEnvironment(route: QwenRoute, secret: String): Map<String, String> = qwenLaunchEnvironment(
        envKey = route.envKey,
        secret = secret,
        subagentsEnabled = subagentsEnabled(),
        maxConcurrentSubagents = maxConcurrentSubagents(),
    )

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        // Filter out the current prompt (last user message), system greeting, and any error messages
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1) // Drop the current prompt which was just added
            .recentWithinCharacterBudget(qwenHistoryCharacterBudget(contextWindowTokens()))

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
        sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
        sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
        sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
        sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Qwen Code runtime could not start."
            else -> message.take(500)
        }
    }

    /** Localized variant; resolves the string only when the throttle allows an update. */
    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        if (activeSessionId == null) return
        if (android.os.SystemClock.elapsedRealtime() - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /**
     * Destroys the CLI process, escalating to a force kill if the PRoot/Node
     * wrapper ignores the graceful signal. Without this, a hung wrapper would
     * block session cleanup forever after the answer was already delivered.
     */
    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        // The first completion/failure post wins; later cleanup must not duplicate it.
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("QwenBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val QWEN_HOME_GUEST_PATH = "/root/.qwen"
        private const val QWEN_GUEST_PATH = RuntimeInstaller.QWEN_GUEST_PATH
        const val DEFAULT_MAX_SESSION_TURNS = 25
        const val DEFAULT_MAX_WALL_MINUTES = 30
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        /** 0 lets Qwen Code guess the context window (200K fallback for unknown models). */
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 0
        const val DEFAULT_AUTO_COMPACT_PERCENT = 85
        const val DEFAULT_SUBAGENTS_ENABLED = false
        const val DEFAULT_MEMORY_ENABLED = false
        const val DEFAULT_MAX_CONCURRENT_SUBAGENTS = 10
    }
}

/** Qwen provider route resolved from a saved provider profile. */
internal data class QwenRoute(
    val authType: String,
    val envKey: String,
    val baseUrl: String,
    val model: String,
    val wireApi: String? = null,
)

/**
 * Maps PocketDev provider profiles onto Qwen Code's `modelProviders` buckets.
 * Anthropic-format providers use the `anthropic` auth type; OpenAI-format ones
 * use `openai` (with the per-model `responses` wire API where required).
 */
internal object QwenRouteMapper {
    fun forProfile(profile: ProviderProfile, gatewayUrl: String?): QwenRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.CLAUDE -> error("Qwen Code cannot use the Claude subscription login")
            ProviderKind.ANTHROPIC, ProviderKind.DEEPSEEK, ProviderKind.KIMI -> QwenRoute(
                authType = "anthropic",
                envKey = "ANTHROPIC_API_KEY",
                baseUrl = profile.resolvedBaseUrl.trimEnd('/'),
                model = model,
            )
            ProviderKind.LLM_ROUTER -> QwenRoute(
                authType = "openai",
                envKey = "OPENAI_API_KEY",
                baseUrl = (gatewayUrl ?: profile.resolvedBaseUrl.trimEnd('/')) + "/v1",
                model = model,
            )
            ProviderKind.OPENCODE_ZEN -> QwenRoute(
                authType = "openai",
                envKey = "OPENAI_API_KEY",
                baseUrl = profile.resolvedBaseUrl.trimEnd('/'),
                model = model,
                wireApi = "responses",
            )
            ProviderKind.NVIDIA_NIM -> QwenRoute(
                authType = "openai",
                envKey = "OPENAI_API_KEY",
                baseUrl = profile.resolvedBaseUrl.trimEnd('/'),
                model = model,
            )
            ProviderKind.CUSTOM -> {
                val api = profile.dshApi.ifBlank { inferredDshApiForUrl(profile.resolvedBaseUrl) }
                when (api) {
                    "anthropic-messages" -> QwenRoute(
                        authType = "anthropic",
                        envKey = "ANTHROPIC_API_KEY",
                        baseUrl = profile.resolvedBaseUrl.trimEnd('/'),
                        model = model,
                    )
                    "openai-responses" -> QwenRoute(
                        authType = "openai",
                        envKey = "OPENAI_API_KEY",
                        baseUrl = openAiRoot(profile.resolvedBaseUrl),
                        model = model,
                        wireApi = "responses",
                    )
                    else -> QwenRoute(
                        authType = "openai",
                        envKey = "OPENAI_API_KEY",
                        baseUrl = openAiRoot(profile.resolvedBaseUrl),
                        model = model,
                    )
                }
            }
        }
    }

    /** Qwen appends /chat/completions or /responses itself; normalize to the /v1 root. */
    private fun openAiRoot(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses")) {
            if (base.endsWith(suffix)) base = base.removeSuffix(suffix).trimEnd('/')
        }
        return if (base.endsWith("/v1")) base else "$base/v1"
    }
}

/**
 * Assembles the per-session `/root/.qwen/settings.json` payload. The context
 * window override lives inside the `modelProviders` entry because provider
 * model entries are sealed: a top-level `model.generationConfig` never fills
 * missing fields for a provider-defined model.
 */
internal fun qwenSettingsJson(
    route: QwenRoute,
    maxSessionTurns: Int,
    contextWindowTokens: Int,
    autoCompactPercent: Int,
    subagentsEnabled: Boolean,
    reasoningEffort: Int = 0,
    maxToolCalls: Int = 0,
    shellTimeoutMs: Int = 0,
    memoryEnabled: Boolean = false,
): JSONObject {
    val providerEntry = JSONObject()
        .put("id", route.model)
        .put("envKey", route.envKey)
        .put("baseUrl", route.baseUrl)
    route.wireApi?.let { providerEntry.put("wireApi", it) }
    if (contextWindowTokens > 0) {
        providerEntry.put(
            "generationConfig",
            JSONObject().put("contextWindowSize", contextWindowTokens),
        )
    }
    val modelSettings = JSONObject()
        .put("name", route.model)
        .put("maxSessionTurns", maxSessionTurns)
    // Effort index 0 omits the key so the provider default applies; 1..n select
    // the canonical wire names below. maxToolCalls 0 also omits: Qwen's own
    // default is no cumulative tool-call budget.
    QWEN_REASONING_EFFORTS.getOrNull(reasoningEffort - 1)?.let { modelSettings.put("reasoningEffort", it) }
    if (maxToolCalls > 0) modelSettings.put("maxToolCalls", maxToolCalls)
    val toolsSettings = JSONObject().put("approvalMode", "yolo")
    if (subagentsEnabled) {
        // Subagents stay flat: nested delegation multiplies model calls and was
        // the main source of runaway subagent sessions on phone-class runtimes.
        modelSettings.put("maxSubagentDepth", 1)
    } else {
        // tools.disabled unregisters the tools, so they are invisible to the
        // model and unreachable even through the tool_search/tool_call bridge.
        toolsSettings.put("disabled", JSONArray(QWEN_SUBAGENT_CONTROL_TOOLS))
    }
    // 0 keeps the CLI default (120 s foreground shell timeout); the explicit
    // timeout-disabled spelling (0 in tools.shell.defaultTimeoutMs) stays hidden.
    if (shellTimeoutMs > 0) {
        toolsSettings.put("shell", JSONObject().put("defaultTimeoutMs", shellTimeoutMs))
    }
    return JSONObject()
        .put(
            "security",
            JSONObject()
                .put("auth", JSONObject().put("selectedType", route.authType))
                .put("folderTrust", JSONObject().put("enabled", false)),
        )
        .put("model", modelSettings)
        .put(
            "context",
            JSONObject().put("autoCompactThreshold", autoCompactPercent.coerceIn(5, 100) / 100.0),
        )
        .put("tools", toolsSettings)
        // Qwen Code defaults managed auto-memory and auto-dream to true: they add
        // private model calls after every turn plus an always-on memory corpus to
        // each request. PocketDev opts out unless the user enables it in Settings.
        .put(
            "memory",
            JSONObject()
                .put("enableManagedAutoMemory", memoryEnabled)
                .put("enableManagedAutoDream", memoryEnabled),
        )
        .put("telemetry", JSONObject().put("enabled", false))
        .put("privacy", JSONObject().put("usageStatisticsEnabled", false))
        .put("experimental", JSONObject().put("cron", false).put("artifact", false))
        .put("modelProviders", JSONObject().put(route.authType, JSONArray().put(providerEntry)))
}

/** Subagent control-plane tools removed when subagents are turned off. */
internal val QWEN_SUBAGENT_CONTROL_TOOLS = listOf("agent", "list_agents", "send_message", "task_stop")

/** Qwen Code `model.reasoningEffort` wire values, ordered by Settings picker index (1-based). */
internal val QWEN_REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

/** Launch environment; caps concurrent background subagents when they are allowed. */
internal fun qwenLaunchEnvironment(
    envKey: String,
    secret: String,
    subagentsEnabled: Boolean,
    maxConcurrentSubagents: Int,
): Map<String, String> = buildMap {
    put(envKey, secret)
    put("QWEN_TELEMETRY_ENABLED", "0")
    put("QWEN_USAGE_STATISTICS_ENABLED", "0")
    put("QWEN_CODE_UNATTENDED_RETRY", "1")
    put("QWEN_CODE_SUPPRESS_YOLO_WARNING", "1")
    put("QWEN_CODE_DISABLE_CRON", "1")
    put("QWEN_CODE_DISABLE_ARTIFACT", "1")
    put("NO_COLOR", "1")
    put("NODE_OPTIONS", "--max-old-space-size=1024")
    if (subagentsEnabled) {
        put("QWEN_CODE_MAX_BACKGROUND_AGENTS", maxConcurrentSubagents.coerceAtLeast(1).toString())
    }
}

/** Parsed `system/task_started` payload: a subagent just detached and went live. */
internal data class QwenTaskStart(val callId: String, val description: String)

internal fun qwenTaskStarted(data: JSONObject?): QwenTaskStart? {
    if (data == null) return null
    val callId = data.optString("tool_use_id")
    if (callId.isBlank()) return null
    return QwenTaskStart(callId, qwenSubagentDescription(data.optString("description"), data.optString("subagent_type")))
}

/** Parsed `system/task_notification`: terminal status + cumulative usage. */
internal data class QwenTaskNotification(
    val callId: String,
    val status: String,
    val totalTokens: Int,
    val toolUses: Int,
    val durationMs: Int,
)

internal fun qwenTaskNotification(data: JSONObject?): QwenTaskNotification? {
    if (data == null) return null
    val callId = data.optString("tool_use_id")
    if (callId.isBlank()) return null
    val usage = data.optJSONObject("usage")
    return QwenTaskNotification(
        callId = callId,
        status = data.optString("status").ifBlank { "finished" },
        totalTokens = usage?.optInt("total_tokens") ?: 0,
        toolUses = usage?.optInt("tool_uses") ?: 0,
        durationMs = usage?.optInt("duration_ms") ?: 0,
    )
}

/** Task statuses that mean the subagent is no longer running. */
private val QWEN_TASK_FINISHED_STATUSES =
    setOf("completed", "complete", "failed", "cancelled", "canceled", "stopped", "killed", "error")

/** Parsed `agent-*.meta.json` written per detached subagent under `~/.qwen`. */
internal data class QwenSubagentMeta(val callId: String, val description: String, val finished: Boolean)

internal fun qwenSubagentMeta(metaFile: File): QwenSubagentMeta? = runCatching {
    val json = JSONObject(metaFile.readText())
    val callId = json.optString("toolUseId")
    if (callId.isBlank()) return null
    QwenSubagentMeta(
        callId = callId,
        description = qwenSubagentDescription(json.optString("description"), json.optString("agentType")),
        finished = json.optString("status").lowercase() in QWEN_TASK_FINISHED_STATUSES,
    )
}.getOrNull()

/**
 * Detached-subagent meta files from this app start onwards. Qwen Code writes
 * `projects/<cwd-slug>/subagents/<session>/agent-<task-id>.meta.json`; the recency
 * filter keeps stale files of older sessions (e.g. crashed runs still marked
 * `running`) out of the live monitor.
 */
internal fun qwenSubagentMetaFiles(projectsDir: File, notBeforeMillis: Long): List<File> =
    projectsDir.listFiles()?.flatMap { project ->
        File(project, "subagents").listFiles()?.flatMap { session ->
            session.listFiles()?.filter {
                it.isFile && it.name.startsWith("agent-") && it.name.endsWith(".meta.json")
            }.orEmpty()
        }.orEmpty()
    }.orEmpty().filter { it.lastModified() >= notBeforeMillis - SUBAGENT_POLL_RECENTNESS_GRACE_MS }

/** One subagent-transcript poll tick: [newOffset] plus whatever the new lines carry. */
internal data class QwenSubagentDelta(
    val newOffset: Long,
    val usageTokens: Int,
    val activity: String?,
    val entries: List<QwenSubagentEntry> = emptyList(),
)

/** A complete transcript record classified for the per-subagent transcript view. */
internal data class QwenSubagentEntry(val kind: String, val text: String)

/**
 * Incrementally reads a detached subagent's append-only transcript (one complete
 * JSON record per line: prompt, thinking, tool calls, replies). Reports the next
 * byte offset, the latest cumulative token count, a one-line activity label, and
 * every complete record since [offset] classified into prompt/thinking/reply
 * entries. A trailing half-written line (the CLI is mid-append) is held back for
 * the next tick; a truncation restarts at 0.
 */
internal fun qwenSubagentTranscriptDelta(transcript: File, offset: Long): QwenSubagentDelta? {
    val length = transcript.length()
    if (length == 0L) return null
    val start = if (offset > length) 0L else offset
    if (length <= start) return null
    val count = minOf(length - start, SUBAGENT_TRANSCRIPT_POLL_CAP_BYTES.toLong()).toInt()
    val bytes = runCatching {
        RandomAccessFile(transcript, "r").use { file ->
            file.seek(start)
            val buffer = ByteArray(count)
            val read = file.read(buffer)
            if (read < count) buffer.copyOf(read.coerceAtLeast(0)) else buffer
        }
    }.getOrNull() ?: return null
    var cut = -1
    for (index in bytes.size - 1 downTo 0) {
        if (bytes[index] == '\n'.code.toByte()) {
            cut = index
            break
        }
    }
    if (cut < 0) return null
    var usageTokens = 0
    var activity: String? = null
    val entries = mutableListOf<QwenSubagentEntry>()
    String(bytes, 0, cut + 1, Charsets.UTF_8).lineSequence().forEach { raw ->
        if (raw.isBlank()) return@forEach
        val record = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
        record.optJSONObject("usageMetadata")
            ?.takeUnless { it.isNull("totalTokenCount") }
            ?.optInt("totalTokenCount")
            ?.takeIf { it > 0 }
            ?.let { usageTokens = it }
        val parts = record.optJSONObject("message")?.optJSONArray("parts") ?: return@forEach
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            part.optJSONObject("functionCall")?.optString("name")?.takeIf(String::isNotBlank)?.let {
                activity = "Using $it"
                return@forEach
            }
            part.optString("text").takeIf(String::isNotBlank)?.let { text ->
                activity = qwenSubagentPreview(text)
                if (!part.optBoolean("functionResponse")) {
                    entries +=
                        when {
                            part.optBoolean("thought") -> QwenSubagentEntry("Thinking", text)
                            record.optString("type") == "user" -> QwenSubagentEntry("Prompt", text)
                            else -> QwenSubagentEntry("Reply", text)
                        }
                }
            }
        }
    }
    return QwenSubagentDelta(start + cut + 1L, usageTokens, activity, entries)
}

private fun qwenSubagentPreview(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().let {
        if (it.length > 70) it.take(70).trimEnd() + "…" else it
    }

private fun qwenSubagentDescription(description: String, type: String): String {
    val ask = description.ifBlank { "Subagent" }
    return type.takeIf(String::isNotBlank)?.let { "$ask · $it" } ?: ask
}

private const val SUBAGENT_POLL_INTERVAL_MS = 1_000L
private const val SUBAGENT_POLL_RECENTNESS_GRACE_MS = 30_000L
private const val SUBAGENT_TRANSCRIPT_POLL_CAP_BYTES = 256 * 1024

/** `result` arriving after this much stdout silence gets an explanation line. */
private const val HIDDEN_FINALIZATION_WARN_MS = 10_000L

/**
 * Character budget for the replayed chat history. Sessions are one-shot, so the
 * prior turns travel inside the prompt and this budget is what keeps a small
 * server-side context from receiving a prompt it would truncate: two characters
 * per context token leaves the rest of the window for the prompt, tools, and
 * replies. An unset window keeps the historical flat cap.
 */
internal fun qwenHistoryCharacterBudget(contextWindowTokens: Int): Int =
    if (contextWindowTokens <= 0) {
        MAX_CONVERSATION_HISTORY_CHARACTERS
    } else {
        (contextWindowTokens * HISTORY_CHARACTERS_PER_CONTEXT_TOKEN)
            .coerceIn(MIN_CONVERSATION_HISTORY_CHARACTERS, MAX_CONVERSATION_HISTORY_CHARACTERS)
    }

private const val MIN_CONVERSATION_HISTORY_CHARACTERS = 4_000
private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
private const val HISTORY_CHARACTERS_PER_CONTEXT_TOKEN = 2

/**
 * Qwen Code multiplexes subagent turns onto the same stdout stream, tagging each
 * envelope with `parent_tool_use_id`; absent or JSON null means the main agent.
 */
internal fun qwenParentToolUseId(json: JSONObject): String? =
    if (json.has("parent_tool_use_id") && !json.isNull("parent_tool_use_id")) {
        json.optString("parent_tool_use_id").takeIf(String::isNotBlank)
    } else {
        null
    }

/** One main-agent API call's token accounting, as reported by the provider. */
internal data class QwenTokenUsage(val promptTokens: Int, val cachedTokens: Int, val outputTokens: Int) {
    val totalTokens: Int get() = promptTokens + outputTokens
}

/**
 * Token accounting of one API call from its `usage`: prompt = fresh + cached +
 * cache-write input tokens. Returns null when the envelope carries no usage
 * (provider did not report).
 */
internal fun qwenTokenUsage(message: JSONObject): QwenTokenUsage? {
    val usage = message.optJSONObject("usage") ?: return null
    val cacheRead = usage.optInt("cache_read_input_tokens")
    val cacheWrite = usage.optInt("cache_creation_input_tokens")
    val prompt = usage.optInt("input_tokens") + cacheRead + cacheWrite
    val output = usage.optInt("output_tokens")
    if (prompt == 0 && output == 0) return null
    return QwenTokenUsage(prompt, cacheRead + cacheWrite, output)
}
