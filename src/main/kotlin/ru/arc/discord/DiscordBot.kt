package ru.arc.discord

import org.slf4j.LoggerFactory
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.tickets.ForumTicketSync
import ru.arc.auction.AuctionItemDto
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.ops.DiscordChannelMutationRequest
import ru.arc.ops.DiscordHistoryRequest
import ru.arc.ops.DiscordMemberMutationRequest
import ru.arc.ops.DiscordMemberReadRequest
import ru.arc.ops.DiscordMessageMutationRequest
import ru.arc.ops.DiscordMessageRequest
import ru.arc.ops.DiscordOpsGateway
import ru.arc.ops.DiscordPinsRequest
import ru.arc.ops.DiscordRoleMutationRequest
import ru.arc.ops.DiscordSearchRequest
import ru.arc.ops.DiscordThreadMutationRequest
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Public compatibility facade; concrete Discord responsibilities live in focused services. */
class DiscordBot : AutoCloseable, DiscordOpsGateway {
    private val config: Config get() = ProxyConfigs.module("discord.yml")
    private val joinConfig: Config get() = ProxyConfigs.module("join_config.yml")
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val session = DiscordSession()
    private val verificationConfig =
        runCatching { DiscordVerificationConfig.load().also(DiscordVerificationConfig::validate) }
            .onFailure { log.error("Discord verification configuration is invalid", it) }
            .getOrNull()
    private val identities =
        verificationConfig?.let {
            DiscordIdentityService(
                DiscordIdentityStore(Velocity.requireDataFolder().resolve("data/discord-identities.json")),
                it,
            )
        }
    private val roleService =
        verificationConfig?.let {
            DiscordRoleService(
                session,
                it,
                DiscordRoleService.luckPermsProvider { Velocity.luckpermsHook },
            )
        }
    private val verification =
        identities?.let { identityService ->
            roleService?.let { DiscordVerificationService(identityService, it) }
        }
    private val codec = DiscordMessageCodec { playerName -> identities?.findByPlayerName(playerName) }
    private val cleaner =
        DiscordChatCleaner(
            executor = executor,
            historyProvider = { channelId ->
                session.jda()?.getTextChannelById(channelId)?.iterableHistory
            },
        )
    private val chat = DiscordChatService(session, config, codec, cleaner)
    private val feeds = DiscordFeedService(session, config, joinConfig, executor)
    private val tickets = DiscordTicketService(session, config, executor)
    private val verificationListener =
        verificationConfig?.let { configured ->
            verification?.let { DiscordVerificationListener(configured, it) }
        }
    private val connection = DiscordConnectionService(config, session, executor)
    private val opsAdapter =
        DiscordOpsAdapter(
            jdaProvider = session::jda,
            aliasesProvider = ::configuredChannelAliases,
        )
    @Volatile
    private var roleSyncFuture: ScheduledFuture<*>? = null

    init {
        instance = this
        if (identities?.isAvailable() == false) {
            log.error(
                "Discord identity workflows are disabled because storage could not be loaded: {}",
                identities.storageFailureClass(),
            )
        }
        runCatching {
            connection.start(::activateServices)
        }.onFailure { log.error("Discord bot initialization failed", it) }
    }

    private fun activateServices(snapshot: DiscordSessionSnapshot): Collection<Any> {
        val listeners = mutableListOf<Any>(DiscordListener(chat))
        verificationListener?.let { listener ->
            listener.registerCommands(snapshot)
            listeners += listener
        }
        roleService?.hierarchyProblems()?.takeIf(List<String>::isNotEmpty)?.let { problems ->
            log.error("Discord managed-role prerequisites are not met: {}", problems.joinToString(","))
        }
        scheduleRoleReconciliation()
        ForumTicketSync.scheduleIfEnabled()
        return listeners
    }

    private fun scheduleRoleReconciliation() {
        val configured = verificationConfig ?: return
        val service = verification ?: return
        if (!configured.enabled || roleSyncFuture != null) return
        service.reconcileAll().whenComplete { _, error ->
            if (error != null) {
                log.warn("Initial Discord role reconciliation failed: {}", error.javaClass.simpleName)
            }
        }
        roleSyncFuture =
            executor.scheduleAtFixedRate(
                {
                    service.reconcileAll().whenComplete { _, error ->
                        if (error != null) {
                            log.warn("Periodic Discord role reconciliation failed: {}", error.javaClass.simpleName)
                        }
                    }
                },
                configured.syncIntervalSeconds,
                configured.syncIntervalSeconds,
                TimeUnit.SECONDS,
            )
    }

    fun scheduler(): ScheduledExecutorService = executor

    override fun isReady(): Boolean = connection.isEnabled() && session.isReady()

    fun sendChatMessage(message: String) = chat.sendChatMessage(message)

    fun sendGeneralMessage(message: String) = chat.sendGeneralMessage(message)

    fun clearChat(channelId: String) = chat.clearChat(channelId)

    fun stopClearTask(channelId: String) = chat.stopClearTask(channelId)

    fun refreshPlayerListFromProxy() = feeds.refreshPlayerListFromProxy()

    fun updatePlayerList(players: Collection<String>) = feeds.updatePlayerList(players)

    fun updateAuctionItems(items: List<AuctionItemDto>) = feeds.updateAuctionItems(items)

