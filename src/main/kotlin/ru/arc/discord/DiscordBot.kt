package ru.arc.discord

import org.slf4j.LoggerFactory
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.tickets.ForumTicketSync
import ru.arc.auction.AuctionItemDto
import ru.arc.auction.AuctionSaleEventDto
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.core.Tasks
import ru.arc.metrics.core.MetricPoint
import ru.arc.ops.DiscordChannelMutationRequest
import ru.arc.ops.DiscordGuildMutationRequest
import ru.arc.ops.DiscordHistoryRequest
import ru.arc.ops.DiscordInviteMutationRequest
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

/** Public compatibility facade; concrete Discord responsibilities live in focused services. */
class DiscordBot : AutoCloseable, DiscordOpsGateway {
    private val config: Config get() = ProxyConfigs.module("discord.yml")
    private val joinConfig: Config get() = ProxyConfigs.module("join_config.yml")
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val session = DiscordSession()
    private val verificationTelemetry = DiscordVerificationTelemetry()
    private val verificationConfig =
        runCatching { DiscordVerificationConfig.load().also(DiscordVerificationConfig::validate) }
            .onFailure { log.error("Discord verification configuration is invalid", it) }
            .getOrNull()
    private val chatConfig =
        runCatching { DiscordChatConfig.load().also(DiscordChatConfig::validate) }
            .onFailure { log.error("Discord chat bridge configuration is invalid", it) }
            .getOrNull()
    private val integrationConfig =
        runCatching { DiscordIntegrationConfig.load().also(DiscordIntegrationConfig::validate) }
            .onFailure { log.error("Discord integration configuration is invalid", it) }
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
    private val integrationStore =
        integrationConfig?.takeIf { it.enabled }?.let {
            DiscordIntegrationStore(Velocity.requireDataFolder().resolve("data/discord-integration.json"))
        }
    private val notifications =
        integrationConfig?.takeIf { it.enabled }?.let { configured ->
            integrationStore?.takeIf { it.isAvailable() }?.let { store ->
                DiscordNotificationService(
                    session = session,
                    config = configured,
                    store = store,
                    identityByPlayerName = { identities?.findByPlayerName(it) },
                    identityByPlayerUuid = { identities?.findByPlayerUuid(it) },
                )
            }
        }
    private val linkProtection =
        integrationConfig?.takeIf { it.enabled }?.let { configured ->
            integrationStore?.takeIf { it.isAvailable() }?.let { store ->
                notifications?.let { notifier ->
                    DiscordLinkProtectionService(configured, notifier, store, executor)
                }
            }
        }
    private val verification =
        identities?.let { identityService ->
            roleService?.let {
                DiscordVerificationService(
                    identityService,
                    it,
                    telemetry = verificationTelemetry,
                    workflowObserver = ::onVerificationWorkflow,
                    recoveryGuard = { prepared ->
                        linkProtection?.guard(prepared) ?: CompletableFuture.completedFuture(true)
                    },
                )
            }
        }
    private val roleSyncCoordinator =
        verificationConfig?.let { configured ->
            verification?.let { service ->
                DiscordRoleSyncCoordinator(
                    config = configured,
                    verification = service,
                    scheduler = Tasks.scheduler,
                    eventSubscriber =
                        DiscordLuckPermsEventSubscriber { listener ->
                            Velocity.luckpermsHook?.subscribeUserDataRecalculation(
                                Velocity.requirePlugin(),
                                listener,
                            )
                        },
                )
            }
        }
    private val codec = DiscordMessageCodec { playerName -> identities?.findByPlayerName(playerName) }
    private val cleaner =
        DiscordChatCleaner(
            executor = executor,
            historyProvider = { channelId ->
                session.jda()?.getTextChannelById(channelId)?.iterableHistory
            },
        )
    private val chat =
        DiscordChatService(
            session,
            chatConfig,
            codec,
            cleaner,
            DiscordChatIdentityResolver { discordUserId ->
                identities?.findByDiscordUserId(discordUserId)?.playerName
            },
        )
    private val feeds =
        DiscordFeedService(
            session,
            config,
            joinConfig,
            executor,
            statusChannelId = integrationConfig?.takeIf { it.enabled }?.statusChannelId,
        )
    private val presence =
        integrationConfig?.takeIf { it.enabled }?.let { DiscordPresenceService(session, it, feeds, executor) }
    private val gameEvents =
        integrationConfig?.takeIf { it.enabled }?.let { configured ->
            integrationStore?.takeIf { it.isAvailable() }?.let { store ->
                notifications?.let { notifier ->
                    DiscordGameEventService(
                        session,
                        configured,
                        store,
                        notifier,
                        executor,
                        identityByPlayerName = { identities?.findByPlayerName(it) },
                    )
                }
            }
        }
    private val moderation =
        notifications?.let { notifier ->
            val messages = integrationConfig?.messages ?: return@let null
            DiscordModerationService(
                notifier,
                messages,
                playerNameByUuid = { uuid ->
                    identities?.findByPlayerUuid(uuid)?.playerName
                        ?: Velocity.proxyServer?.getPlayer(uuid)?.orElse(null)?.username
                },
            )
        }
    private val tickets =
        DiscordTicketService(session, config, executor) { reporter, ticketId, url ->
            notifications?.notifyTicketReply(reporter, ticketId, url)
        }
    private val verificationListener =
        verificationConfig?.let { configured ->
            verification?.let { DiscordVerificationListener(configured, it) }
        }
    private val integrationListener =
        integrationConfig?.takeIf { it.enabled }?.let { configured ->
            verification?.let { verificationService ->
                integrationStore?.takeIf { it.isAvailable() }?.let { store ->
                    notifications?.let { notifier ->
                        gameEvents?.let { events ->
                            DiscordIntegrationListener(
                                configured,
                                verificationService,
                                store,
                                notifier,
                                events,
                                linkProtection,
                            )
                        }
                    }
                }
            }
        }
    private val connection = DiscordConnectionService(config, session, executor)
    private val opsAdapter =
        DiscordOpsAdapter(
            jdaProvider = session::jda,
            aliasesProvider = ::configuredChannelAliases,
        )
    init {
        instance = this
        if (identities?.isAvailable() == false) {
            log.error(
                "Discord identity workflows are disabled because storage could not be loaded: {}",
                identities.storageFailureClass(),
            )
        }
        if (integrationStore?.isAvailable() == false) {
            log.error(
                "Discord integration workflows are disabled because storage could not be loaded: {}",
                integrationStore.failureClass(),
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
        integrationListener?.let { listener ->
            listener.registerCommands(snapshot)
            listeners += listener
        }
        if (verificationConfig?.enabled == true) {
            roleService?.hierarchyProblems()?.takeIf(List<String>::isNotEmpty)?.let { problems ->
                log.error("Discord managed-role prerequisites are not met: {}", problems.joinToString(","))
            }
        }
        roleSyncCoordinator?.start()
        presence?.start()
        gameEvents?.start()
        moderation?.start()
        ForumTicketSync.scheduleIfEnabled()
        return listeners
    }

    fun scheduler(): ScheduledExecutorService = executor

    override fun isReady(): Boolean = connection.isEnabled() && session.isReady()

    fun sendChatMessage(
        message: String,
        allowedUserMentionIds: Set<String> = emptySet(),
    ) {
        chat.sendChatMessage(message, allowedUserMentionIds)
        notifications?.notifyMentions(message)
    }

    fun sendGeneralMessage(
        message: String,
        allowedUserMentionIds: Set<String> = emptySet(),
    ) = chat.sendGeneralMessage(message, allowedUserMentionIds)

    fun clearChat(channelId: String) = chat.clearChat(channelId)

    fun stopClearTask(channelId: String) = chat.stopClearTask(channelId)

    fun refreshPlayerListFromProxy() = feeds.refreshPlayerListFromProxy()

    fun updatePlayerList(players: Collection<String>) = feeds.updatePlayerList(players)

    fun updateAuctionItems(items: List<AuctionItemDto>) = feeds.updateAuctionItems(items)

    fun notifyAuctionSold(event: AuctionSaleEventDto) {
        notifications?.notifyAuctionSold(
            sellerUuid = event.sellerUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            sellerName = event.sellerName,
            item = event.itemDisplay,
            amount = event.amount,
            price = event.price,
            buyerName = event.buyerName,
        )
    }

    fun notifyInvite(
        playerName: String,
        message: String,
    ): CompletableFuture<Boolean> =
        notifications?.notifyInvite(playerName, message) ?: CompletableFuture.completedFuture(false)

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

    internal fun verificationInviteUrl(): String? = verificationConfig?.inviteUrl

    internal fun verificationMessages(): DiscordVerificationMessages? = verificationConfig?.messages

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

    internal fun findIdentityByDiscordUser(discordUserId: String): DiscordIdentityLink? =
        identities?.findByDiscordUserId(discordUserId)

    internal fun reconcileIdentity(
        playerUuid: UUID,
        playerName: String,
    ): CompletableFuture<DiscordRoleReconcileResult> =
        verification?.reconcilePlayer(playerUuid, playerName, DiscordRoleSyncTrigger.SERVER_CONNECT)
            ?: CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.NOT_READY),
            )

