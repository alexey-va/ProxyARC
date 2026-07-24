package ru.arc.ai

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.Defaults.DEFAULT_SYSTEM
import ru.arc.ai.memory.AssistantHistoryCompactor
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.llm.LlmRequestLogger
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.routing.router.RouterBugHeuristic
import ru.arc.ai.routing.survey.BugSurveyLifecycle
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.BugToolPolicy
import ru.arc.ai.tickets.IssueTicketFormat
import ru.arc.ai.tickets.TicketDialogStore
import ru.arc.ai.tools.DefaultTools
import ru.arc.ai.tools.RemoteToolSupport
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.Config
import ru.arc.velocity.Velocity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Deque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque

class Assistant(
    private val config: Config,
    private val type: String,
    private val llmClient: OpenRouterLlmClient,
    val memoryStore: AssistantMemoryStore = AssistantMemoryStore(null),
) {
    private val log = LoggerFactory.getLogger(Assistant::class.java)

    private var prompt: String = DEFAULT_SYSTEM
    private var bugPrompt: String = DEFAULT_SYSTEM
    private var leftConversationUntil: Long = 0
    private val history: Deque<ChatCompletionMessageParam> = ConcurrentLinkedDeque()
    private val historyLabels: Deque<String> = ConcurrentLinkedDeque()
    private val chatObservations: Deque<String> = ConcurrentLinkedDeque()
    var currentRequest: CompletableFuture<AssistantEnqueueResult>? = null
    var lastTriggerPlayer: String? = null
        private set
    var lastTriggerMessage: String? = null
        private set
    var lastTriggerServer: String? = null
        private set

    private data class EnqueueJob(
        val triggerPlayer: String?,
        val triggerMessage: String?,
        val mode: AssistantRunMode,
        val triggerServer: String?,
        val enqueuedAtMs: Long = System.currentTimeMillis(),
        val result: CompletableFuture<AssistantEnqueueResult> = CompletableFuture(),
    )

    private val pendingJobs = ConcurrentLinkedDeque<EnqueueJob>()
    private val processLock = Any()

    private val client: OpenAIClient?
        get() = llmClient.client

    init {
        loadPrompt()
        loadBugPrompt()
        assistants.add(this)
    }

    fun reload() {
        loadPrompt()
        loadBugPrompt()
        history.clear()
        historyLabels.clear()
        TicketDialogStore.clearAll()
        chatObservations.clear()
        currentRequest = null
        pendingJobs.clear()
        PlayerPmCooldown.clear()
        leftConversationUntil = 0
    }

    fun snapshotChatObservations(maxLines: Int = 10): String {
        if (chatObservations.isEmpty()) return ""
        val lines = chatObservations.toList().takeLast(maxLines.coerceAtLeast(1))
        return lines.joinToString("\n")
    }

    fun observationLines(): List<String> = chatObservations.toList()

    fun recordTicketDialog(line: String) {
        val player = lastTriggerPlayer?.trim().orEmpty()
        if (player.isEmpty()) return
        TicketDialogStore.record(player, line)
    }

    fun snapshotTicketDialog(maxLines: Int = 15): String {
        val player = lastTriggerPlayer?.trim().orEmpty()
        if (player.isEmpty()) return ""
        return TicketDialogStore.snapshot(player, maxLines)
    }

    fun observeChat(formattedLine: String) {
        if (formattedLine.isBlank()) return
        chatObservations.addLast(formattedLine.trim())
        val maxLines = config.integer("$type.observe-max-lines", 40)
        AssistantHistoryCompactor.compactObservations(chatObservations, maxLines)
    }

    private fun systemPrompt(mode: AssistantRunMode): String =
        when (mode) {
            AssistantRunMode.CHAT -> prompt
            AssistantRunMode.BUG, AssistantRunMode.BUG_SURVEY -> bugPrompt
        }

    private val tools: Collection<Class<out Tool>>
        get() = tools(AssistantRunMode.CHAT)

    private fun tools(
        mode: AssistantRunMode,
        triggerPlayer: String? = null,
    ): Collection<Class<out Tool>> {
        val section = mode.configSection()
        val defaults =
            when (mode) {
                AssistantRunMode.CHAT -> emptyList()
                AssistantRunMode.BUG ->
                    listOf(
                        "createissueticket",
                        "updateissueticket",
                        "sendprivatemessage",
                        "sendglobalmessage",
                        "listissuetickets",
                    )
                AssistantRunMode.BUG_SURVEY ->
                    listOf(
                        "createissueticket",
                        "updateissueticket",
                        "sendprivatemessage",
                        "sendglobalmessage",
                        "listissuetickets",
                        "completebugsurvey",
                    )
            }
        val toolNames =
            config.stringList("$section.tools", defaults)
                .map { it.lowercase() }
                .toMutableSet()
        if (mode.blocksPublicReply() && !BugToolPolicy.shouldOfferListIssueTickets(triggerPlayer)) {
            toolNames.remove("listissuetickets")
        }
        return Tools.getAllTools()
            .filter { toolNames.contains(it.simpleName.lowercase()) }
            .toSet()
    }

    private fun isModeEnabled(mode: AssistantRunMode): Boolean =
        config.bool("${mode.configSection()}.enabled", true)

    private fun loadBugPrompt() {
        bugPrompt = loadPromptFile("bug-ticket", DEFAULT_SYSTEM.trimIndent())
    }

    private fun loadPrompt() {
        prompt = loadPromptFile(type, DEFAULT_SYSTEM.trimIndent())
    }

    private fun loadPromptFile(
        promptType: String,
        fallback: String,
    ): String {
        try {
            val promptFolder = config.dataFolder.toPath().resolve("prompts")
            if (!Files.exists(promptFolder)) {
                Files.createDirectories(promptFolder)
            }
            val promptPath = promptFolder.resolve("$promptType.txt")
            if (!Files.exists(promptPath)) {
                copyBundledPrompt(promptPath, promptType)
            }
            if (!Files.exists(promptPath)) {
                return fallback
            }
            val text = Files.readString(promptPath).trim()
            return if (text.isEmpty()) fallback else text
        } catch (e: Exception) {
            log.info("Error reading prompt file {}", promptType, e)
            return fallback
        }
    }

    private fun copyBundledPrompt(promptPath: Path, type: String) {
        Assistant::class.java.getResourceAsStream("/prompts/$type.txt")?.use { input ->
            Files.copy(input, promptPath)
        }
    }

    fun leaveForTime(minutes: Int) {
        log.info("Assistant of type {} is leaving for {} minutes", type, minutes)
        leftConversationUntil = System.currentTimeMillis() + minutes.toLong() * 60 * 1000
    }

    fun addChatMessage(
        content: String,
        player: String,
    ) {
        history.addLast(
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(content)
                    .name(player)
                    .build(),
            ),
        )
        historyLabels.addLast(content)
        trimAndCompactHistory()
    }

    private fun trimAndCompactHistory() {
        val maxHistory = config.integer("$type.max-history", 20)
        while (history.size > maxHistory) {
            history.pollFirst()
            historyLabels.pollFirst()
        }
        val threshold = config.integer("$type.compact-threshold", maxHistory)
        val keepRecent = config.integer("$type.compact-keep-recent", maxHistory / 2)
        AssistantHistoryCompactor.compactHistory(history, historyLabels, threshold, keepRecent)
    }

    fun tryEnqueue(
        triggerPlayer: String? = null,
        triggerMessage: String? = null,
        mode: AssistantRunMode = AssistantRunMode.CHAT,
        triggerServer: String? = null,
    ): CompletableFuture<AssistantEnqueueResult> {
        if (!isModeEnabled(mode)) {
            return completedSkip(SkipReason.DISABLED, triggerPlayer, triggerMessage)
        }
        if (!llmClient.enabled || client == null) {
            return completedSkip(SkipReason.LLM_NOT_READY, triggerPlayer, triggerMessage)
        }
        if (System.currentTimeMillis() < leftConversationUntil) {
            return completedSkip(
                SkipReason.AWAY,
                triggerPlayer,
                triggerMessage,
                detail = "until ${Date(leftConversationUntil)}",
            )
        }
        if (currentRequest != null && currentRequest!!.isDone) {
            currentRequest = null
        }
        val job =
            EnqueueJob(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                mode = mode,
                triggerServer = triggerServer?.trim()?.takeIf { it.isNotEmpty() },
            )
        synchronized(processLock) {
            if (currentRequest != null && !currentRequest!!.isDone) {
                supersedePendingChatJobs(job)
                val section = mode.configSection()
                val defaultMaxPending = if (mode == AssistantRunMode.CHAT) 2 else 4
                val maxPending = config.integer("$section.max-pending-jobs", defaultMaxPending).coerceIn(1, 16)
                if (pendingJobs.size >= maxPending) {
                    log.warn(
                        "Assistant [{}] pending queue full ({}), dropping {} on \"{}\"",
                        type,
                        maxPending,
                        triggerPlayer ?: "?",
                        triggerMessage,
                    )
                    return completedSkip(
                        SkipReason.BUSY,
                        triggerPlayer,
                        triggerMessage,
                        detail = "queue_full",
                    )
                }
                pendingJobs.addLast(job)
                log.debug(
                    "Assistant [{}] queued for {} on \"{}\": pending={}",
                    type,
                    triggerPlayer ?: "?",
                    triggerMessage,
                    pendingJobs.size,
                )
                return job.result
            }
            launchJob(job)
        }
        return job.result
    }

    private fun launchJob(job: EnqueueJob) {
        prepareJobTrigger(job)
        currentRequest = sendRequest(0, job.triggerPlayer, job.triggerMessage, job.mode, ToolChainState())
        currentRequest!!.whenComplete { result, error ->
            synchronized(processLock) {
                val final =
                    when {
                        error != null ->
                            AssistantEnqueueResult.skip(
                                reason = SkipReason.LLM_ERROR,
                                triggerPlayer = job.triggerPlayer,
                                triggerMessage = job.triggerMessage,
                                detail = error.message,
                            )
                        result != null -> result
                        else ->
                            AssistantEnqueueResult.skip(
                                reason = SkipReason.LLM_ERROR,
                                triggerPlayer = job.triggerPlayer,
                                triggerMessage = job.triggerMessage,
                                detail = "null_result",
                            )
                    }
                job.result.complete(final)
                currentRequest = null
                launchNextPending()
            }
        }
    }

    private fun supersedePendingChatJobs(incoming: EnqueueJob) {
        val superseded =
            pendingJobs.filter {
                AssistantQueuePolicy.shouldSupersede(
                    queuedPlayer = it.triggerPlayer,
                    queuedMode = it.mode,
                    incomingPlayer = incoming.triggerPlayer,
                    incomingMode = incoming.mode,
                )
            }
        for (job in superseded) {
            if (!pendingJobs.remove(job)) continue
            completeQueuedSkip(job, "superseded_by_newer_message")
        }
    }

    private fun launchNextPending() {
        while (true) {
            val next = pendingJobs.pollFirst() ?: return
            if (AssistantQueuePolicy.isExpired(next.enqueuedAtMs, System.currentTimeMillis(), maxQueueAgeMs(next.mode))) {
                log.info(
                    "Assistant [{}] dropped stale queued {} request from {} on \"{}\"",
                    type,
                    next.mode.name.lowercase(),
                    next.triggerPlayer ?: "?",
                    next.triggerMessage?.take(80),
                )
                completeQueuedSkip(next, "stale_queue")
                continue
            }
            launchJob(next)
            return
        }
    }

    private fun maxQueueAgeMs(mode: AssistantRunMode): Long {
        val section = mode.configSection()
        val defaultSec = if (mode == AssistantRunMode.CHAT) 15 else 60
        return config.integer("$section.max-queue-age-sec", defaultSec).coerceIn(5, 300) * 1000L
    }

    private fun completeQueuedSkip(
        job: EnqueueJob,
        detail: String,
    ) {
        val result =
            AssistantEnqueueResult.skip(
                reason = SkipReason.BUSY,
                triggerPlayer = job.triggerPlayer,
                triggerMessage = job.triggerMessage,
                detail = detail,
            )
        result.logSummary(log, type, job.mode)
        job.result.complete(result)
    }

    private fun prepareJobTrigger(job: EnqueueJob) {
        val previousPlayer = lastTriggerPlayer?.trim().orEmpty()
        lastTriggerPlayer = job.triggerPlayer
        lastTriggerMessage = job.triggerMessage
        lastTriggerServer = job.triggerServer
        if (job.mode.usesBugPrompt()) {
            val player = job.triggerPlayer?.trim().orEmpty()
            if (player.isNotEmpty()) {
                if (previousPlayer.isNotEmpty() && previousPlayer != player) {
                    history.clear()
                    historyLabels.clear()
                }
                if (!BugSurveySessionStore.isActive(player)) {
                    TicketDialogStore.clear(player)
                } else {
                    trimSurveyTurnHistory()
                }
                job.triggerMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { recordTicketDialog("игрок: $it") }
            }
        }
    }

    private class ToolChainState {
        var privateMessageSent: Boolean = false
        var globalMessageSent: Boolean = false
        var surveyCompleted: Boolean = false
        var ticketHandled: Boolean = false
        var ticketCreatedThisChain: Boolean = false
    }

    private fun trimSurveyTurnHistory() {
        val keep = config.integer("$type.survey-turn-keep-history", 6)
        while (history.size > keep) {
            history.pollFirst()
            historyLabels.pollFirst()
        }
    }

    private fun completedSkip(
        reason: SkipReason,
        triggerPlayer: String?,
        triggerMessage: String?,
        raw: String? = null,
        detail: String? = null,
    ): CompletableFuture<AssistantEnqueueResult> {
        val result =
            AssistantEnqueueResult.skip(
                reason = reason,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = detail,
            )
        result.logSummary(log, type)
        return CompletableFuture.completedFuture(result)
    }

    private fun sendRequest(
        depth: Int,
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
        chainState: ToolChainState,
        toolsInChain: Boolean = false,
    ): CompletableFuture<AssistantEnqueueResult> {
        val runModel = model(mode)
        val toolClasses = tools(mode, triggerPlayer)
        val builder =
            ChatCompletionCreateParams.builder()
                .addSystemMessage(AssistantPromptLayers.staticSystemPrompt(systemPrompt(mode)))
                .temperature(temperature(mode))
                .model(runModel)
        if (mode == AssistantRunMode.CHAT) {
            AssistantPromptLayers.memoryContextMessage(config, type, memoryStore)?.let { builder.addMessage(it) }
            AssistantPromptLayers.chatContextMessage(config, type, chatObservations)?.let { builder.addMessage(it) }
            AssistantPromptLayers.chatBugSurveyActiveMessage(triggerPlayer)?.let { builder.addMessage(it) }
            AssistantPromptLayers.chatTriggerContextMessage(
                config,
                triggerPlayer,
                triggerMessage,
                lastTriggerServer,
            )?.let { builder.addMessage(it) }
        } else if (mode.usesBugPrompt()) {
            AssistantPromptLayers.bugRecentOpenTicketsMessage(config)?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugInvestigationScopeMessage(triggerPlayer)?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugOpenTicketMessage(config, triggerPlayer)?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugRecentChatMessage(config, type, chatObservationLines())?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugTriggerContextMessage(
                config,
                triggerPlayer,
                triggerMessage,
                lastTriggerServer,
            )?.let { builder.addMessage(it) }
        }
        for (message in history) {
            builder.addMessage(message)
        }
        for (tool in toolClasses) {
            builder.addTool(tool)
        }
        val params =
            builder
                .maxTokens(maxTokens(mode))
                .build()

        val contextLayers = countContextLayers(mode, triggerPlayer, triggerMessage)
        LlmRequestLogger.logAssistantRequestStart(
            log = log,
            agent = type,
            mode = mode.name.lowercase(),
            depth = depth,
            player = triggerPlayer,
            triggerMessage = triggerMessage,
            contextLayers = contextLayers,
            historyMessages = history.size,
            toolSchemas = toolClasses.size,
            model = runModel,
        )

        return CompletableFuture.supplyAsync {
            try {
                val startedNs = System.nanoTime()
                val response = client!!.chat().completions().create(params)
                val latencyMs = (System.nanoTime() - startedNs) / 1_000_000
                val choice = response.choices().first()
                val message = choice.message()
                val content = message.content().orElse("")
                val toolCalls = message.toolCalls().orElse(emptyList())
                val finishReason = choice.finishReason()?.toString()?.lowercase().orEmpty()
                LlmRequestLogger.logAssistantRequestComplete(
                    log = log,
                    agent = type,
                    mode = mode.name.lowercase(),
                    depth = depth,
                    player = triggerPlayer,
                    model = runModel,
                    latencyMs = latencyMs,
                    response = response,
                    toolNames = toolCalls.map { it.asFunction().function().name() },
                )
                if (content.isBlank() && toolCalls.isEmpty()) {
                    log.warn(
                        "Assistant LLM empty content mode={} model={} player={} finish_reason={}",
                        mode.name.lowercase(),
                        runModel,
                        triggerPlayer ?: "?",
                        choice.finishReason()?.toString() ?: "null",
                    )
                }
                history.addLast(ChatCompletionMessageParam.ofAssistant(message.toParam()))
                historyLabels.addLast("бот: ${message.content().orElse("")}")
                val toolsThisRound = toolCalls.isNotEmpty()
                val toolsInChainTotal = toolsInChain || toolsThisRound
                toolCalls.forEach { toolCall ->
                    val result = executeTool(toolCall, mode, chainState)
                    history.addLast(
                        ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.asFunction().id())
                                .contentAsJson(result)
                                .build(),
                        ),
                    )
                    historyLabels.addLast("tool:${toolCall.asFunction().function().name()}")
                }
                if (toolsThisRound) {
                    if (mode.blocksPublicReply() && shouldFinishBugChain(chainState)) {
                        return@supplyAsync completedBugChain(
                            triggerPlayer = triggerPlayer,
                            triggerMessage = triggerMessage,
                            detail =
                                when {
                                    chainState.privateMessageSent -> "PM sent, chain complete"
                                    chainState.globalMessageSent -> "global message sent, chain complete"
                                    chainState.surveyCompleted -> "survey closed, chain complete"
                                    chainState.ticketHandled -> "ticket handled, chain complete"
                                    else -> "chain complete"
                                },
                        )
                    }
                    if (depth < maxToolDepth(mode)) {
                        return@supplyAsync sendRequest(
                            depth + 1,
                            triggerPlayer,
                            triggerMessage,
                            mode,
                            chainState,
                            toolsInChainTotal,
                        ).join()
                    }
                }
                return@supplyAsync finishAfterToolRound(
                    content = content,
                    toolsInChainTotal = toolsInChainTotal,
                    depth = depth,
                    triggerPlayer = triggerPlayer,
                    triggerMessage = triggerMessage,
                    mode = mode,
                    chainState = chainState,
                    finishReason = finishReason,
                )
            } catch (e: Exception) {
                log.error(
                    "Assistant [{}] LLM error for {} on \"{}\": {}",
                    type,
                    triggerPlayer ?: "?",
                    triggerMessage,
                    e.message,
                    e,
                )
                recoverBugAgentFailure(
                    triggerPlayer = triggerPlayer,
                    triggerMessage = triggerMessage,
                    mode = mode,
                    chainState = chainState,
                )?.let { return@supplyAsync it }
                AssistantEnqueueResult.skip(
                    reason = SkipReason.LLM_ERROR,
                    triggerPlayer = triggerPlayer,
                    triggerMessage = triggerMessage,
                    detail = e.message,
                ).also { it.logSummary(log, type, mode) }
            }
        }
    }

    private fun recoverBugAgentFailure(
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
        chainState: ToolChainState,
    ): AssistantEnqueueResult? {
        if (!mode.blocksPublicReply()) return null
        if (
            chainState.privateMessageSent ||
            chainState.globalMessageSent ||
            chainState.surveyCompleted
        ) {
            return completedBugChain(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "LLM failed after bug chain already delivered",
            )
        }
        val player = triggerPlayer?.trim().orEmpty()
        val session = player.takeIf { it.isNotEmpty() }?.let { BugSurveySessionStore.findForPlayer(it) }
        val action =
            BugAgentRecoveryPolicy.decide(
                mode = mode,
                player = player,
                message = triggerMessage,
                activeSurveyWithoutTicket =
                    session != null &&
                        session.ticketId.isNullOrBlank() &&
                        !chainState.ticketHandled,
            )
        return when (action) {
            BugAgentRecoveryPolicy.Action.SUPPRESS -> null
            BugAgentRecoveryPolicy.Action.ASK_DETAILS ->
                bugHardFallback(triggerPlayer, triggerMessage, mode)
            BugAgentRecoveryPolicy.Action.CREATE_TICKET ->
                bugSurveyDetailFallback(triggerPlayer, triggerMessage, mode)
        }
    }

    private fun finishAfterToolRound(
        content: String,
        toolsInChainTotal: Boolean,
        depth: Int,
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
        chainState: ToolChainState,
        finishReason: String = "",
    ): AssistantEnqueueResult {
        if (
            BugSurveyLifecycle.shouldResolveWithoutTools(
                mode = mode,
                hadToolCalls = toolsInChainTotal,
                message = triggerMessage,
            )
        ) {
            return resolveBugSurveyDeterministically(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                mode = mode,
            )
        }
        val result =
            evaluateModelContent(
                raw = content,
                hadToolCalls = toolsInChainTotal,
                depth = depth,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                mode = mode,
            )
        if (shouldRetryBlankWithoutTools(result, mode, toolsInChainTotal, depth, finishReason)) {
            log.info(
                "Bug agent retry {} on «{}» — blank/length without tools (depth={}, finish={})",
                triggerPlayer ?: "?",
                triggerMessage?.take(80),
                depth,
                finishReason.ifBlank { "?" },
            )
            history.addLast(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(
                            "system: response was empty or truncated. " +
                                "Call tools now with SHORT arguments: " +
                                "createissueticket + sendprivatemessage, or updateissueticket + sendprivatemessage. " +
                                "Player text must be Russian. SKIP only after tools succeed.",
                        )
                        .name("blank-retry")
                        .build(),
                ),
            )
            return sendRequest(depth + 1, triggerPlayer, triggerMessage, mode, chainState, false).join()
        }
        if (shouldNudgeBugAgent(result, mode, toolsInChainTotal, depth, triggerMessage)) {
            log.info(
                "Bug survey nudge {} — model_skip without tools on «{}», retrying",
                triggerPlayer ?: "?",
                triggerMessage,
            )
            history.addLast(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(
                            "system: you wrote SKIP without tools. " +
                                "Call sendprivatemessage or createissueticket/updateissueticket " +
                                "(player text must be Russian). SKIP only after tools.",
                        )
                        .name("survey-nudge")
                        .build(),
                ),
            )
            return sendRequest(depth + 1, triggerPlayer, triggerMessage, mode, chainState, false).join()
        }
        if (shouldSurveyDetailFallback(result, mode, toolsInChainTotal, triggerPlayer, triggerMessage)) {
            return bugSurveyDetailFallback(triggerPlayer, triggerMessage, mode)
        }
        if (shouldHardFallbackBugAgent(result, mode, toolsInChainTotal, depth, triggerMessage)) {
            return bugHardFallback(triggerPlayer, triggerMessage, mode)
        }
        return result
    }

    private fun resolveBugSurveyDeterministically(
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): AssistantEnqueueResult {
        val player = triggerPlayer?.trim().orEmpty()
        val message = triggerMessage?.trim().orEmpty()
        val session = player.takeIf { it.isNotEmpty() }?.let { BugSurveySessionStore.findForPlayer(it) }
        val ticket =
            session?.ticketId?.let { ru.arc.ai.tickets.IssueTicketStore.find(it) }
                ?: ru.arc.ai.tickets.IssueTicketStore.findOpenByReporter(player)

        if (ticket == null) {
            BugSurveySessionStore.close(player, "player_resolved_no_ticket")
            log.info("Bug survey resolved deterministically for {} without an open ticket", player)
            return completedBugChain(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "player resolved; no open ticket",
            )
        }

        val updateResult =
            DefaultTools.UpdateIssueTicket(
                ticketId = ticket.ticketId,
                appendDescription = "Игрок $player сообщил, что проблема решена: $message",
                status = "closed",
            ).execute(this)
        val closed =
            updateResult is Map<*, *> &&
                updateResult["status"] == "updated" &&
                updateResult["ticketStatus"] == "closed"
        if (!closed) {
            return AssistantEnqueueResult.skip(
                reason = SkipReason.LLM_ERROR,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "deterministic_close_failed: $updateResult",
            ).also { it.logSummary(log, type, mode) }
        }

        val pmResult =
            DefaultTools.SendPrivateMessage(
                playerName = player,
                message = "ок, закрыл ${ticket.ticketId}. если повторится — пиши",
            ).execute(this)
        log.info(
            "Bug survey resolved deterministically for {} ticket={} pm={}",
            player,
            ticket.ticketId,
            (pmResult as? Map<*, *>)?.get("status") ?: "unknown",
        )
        return completedBugChain(
            triggerPlayer = triggerPlayer,
            triggerMessage = triggerMessage,
            detail = "player resolved; ticket ${ticket.ticketId} closed",
        )
    }

    private fun shouldRetryBlankWithoutTools(
        result: AssistantEnqueueResult,
        mode: AssistantRunMode,
        hadToolCalls: Boolean,
        depth: Int,
        finishReason: String,
    ): Boolean =
        mode.blocksPublicReply() &&
            !hadToolCalls &&
            depth < 2 &&
            result.skipReason == SkipReason.MODEL_BLANK &&
            (finishReason.contains("length") || finishReason.isEmpty())

    private fun evaluateModelContent(
        raw: String,
        hadToolCalls: Boolean,
        depth: Int,
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): AssistantEnqueueResult {
        val trimmed = raw.trim()
        if (AssistantChatFormat.isModelSkip(trimmed)) {
            if (hadToolCalls && mode.blocksPublicReply()) {
                return AssistantEnqueueResult.skip(
                    reason = SkipReason.MODEL_SKIP,
                    raw = raw,
                    triggerPlayer = triggerPlayer,
                    triggerMessage = triggerMessage,
                    detail = "after_tools",
                ).also {
                    log.info(
                        "Assistant [{}] bug handled for {} on \"{}\": PM/ticket sent, public chat suppressed",
                        type,
                        triggerPlayer ?: "?",
                        triggerMessage?.take(80),
                    )
                }
            }
            return AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
            ).also { it.logSummary(log, type, mode) }
        }
        if (trimmed.isEmpty()) {
            val reason =
                if (hadToolCalls) SkipReason.MODEL_TOOL_ONLY else SkipReason.MODEL_BLANK
            return AssistantEnqueueResult.skip(
                reason = reason,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = if (hadToolCalls) "tool round depth=$depth" else null,
            ).also { it.logSummary(log, type, mode) }
        }
        if (mode.blocksPublicReply()) {
            return AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "bug_mode_public_reply_blocked",
            ).also {
                log.info(
                    "Bug agent blocked public reply for {}: {}",
                    triggerPlayer ?: "?",
                    trimmed.take(120),
                )
            }
        }
        return AssistantEnqueueResult.reply(
            text = raw,
            raw = raw,
            triggerPlayer = triggerPlayer,
            triggerMessage = triggerMessage,
        ).also { it.logSummary(log, type, mode) }
    }

    private fun maxTokens(mode: AssistantRunMode): Long {
        val section = mode.configSection()
        val chatDefault = config.integer("chat.max-tokens", 512)
        return config.integer("$section.max-tokens", chatDefault).toLong().coerceIn(64L, 4096L)
    }

    private fun model(mode: AssistantRunMode): String {
        val section = mode.configSection()
        val chatDefault = config.string("chat.model", "deepseek/deepseek-v4-flash@preset/deepseek")
        return config.string("$section.model", chatDefault)
    }

    private fun temperature(mode: AssistantRunMode): Double {
        val section = mode.configSection()
        val chatDefault = config.real("chat.temperature", 0.7)
        return config.real("$section.temperature", chatDefault)
    }

    private fun chatObservationLines(): List<String> {
        if (type == "bug-survey") {
            return Velocity.chatAssistant?.observationLines().orEmpty()
        }
        return chatObservations.toList()
    }

    private fun shouldFinishBugChain(chainState: ToolChainState): Boolean =
        when {
            chainState.surveyCompleted -> true
            chainState.ticketCreatedThisChain && !chainState.privateMessageSent -> false
            chainState.globalMessageSent -> true
            chainState.privateMessageSent -> true
            chainState.ticketHandled && !chainState.privateMessageSent -> false
            else -> false
        }

    private fun completedBugChain(
        triggerPlayer: String?,
        triggerMessage: String?,
        detail: String,
    ): AssistantEnqueueResult =
        AssistantEnqueueResult.skip(
            reason = SkipReason.MODEL_SKIP,
            raw = "SKIP",
            triggerPlayer = triggerPlayer,
            triggerMessage = triggerMessage,
            detail = detail,
        ).also {
            log.info(
                "Assistant [{}] bug handled for {} on \"{}\": {}",
                type,
                triggerPlayer ?: "?",
                triggerMessage?.take(80),
                detail,
            )
        }

    private fun shouldNudgeBugAgent(
        result: AssistantEnqueueResult,
        mode: AssistantRunMode,
        hadToolCalls: Boolean,
        depth: Int,
        triggerMessage: String?,
    ): Boolean =
        mode.blocksPublicReply() &&
            !hadToolCalls &&
            depth < 2 &&
            result.skipReason == SkipReason.MODEL_SKIP &&
            result.detail != "after_tools" &&
            !shouldSuppressBugFollowUp(triggerMessage)

    private fun shouldHardFallbackBugAgent(
        result: AssistantEnqueueResult,
        mode: AssistantRunMode,
        hadToolCalls: Boolean,
        depth: Int,
        triggerMessage: String?,
    ): Boolean =
        mode.blocksPublicReply() &&
            !hadToolCalls &&
            depth >= 2 &&
            result.skipReason == SkipReason.MODEL_SKIP &&
            result.detail != "after_tools" &&
            !shouldSuppressBugFollowUp(triggerMessage)

    private fun shouldSuppressBugFollowUp(triggerMessage: String?): Boolean {
        val message = triggerMessage?.trim().orEmpty()
        if (message.isEmpty()) return true
        if (RouterBugHeuristic.looksLikeOptOut(message)) return true
        if (RouterBugHeuristic.looksLikeTrollNoise(message)) return true
        if (RouterBugHeuristic.looksLikeOfftopicSmalltalk(message)) return true
        if (RouterBugHeuristic.looksLikeJoke(message) && !RouterBugHeuristic.looksLikeBugReport(message)) {
            return true
        }
        return false
    }

    private fun shouldSurveyDetailFallback(
        result: AssistantEnqueueResult,
        mode: AssistantRunMode,
        hadToolCalls: Boolean,
        triggerPlayer: String?,
        triggerMessage: String?,
    ): Boolean {
        if (!mode.blocksPublicReply() || hadToolCalls) return false
        if (result.skipReason != SkipReason.MODEL_BLANK && result.skipReason != SkipReason.MODEL_SKIP) {
            return false
        }
        val player = triggerPlayer?.trim().orEmpty()
        val message = triggerMessage?.trim().orEmpty()
        if (player.isEmpty() || message.length < 8) return false
        val session = BugSurveySessionStore.findForPlayer(player) ?: return false
        if (!session.ticketId.isNullOrBlank()) return false
        return RouterBugHeuristic.looksLikeSurveyDetail(message) &&
            RouterBugHeuristic.looksLikeBugReport(message) &&
            !RouterBugHeuristic.looksLikeJoke(message)
    }

    private fun bugSurveyDetailFallback(
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): AssistantEnqueueResult {
        val player = triggerPlayer?.trim().orEmpty()
        val message = triggerMessage?.trim().orEmpty()
        if (player.isEmpty() || message.isEmpty()) {
            return bugHardFallback(triggerPlayer, triggerMessage, mode)
        }
        log.warn(
            "Bug survey detail fallback create+PM for {} on «{}» after model blank without tools",
            player,
            message.take(80),
        )
        val summary = IssueTicketFormat.sanitizeSummary(message, player)
        val ctx = IssueTicketContext.build(this, player, lastTriggerServer)
        val title = IssueTicketFormat.normalizeTitle(summary.take(72), ctx.displayServer)
        val description = IssueTicketFormat.buildDescription(summary, player)
        val createResult =
            DefaultTools.CreateIssueTicket(
                title = title,
                description = description,
                reporter = player,
                server = lastTriggerServer,
            ).execute(this)
        val ticketId =
            (createResult as? Map<*, *>)?.get("ticketId")?.toString()?.trim().orEmpty()
        val pmText =
            if (ticketId.isNotEmpty()) {
                "завёл $ticketId, грос глянет"
            } else {
                "Записал: $summary. Грос глянет."
            }
        val sendResult = DefaultTools.SendPrivateMessage(playerName = player, message = pmText).execute(this)
        return if (sendResult is Map<*, *> && PlayerMessaging.isPrivateMessageAccepted(sendResult)) {
            completedBugChain(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail =
                    if (ticketId.isNotEmpty()) {
                        "detail fallback create+PM ($ticketId)"
                    } else {
                        "detail fallback PM only (create failed)"
                    },
            )
        } else {
            AssistantEnqueueResult.skip(
                reason = SkipReason.LLM_ERROR,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "detail_fallback_failed: create=$createResult pm=$sendResult",
            ).also { it.logSummary(log, type, mode) }
        }
    }

    private fun bugHardFallback(
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): AssistantEnqueueResult {
        val player = triggerPlayer?.trim().orEmpty()
        val message = triggerMessage?.trim().orEmpty()
        if (player.isEmpty()) {
            return AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "hard_fallback_no_player",
            ).also { it.logSummary(log, type, mode) }
        }
        val pmText =
            if (message.length >= 8) {
                "Записал: $message. На каком сервере и какой командой воспроизводится?"
            } else {
                "Расскажи подробнее: что не работает, команда и сервер."
            }
        log.warn(
            "Bug agent hard fallback PM for {} on «{}» after model skip without tools",
            player,
            message.take(80),
        )
        val sendResult =
            DefaultTools.SendPrivateMessage(playerName = player, message = pmText).execute(this)
        return if (sendResult is Map<*, *> && PlayerMessaging.isPrivateMessageAccepted(sendResult)) {
            BugSurveySessionStore.openOrTouch(player)
            completedBugChain(
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail =
                    if (sendResult["status"] == "offline") {
                        "hard fallback PM logged (offline)"
                    } else {
                        "hard fallback PM sent"
                    },
            )
        } else {
            AssistantEnqueueResult.skip(
                reason = SkipReason.LLM_ERROR,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "hard_fallback_pm_failed: $sendResult",
            ).also { it.logSummary(log, type, mode) }
        }
    }

    private fun maxToolDepth(mode: AssistantRunMode): Int =
        if (mode.blocksPublicReply()) 3 else 3

    private fun countContextLayers(
        mode: AssistantRunMode,
        triggerPlayer: String?,
        triggerMessage: String?,
    ): Int {
        var layers = 1
        when (mode) {
            AssistantRunMode.CHAT -> {
                if (AssistantPromptLayers.memoryContextMessage(config, type, memoryStore) != null) layers++
                if (AssistantPromptLayers.chatContextMessage(config, type, chatObservations) != null) layers++
                if (AssistantPromptLayers.chatBugSurveyActiveMessage(triggerPlayer) != null) layers++
                if (
                    AssistantPromptLayers.chatTriggerContextMessage(
                        config,
                        triggerPlayer,
                        triggerMessage,
                        lastTriggerServer,
                    ) != null
                ) {
                    layers++
                }
            }
            AssistantRunMode.BUG, AssistantRunMode.BUG_SURVEY -> {
                if (AssistantPromptLayers.bugRecentOpenTicketsMessage(config) != null) layers++
                if (AssistantPromptLayers.bugInvestigationScopeMessage(triggerPlayer) != null) layers++
                if (AssistantPromptLayers.bugOpenTicketMessage(config, triggerPlayer) != null) layers++
                if (AssistantPromptLayers.bugRecentChatMessage(config, type, chatObservationLines()) != null) layers++
                if (
                    AssistantPromptLayers.bugTriggerContextMessage(
                        config,
                        triggerPlayer,
                        triggerMessage,
                        lastTriggerServer,
                    ) != null
                ) {
                    layers++
                }
            }
        }
        return layers
    }

    private fun executeTool(
        toolCall: ChatCompletionMessageToolCall,
        mode: AssistantRunMode,
        chainState: ToolChainState,
    ): Any {
        val toolName = toolCall.asFunction().function().name().lowercase()
        if (
            toolName == "updateissueticket" &&
            chainState.ticketCreatedThisChain
        ) {
            log.info("Skipping updateissueticket right after createissueticket in same chain")
            return mapOf(
                "status" to "skipped",
                "message" to "тикет только что создан — sendprivatemessage достаточно, update не нужен",
            )
        }
        if (
            toolName == "sendprivatemessage" &&
            mode.blocksPublicReply() &&
            chainState.privateMessageSent
        ) {
            log.info("Skipping duplicate sendprivatemessage in same bug chain")
            return mapOf(
                "status" to "skipped",
                "message" to "PM уже отправлен на это сообщение игрока, не дублируй",
            )
        }
        val toolClass = Tools.getTool(toolName)
        if (toolClass == null) {
            return "Unknown tool: $toolName"
        }
        return try {
            val tool = toolCall.asFunction().function().arguments(toolClass)
            if (toolName == "sendglobalmessage") {
                val text = (tool as? DefaultTools.SendGlobalMessage)?.message?.trim().orEmpty()
                val primary = lastTriggerPlayer?.trim().orEmpty()
                if (primary.isNotEmpty() && text.isNotEmpty()) {
                    val windowSec =
                        config.integer("bug.survey.global-inquiry-window-sec", 300).coerceIn(30, 900)
                    if (BugSurveySessionStore.hasRecentGlobalAsk(primary, text, windowSec * 1000L)) {
                        log.info("Skipping duplicate sendglobalmessage for {}: «{}»", primary, text.take(80))
                        return mapOf(
                            "status" to "skipped",
                            "message" to "глобальный опрос уже был недавно, не дублируй sendglobalmessage",
                        )
                    }
                }
            }
            if (toolName == "sendprivatemessage") {
                val target = (tool as? DefaultTools.SendPrivateMessage)?.playerName?.trim().orEmpty()
                if (target.isNotEmpty()) {
                    val cooldownSec = config.integer("$type.pm-cooldown-sec", 0).coerceIn(0, 300)
                    if (PlayerPmCooldown.isWithinCooldown(target, cooldownSec * 1000L)) {
                        log.info("Skipping sendprivatemessage — PM cooldown for {}", target)
                        return mapOf(
                            "status" to "skipped",
                            "message" to "PM cooldown: подожди перед следующим личным сообщением этому игроку",
                        )
                    }
                }
            }
            log.info("Executing tool {}", tool)
            val result =
                if (tool is RemoteToolSupport) {
                    tool.executeRemote().join()
                } else {
                    tool.execute(this) ?: ""
                }
            if (toolName == "sendprivatemessage" && result is Map<*, *>) {
                if (PlayerMessaging.isPrivateMessageAccepted(result)) {
                    chainState.privateMessageSent = true
                    (tool as? DefaultTools.SendPrivateMessage)?.playerName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        PlayerPmCooldown.markSent(it)
                    }
                    val triggerPlayer = lastTriggerPlayer?.trim().orEmpty()
                    val resolutionWithoutOpenTicket =
                        triggerPlayer.isNotEmpty() &&
                            RouterBugHeuristic.looksLikeResolved(lastTriggerMessage.orEmpty()) &&
                            BugSurveySessionStore.findForPlayer(triggerPlayer) == null &&
                            ru.arc.ai.tickets.IssueTicketStore.findOpenByReporter(triggerPlayer) == null
                    if (
                        BugSurveyLifecycle.shouldOpenAfterPrivateMessage(
                            surveyCompleted = chainState.surveyCompleted,
                            isResolutionWithoutOpenTicket = resolutionWithoutOpenTicket,
                        )
                    ) {
                        lastTriggerPlayer
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { BugSurveySessionStore.openOrTouch(it) }
                    }
                }
            }
            if (toolName == "sendglobalmessage" && result is Map<*, *> && result["status"] == "sent") {
                chainState.globalMessageSent = true
            }
            if (toolName == "createissueticket" && result is Map<*, *> && result["status"] == "created") {
                chainState.ticketHandled = true
                chainState.ticketCreatedThisChain = true
            }
            if (toolName == "updateissueticket" && result is Map<*, *>) {
                if (result["status"] == "updated") {
                    chainState.ticketHandled = true
                }
                if (result["ticketStatus"] == "closed") {
                    chainState.surveyCompleted = true
                }
            }
            if (toolName == "completebugsurvey" && result is Map<*, *> && result["status"] == "closed") {
                chainState.surveyCompleted = true
            }
            result
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
        }
    }

    companion object {
        @JvmField
        val assistants: MutableList<Assistant> = ArrayList()
    }
}