    fun sendJoinEmbed(
        playerName: String,
        joinType: JoinType,
        override: String?,
    ) = feeds.sendJoinEmbed(playerName, joinType, override)

    fun syncForumTickets(): CompletableFuture<Int> = tickets.syncForumTickets()

    fun createIssueTicket(
        title: String,
        description: String,
        context: IssueTicketContext,
    ): CompletableFuture<Any> = tickets.createIssueTicket(title, description, context)

    fun updateIssueTicket(
        ticketId: String,
        appendDescription: String?,
        newTitle: String?,
        status: String?,
    ): CompletableFuture<Any> = tickets.updateIssueTicket(ticketId, appendDescription, newTitle, status)

    fun listIssueTickets(
        limit: Int,
        reporter: String?,
    ): CompletableFuture<Any> = tickets.listIssueTickets(limit, reporter)

    internal fun isVerificationEnabled(): Boolean =
        verificationConfig?.enabled == true && verification?.isAvailable() == true

    internal fun isVerificationBackendAllowed(backend: String): Boolean =
        verificationConfig?.allowedBackends?.contains(backend.lowercase()) == true

    internal fun issueLinkChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        verification?.issueLinkChallenge(playerUuid, playerName) ?: DiscordChallengeIssueResult.Unavailable

    internal fun issueRecoveryChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        verification?.issueRecoveryChallenge(playerUuid, playerName) ?: DiscordChallengeIssueResult.Unavailable

    internal fun unlinkIdentityByMinecraft(playerUuid: UUID): CompletableFuture<DiscordVerificationWorkflowResult> =
        verification?.unlinkByMinecraft(playerUuid)
            ?: CompletableFuture.completedFuture(DiscordVerificationWorkflowResult.Unavailable)

    internal fun findIdentityByPlayer(playerUuid: UUID): DiscordIdentityLink? =
        verification?.findByPlayerUuid(playerUuid)

    internal fun reconcileIdentity(
        playerUuid: UUID,
        playerName: String,
    ): CompletableFuture<DiscordRoleReconcileResult> =
        verification?.reconcilePlayer(playerUuid, playerName)
            ?: CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.NOT_READY),
            )

    internal fun roleHierarchyProblems(): List<String> = roleService?.hierarchyProblems() ?: emptyList()

    override fun isChannelAllowed(
        channelId: String,
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Boolean = opsAdapter.isChannelAllowed(channelId, allowedGuildIds, allowedChannelIds)

    override fun isGuildAllowed(guildId: String, allowedGuildIds: Set<String>): Boolean =
        opsAdapter.isGuildAllowed(guildId, allowedGuildIds)

    override fun listGuilds(allowedGuildIds: Set<String>): Map<String, Any?> =
        opsAdapter.listGuilds(allowedGuildIds)

    override fun listChannels(
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Map<String, Any?> = opsAdapter.listChannels(allowedGuildIds, allowedChannelIds)

    override fun listRoles(guildId: String): Map<String, Any?> = opsAdapter.listRoles(guildId)
    override fun readMember(request: DiscordMemberReadRequest) = opsAdapter.readMember(request)
    override fun readHistory(request: DiscordHistoryRequest) = opsAdapter.readHistory(request)
    override fun readMessage(request: DiscordMessageRequest) = opsAdapter.readMessage(request)
    override fun readPins(request: DiscordPinsRequest) = opsAdapter.readPins(request)
    override fun searchMessages(request: DiscordSearchRequest) = opsAdapter.searchMessages(request)
    override fun mutateMessage(request: DiscordMessageMutationRequest) = opsAdapter.mutateMessage(request)
    override fun mutateThread(request: DiscordThreadMutationRequest) = opsAdapter.mutateThread(request)
    override fun mutateChannel(request: DiscordChannelMutationRequest) = opsAdapter.mutateChannel(request)
    override fun mutateRole(request: DiscordRoleMutationRequest) = opsAdapter.mutateRole(request)
    override fun mutateMember(request: DiscordMemberMutationRequest) = opsAdapter.mutateMember(request)

    private fun configuredChannelAliases(): Map<String, String> =
        OPS_CHANNEL_ALIASES.associateWith { config.string("channels.$it", "").trim() }

    @Synchronized
    override fun close() {
        ForumTicketSync.stop()
        roleSyncFuture?.cancel(false)
        roleSyncFuture = null
        feeds.close()
        cleaner.close()
        connection.close()
        executor.shutdownNow()
        if (instance === this) instance = null
    }

    enum class JoinType {
        FIRST_TIME,
        JOIN,
        LEAVE,
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordBot::class.java)
        private val OPS_CHANNEL_ALIASES =
            listOf("join-messages", "player-list", "auction", "chat", "general", "issue-tickets")

        @JvmField
        var instance: DiscordBot? = null

        internal fun playerListSignature(players: Collection<String>): String =
            DiscordFeedService.playerListSignature(players)

        internal fun shouldUpdatePlayerList(
            signature: String,
            lastPublished: String?,
            lastSuccessfulAtMs: Long,
            nowMs: Long,
            heartbeatMs: Long = 10 * 60 * 1_000L,
        ): Boolean =
            DiscordFeedService.shouldUpdatePlayerList(
                signature,
                lastPublished,
                lastSuccessfulAtMs,
                nowMs,
                heartbeatMs,
            )
    }
}
