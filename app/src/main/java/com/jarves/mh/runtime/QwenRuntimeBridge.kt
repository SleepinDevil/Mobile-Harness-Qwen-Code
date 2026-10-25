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
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        toolNames.clear()
        seenToolCalls.clear()
        subagentDescriptions.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
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
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
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
                val contextTokens = qwenContextTokens(message)
                if (contextTokens > 0) {
                    eventBus.emit(RuntimeEvent.ContextUsage(sessionId, contextTokens))
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
                        if (toolName == "agent" && subagentDescriptions.remove(toolId) != null) {
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
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "tool_use" -> {
                            val (name, detail) = emitToolStarted(sessionId, block, subagent = true)
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
                val contextTokens = qwenContextTokens(message)
                if (contextTokens > 0) {
                    eventBus.emit(RuntimeEvent.SubagentUsage(sessionId, parentCallId, contextTokens))
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

    /** Emits the progress row for one tool_use block and returns (name, detail) for subagent attribution. */
    private suspend fun emitToolStarted(sessionId: String, block: JSONObject, subagent: Boolean = false): Pair<String, String> {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return block.optString("name", "Tool") to ""
        val name = block.optString("name", "Tool")
        // The ViewModel matches ToolCompleted rows by tool name, so store and emit
        // the same attributed ("… (subagent)") label for child calls.
        val displayName = if (subagent) "$name (subagent)" else name
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
        if (!subagent && name == "agent" && id.isNotBlank()) {
            // The agent-tool call spawns one live subagent; its child turns stream
            // back tagged with this call id, so remember the ask for attribution.
            subagentDescriptions[id] = detail
            eventBus.emit(RuntimeEvent.SubagentStarted(sessionId, id, detail))
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
        .put("telemetry", JSONObject().put("enabled", false))
        .put("privacy", JSONObject().put("usageStatisticsEnabled", false))
        .put("experimental", JSONObject().put("cron", false).put("artifact", false))
        .put("modelProviders", JSONObject().put(route.authType, JSONArray().put(providerEntry)))
}

/** Subagent control-plane tools removed when subagents are turned off. */
internal val QWEN_SUBAGENT_CONTROL_TOOLS = listOf("agent", "list_agents", "send_message", "task_stop")

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

/**
 * Tokens occupying the model's context after one API call: the prompt
 * (fresh + cached + cache-write input) plus the completion it produced.
 * Returns 0 when the envelope carries no usage (provider did not report).
 */
internal fun qwenContextTokens(message: JSONObject): Int {
    val usage = message.optJSONObject("usage") ?: return 0
    return usage.optInt("input_tokens") + usage.optInt("cache_read_input_tokens") +
        usage.optInt("cache_creation_input_tokens") + usage.optInt("output_tokens")
}