    internal fun lookupIdentity(query: String): DiscordIdentityLookupResult =
        verification?.lookup(query) ?: DiscordIdentityLookupResult.Unavailable

    internal fun reconcileIdentity(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
    ): CompletableFuture<DiscordRoleReconcileResult> =
        verification?.reconcilePlayer(link.playerUuid, link.playerName, trigger)
            ?: CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(
                    DiscordRoleReconcileResult.Status.NOT_READY,
                    reason = "verification-unavailable",
                ),
            )

    internal fun unlinkIdentity(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> =
        verification?.unlinkExpected(link)
            ?: CompletableFuture.completedFuture(DiscordVerificationWorkflowResult.Unavailable)

    internal fun adminGateway(): DiscordVerificationAdminGateway =
        object : DiscordVerificationAdminGateway {
            override fun lookupIdentity(query: String): DiscordIdentityLookupResult = this@DiscordBot.lookupIdentity(query)

            override fun reconcileIdentity(
                link: DiscordIdentityLink,
                trigger: DiscordRoleSyncTrigger,
            ): CompletableFuture<DiscordRoleReconcileResult> = this@DiscordBot.reconcileIdentity(link, trigger)

            override fun unlinkIdentity(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> =
                this@DiscordBot.unlinkIdentity(link)
        }

    internal fun verificationMetricsSnapshot(): List<MetricPoint> =
        verification?.metricsSnapshot()
            ?: verificationTelemetry.snapshot(DiscordIdentityStats(false, 0, 0))

    internal fun roleHierarchyProblems(): List<String> = roleService?.hierarchyProblems() ?: emptyList()

    private fun onVerificationWorkflow(result: DiscordVerificationWorkflowResult) {
        when (result) {
            is DiscordVerificationWorkflowResult.Recovered -> {
                val message =
                    integrationConfig?.messages?.text(
                        "security-link-changed",
                        "player" to DiscordTextSafety.markdown(result.link.playerName, 16),
                    ) ?: return
                notifications?.notifySecurity(result.previousLink.discordUserId, message)
                runCatching {
                    integrationStore?.recordSecurityEvent(
                        "link-recovered",
                        result.previousLink.discordUserId,
                        result.link.playerName,
                        result.link.discordUserId,
                    )
                }.onFailure { error ->
                    log.warn("Could not audit recovered Discord link: {}", error.javaClass.simpleName)
                }
                gameEvents?.migrateParticipant(result.previousLink.discordUserId, result.link.discordUserId)
                    ?.exceptionally { error ->
                        log.warn("Could not migrate Discord event participation after recovery: {}", error.javaClass.simpleName)
                        null
                    }
            }
            is DiscordVerificationWorkflowResult.Unlinked -> {
                val message =
                    integrationConfig?.messages?.text(
                        "security-unlinked",
                        "player" to DiscordTextSafety.markdown(result.previousLink.playerName, 16),
                    ) ?: return
                notifications?.notifySecurity(result.previousLink.discordUserId, message)
                runCatching {
                    integrationStore?.recordSecurityEvent(
                        "identity-unlinked",
                        result.previousLink.discordUserId,
                        result.previousLink.playerName,
                    )
                }.onFailure { error ->
                    log.warn("Could not audit unlinked Discord identity: {}", error.javaClass.simpleName)
                }
                gameEvents?.removeParticipant(result.previousLink.discordUserId)
                    ?.exceptionally { error ->
                        log.warn("Could not remove Discord event participation after unlink: {}", error.javaClass.simpleName)
                        null
                    }
            }
            else -> Unit
        }
    }

    override fun isChannelAllowed(
        channelId: String,
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Boolean = opsAdapter.isChannelAllowed(channelId, allowedGuildIds, allowedChannelIds)

    override fun isGuildAllowed(guildId: String, allowedGuildIds: Set<String>): Boolean =
        opsAdapter.isGuildAllowed(guildId, allowedGuildIds)

    override fun listGuilds(allowedGuildIds: Set<String>): Map<String, Any?> =
        opsAdapter.listGuilds(allowedGuildIds)

    override fun readCapabilities(guildId: String): Map<String, Any?> =
        opsAdapter.readCapabilities(guildId)

    override fun listChannels(
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Map<String, Any?> = opsAdapter.listChannels(allowedGuildIds, allowedChannelIds)

    override fun listRoles(guildId: String): Map<String, Any?> = opsAdapter.listRoles(guildId)
    override fun listInvites(guildId: String) = opsAdapter.listInvites(guildId)
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
    override fun mutateGuild(request: DiscordGuildMutationRequest) = opsAdapter.mutateGuild(request)
    override fun mutateInvite(request: DiscordInviteMutationRequest) = opsAdapter.mutateInvite(request)

    private fun configuredChannelAliases(): Map<String, String> =
        OPS_CHANNEL_ALIASES.associateWith { config.string("channels.$it", "").trim() }

    @Synchronized
    override fun close() {
        ForumTicketSync.stop()
        moderation?.close()
        linkProtection?.close()
        gameEvents?.close()
        presence?.close()
        roleSyncCoordinator?.close()
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
