package ru.arc.telegram

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.forum.CloseForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.CloseGeneralForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.DeleteForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.EditForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.EditGeneralForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.HideGeneralForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.ReopenForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.ReopenGeneralForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.UnhideGeneralForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.UnpinAllForumTopicMessages
import org.telegram.telegrambots.meta.api.methods.forum.UnpinAllGeneralForumTopicMessages
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeclineChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeleteChatPhoto
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeleteChatStickerSet
import org.telegram.telegrambots.meta.api.methods.groupadministration.EditChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.PromoteChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.RevokeChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatAdministratorCustomTitle
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatPermissions
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatPhoto
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatStickerSet
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatTitle
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinAllChatMessages
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.ChatInviteLink
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException
import ru.arc.Utils.plain
import ru.arc.channelsync.ChannelSyncModule
import ru.arc.portal.PortalChatChannel
import ru.arc.portal.PortalChatMessage
import ru.arc.portal.PortalChatSource
import ru.arc.channelsync.TelegramSyncMessage
import ru.arc.channelsync.TelegramSyncEntity
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.discord.DiscordBot
import ru.arc.ops.TelegramAttachmentSpec
import ru.arc.ops.TelegramAttachmentType
import ru.arc.ops.TelegramButtonSpec
import ru.arc.ops.TelegramChatMutationRequest
import ru.arc.ops.TelegramChatMutation
import ru.arc.ops.TelegramInviteMutation
import ru.arc.ops.TelegramInviteMutationRequest
import ru.arc.ops.TelegramMemberMutation
import ru.arc.ops.TelegramMemberMutationRequest
import ru.arc.ops.TelegramMessageMutation
import ru.arc.ops.TelegramMessageMutationRequest
import ru.arc.ops.TelegramOpsGateway
import ru.arc.ops.TelegramParseMode
import ru.arc.ops.TelegramTopicMutation
import ru.arc.ops.TelegramTopicMutationRequest
import ru.arc.velocity.Velocity
import java.io.ByteArrayInputStream
import java.io.Serializable
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

open class TelegramBot(
    private val config: TelegramConfig = TelegramConfig.load(),
    token: String = config.token,
    private val scheduler: TaskScheduler = Tasks.scheduler,
    requestExecutor: ((SendMessage) -> Unit)? = null,
    private val apiExecutor: ((BotApiMethod<*>) -> CompletableFuture<*>)? = null,
    private val inboundRelay: TelegramInboundRelay = VelocityTelegramInboundRelay,
    private val identityService: TelegramIdentityService? = null,
    botOptions: DefaultBotOptions = DefaultBotOptions(),
) : TelegramLongPollingBot(botOptions, token),
    TelegramOpsGateway,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val requestExecutor: (SendMessage) -> Unit = requestExecutor ?: { execute(it) }
    private val minecraftIdentityMessages by lazy { TelegramVerificationMessages(config) }

    override fun onUpdateReceived(update: Update) {
        val edited = update.editedMessage ?: update.editedChannelPost
        if (edited != null) {
            relayGenericEdit(edited)
            return
        }
        val incoming = update.message ?: update.channelPost ?: return
        val author = incoming.from
        if (author?.isBot == true) return
        val commandText = incoming.text?.takeIf(String::isNotBlank)
        if (commandText != null && handleIdentityCommand(incoming, commandText)) return
        val text = commandText ?: incoming.caption?.takeIf(String::isNotBlank) ?: return
        val threadId = incoming.messageThreadId
        val chatId = incoming.chatId.toString()

        log.info(
            "Telegram message chat={} thread={} message={} chars={}",
            chatId,
            threadId,
            incoming.messageId,
            text.length,
        )

        val sender = telegramSender(incoming)
        val portalChannel =
            when {
                config.chatDestination?.matches(chatId, threadId) == true -> PortalChatChannel.GAME
                config.generalDestination?.matches(chatId, threadId) == true -> PortalChatChannel.COMMUNITY
                else -> null
            }
        if (portalChannel != null) {
            Velocity.portalBridge?.publishChat(
                PortalChatMessage(
                    sourceEventId = "telegram:$chatId:${incoming.messageId}",
                    source = PortalChatSource.TELEGRAM,
                    channel = portalChannel,
                    authorUuid = author?.let { identityService?.findByTelegramUserId(it.id)?.playerUuid },
                    authorName = sender,
                    content = text,
                    createdAt = incoming.date.toLong() * 1_000,
                ),
            )
        }
        val syncMessage =
            TelegramSyncMessage(
                chatId = chatId,
                threadId = threadId,
                messageId = incoming.messageId,
                sender = sender,
                text = text,
                replyToMessageId = incoming.replyToMessage?.messageId,
                entities = telegramEntities(incoming, caption = commandText == null),
            )
        if (Velocity.channelSync?.relayTelegram(syncMessage) == true) {
            return
        }
        when {
            config.chatDestination.matches(chatId, threadId) -> propagateChatMessage(syncMessage)
            config.generalDestination.matches(chatId, threadId) -> propagateGeneralMessage(syncMessage)
        }
    }

    private fun relayGenericEdit(incoming: Message) {
        if (incoming.from?.isBot == true) return
        val directText = incoming.text?.takeIf(String::isNotBlank)
        val text = directText ?: incoming.caption?.takeIf(String::isNotBlank) ?: return
        Velocity.channelSync?.editTelegram(
            TelegramSyncMessage(
                chatId = incoming.chatId.toString(),
                threadId = incoming.messageThreadId,
                messageId = incoming.messageId,
                sender = telegramSender(incoming),
                text = text,
                replyToMessageId = incoming.replyToMessage?.messageId,
                entities = telegramEntities(incoming, caption = directText == null),
            ),
        )
    }

    private fun telegramSender(message: Message): String =
        message.from?.let { author ->
            val displayName = telegramDisplayName(author.firstName, author.lastName)
            identityService?.updateTelegramProfile(author.id, author.userName, displayName)?.playerName
                ?: author.userName?.takeIf(String::isNotBlank)
                ?: displayName
        } ?: message.authorSignature?.takeIf(String::isNotBlank)
            ?: message.senderChat?.title?.takeIf(String::isNotBlank)
            ?: "Telegram"

    private fun telegramEntities(
        message: Message,
        caption: Boolean,
    ): List<TelegramSyncEntity> {
        val text = if (caption) message.caption.orEmpty() else message.text.orEmpty()
        val entities = if (caption) message.captionEntities.orEmpty() else message.entities.orEmpty()
        return entities.mapNotNull { entity ->
            val offset = entity.offset
            val length = entity.length
            if (offset < 0 || length <= 0 || offset + length > text.length) return@mapNotNull null
            val raw = text.substring(offset, offset + length)
            TelegramSyncEntity(
                type = entity.type.orEmpty(),
                offset = offset,
                length = length,
                url = entity.url,
                userId = entity.user?.id,
                username = entity.user?.userName ?: raw.takeIf { entity.type == "mention" }?.removePrefix("@"),
                language = entity.language,
                customEmojiId = entity.customEmojiId,
            )
        }
    }

    private fun handleIdentityCommand(
        message: Message,
        text: String,
    ): Boolean {
        val parts = text.trim().split(Regex("\\s+"), limit = 3)
        val command = parts.firstOrNull()?.substringBefore('@')?.lowercase() ?: return false
        if (command !in setOf("/start", "/status", "/verify", "/unlink", "/help")) {
            if (command.startsWith("/") && message.chat.type == "private") {
                replyIdentity(message, config.identityMessage("help"))
                return true
            }
            return false
        }
        val identity = identityService
        if (!config.identityEnabled || identity == null) {
            replyIdentity(message, config.identityMessage("minecraft-unavailable"))
            return true
        }
        if (config.identityPrivateChatOnly && message.chat.type != "private") {
            replyIdentity(message, config.identityMessage("private-only"))
            return true
        }
        val author = message.from
        if (author == null || author.isBot) return true
        scheduler.runAsync {
            if (closed.get()) return@runAsync
            val response =
                when (command) {
                    "/start", "/status" ->
                        identity.findByTelegramUserId(author.id)?.let { link ->
                            config.identityMessage("welcome-linked", mapOf("player_name" to link.playerName))
                        } ?: config.identityMessage("welcome-not-linked")
                    "/help" -> config.identityMessage("help")
                    "/verify" ->
                        if (parts.size == 1) {
                            identity.findByTelegramUserId(author.id)?.let { link ->
                                config.identityMessage("status-linked", mapOf("player_name" to link.playerName))
                            } ?: config.identityMessage("status-not-linked")
                        } else {
                            when (
                                val result =
                                    identity.completeChallenge(
                                        rawCode = parts[1],
                                        telegramUserId = author.id,
                                        telegramUsername = author.userName,
                                        telegramDisplayName = telegramDisplayName(author.firstName, author.lastName),
                                    )
                            ) {
                                is TelegramChallengeCompletionResult.Linked ->
                                    config.identityMessage(
                                        if (result.idempotent) "verified-idempotent" else "verified",
                                        mapOf("player_name" to result.link.playerName),
                                    )
                                TelegramChallengeCompletionResult.InvalidOrExpired ->
                                    config.identityMessage("invalid-or-expired")
                                TelegramChallengeCompletionResult.MinecraftAlreadyLinked ->
                                    config.identityMessage("minecraft-already-linked")
                                TelegramChallengeCompletionResult.TelegramAlreadyLinked ->
                                    config.identityMessage("telegram-already-linked")
                                is TelegramChallengeCompletionResult.RateLimited ->
                                    config.identityMessage(
                                        "rate-limited",
                                        mapOf("minutes" to retryMinutes(result.retryAt).toString()),
                                    )
                                TelegramChallengeCompletionResult.Unavailable ->
                                    config.identityMessage("minecraft-unavailable")
                            }
                        }
                    else ->
                        if (parts.getOrNull(1)?.equals("confirm", true) != true) {
                            config.identityMessage("unlink-confirm")
                        } else {
                            when (val result = identity.unlinkByTelegram(author.id)) {
                                is TelegramUnlinkResult.Unlinked ->
                                    config.identityMessage(
                                        "unlink-success",
                                        mapOf("player_name" to result.previousLink.playerName),
                                    )
                                TelegramUnlinkResult.NotLinked -> config.identityMessage("unlink-not-linked")
                                TelegramUnlinkResult.Unavailable -> config.identityMessage("minecraft-unavailable")
                            }
                        }
                }
            val showCommunity =
                command == "/start" ||
                    command == "/status" ||
                    command == "/help" ||
                    (command == "/verify" && identity.findByTelegramUserId(author.id) != null)
            replyIdentity(message, response, showCommunity)
        }
        return true
    }

    private fun replyIdentity(
        source: Message,
        text: String,
        showCommunity: Boolean = false,
    ) {
        enqueue(
            SendMessage(source.chatId.toString(), text).also { reply ->
                reply.replyToMessageId = source.messageId
                if (showCommunity) {
                    reply.replyMarkup = communityKeyboard()
                }
            },
        )
    }

    private fun communityKeyboard(): InlineKeyboardMarkup? =
        config.informationUrl?.let { url ->
            InlineKeyboardMarkup(
                listOf(
                    listOf(
                        InlineKeyboardButton("Перейти в Telegram RusCrafting").also { button ->
                            button.url = url
                        },
                    ),
                ),
            )
        }

    internal fun isIdentityEnabled(): Boolean = config.identityEnabled && identityService != null

    internal fun identityMessage(
        key: String,
        values: Map<String, String> = emptyMap(),
    ): String = config.identityMessage(key, values)

    internal fun verificationMessages(): TelegramVerificationMessages = minecraftIdentityMessages

    internal fun isIdentityBackendAllowed(backend: String): Boolean =
        backend.lowercase() in config.identityAllowedBackends

    internal fun issueIdentityChallenge(
        playerUuid: UUID,
        playerName: String,
    ): TelegramChallengeIssueResult =
        identityService?.issueChallenge(playerUuid, playerName) ?: TelegramChallengeIssueResult.Unavailable

    internal fun unlinkIdentityByMinecraft(playerUuid: UUID): TelegramUnlinkResult =
        identityService?.unlinkByPlayer(playerUuid) ?: TelegramUnlinkResult.Unavailable

    internal fun findIdentityByPlayer(playerUuid: UUID): TelegramIdentityLink? =
        identityService?.findByPlayerUuid(playerUuid)

    internal fun findIdentityByTelegramUser(telegramUserId: Long): TelegramIdentityLink? =
        identityService?.findByTelegramUserId(telegramUserId)

    internal fun findIdentityByTelegramUsername(username: String): TelegramIdentityLink? =
        identityService?.findByTelegramUsername(username)

    internal fun identitySnapshot(): List<TelegramIdentityLink>? =
        identityService?.takeIf { config.identityEnabled }?.allLinks()

    internal fun refreshIdentity(
        playerUuid: UUID,
        playerName: String,
    ) {
        val identity = identityService ?: return
        scheduler.runAsync { identity.updatePlayerName(playerUuid, playerName) }
    }

    private fun propagateChatMessage(message: TelegramSyncMessage) {
        val codec = ChannelSyncModule.textCodec()
        val translated = codec.telegramToDiscord(message)
        inboundRelay.relayChat(
            discordMessage =
                formatPlain(
                    config.discordFormat,
                    codec.safeDiscordPlainText(message.sender),
                    translated.text,
                ),
            minecraftMessage = formatMinecraft(config.chatFormat, message.sender, message.text),
            allowedDiscordUserMentionIds = translated.allowedUserMentionIds,
        )
    }

    private fun propagateGeneralMessage(message: TelegramSyncMessage) {
        val codec = ChannelSyncModule.textCodec()
        val translated = codec.telegramToDiscord(message)
        inboundRelay.relayGeneral(
            discordMessage =
                formatPlain(
                    config.discordFormat,
                    codec.safeDiscordPlainText(message.sender),
                    translated.text,
                ),
            allowedDiscordUserMentionIds = translated.allowedUserMentionIds,
        )
    }

    fun sendChatMessage(
        message: String,
        parseMode: TelegramParseMode = TelegramParseMode.NONE,
    ) {
        config.chatDestination?.let { sendTo(it, message, parseMode) }
    }

    fun sendGeneralMessage(
        message: String,
        parseMode: TelegramParseMode = TelegramParseMode.NONE,
    ) {
        config.generalDestinations().forEach { sendTo(it, message, parseMode) }
    }

    private fun sendTo(
        destination: TelegramDestination,
        message: String,
        parseMode: TelegramParseMode,
    ) {
        splitMessage(message).forEach { part ->
            enqueue(
                SendMessage(destination.chatId, part).also { request ->
                    request.messageThreadId = destination.threadId
                    request.parseMode = parseMode.apiValue
                },
            )
        }
    }

    override fun getBotUsername(): String = config.botUsername

    fun sendJoinMessage(
        username: String,
        joinType: DiscordBot.JoinType,
        messageOverride: String?,
    ) {
        val message = messageOverride ?: config.joinMessage(joinType.name.lowercase())
        sendChatMessage(plain(message.replace("%player_name%", username)))
    }

    internal fun enqueue(message: SendMessage) {
        if (closed.get()) return
        scheduler.runAsync {
            if (closed.get()) return@runAsync
            try {
                requestExecutor(message)
            } catch (e: Exception) {
                log.error("Failed to send message to Telegram", e)
            }
        }
    }

    internal fun probeConnectivity(): CompletableFuture<Unit> =
        executeWhenOpen(GetMe()).thenCompose { botUser ->
            require(botUser.id > 0L) { "Telegram getMe returned an invalid bot identity" }
            executeWhenOpen(privateCommandMenu()).thenApply { registered ->
                require(registered) { "Telegram rejected the private command menu" }
                connected.set(true)
            }
        }

    override fun isReady(): Boolean = connected.get() && !closed.get()

    override fun listChats(chatIds: Set<String>): CompletableFuture<Map<String, Any?>> {
        val reads = chatIds.sorted().map(::readChat)
        return CompletableFuture.allOf(*reads.toTypedArray()).thenApply {
            mapOf("chats" to reads.map(CompletableFuture<Map<String, Any?>>::join))
        }
    }

    override fun readChat(chatId: String): CompletableFuture<Map<String, Any?>> =
        executeWhenOpen(GetChat(chatId)).thenApply(::chatMap)

    override fun listAdministrators(chatId: String): CompletableFuture<Map<String, Any?>> =
        executeWhenOpen(GetChatAdministrators(chatId)).thenApply { administrators ->
            mapOf(
                "chatId" to chatId,
                "count" to administrators.size,
                "administrators" to administrators.map(::chatMemberMap),
            )
        }

    override fun readMember(
        chatId: String,
        userId: Long,
    ): CompletableFuture<Map<String, Any?>> =
        executeWhenOpen(GetChatMember(chatId, userId)).thenApply { member ->
            mapOf("chatId" to chatId, "member" to chatMemberMap(member))
        }

    override fun mutateMessage(request: TelegramMessageMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            TelegramMessageMutation.SEND -> {
                sendRichMessage(request).thenApply(::messageMap)
            }
            TelegramMessageMutation.EDIT -> {
                val method = EditMessageText()
                method.chatId = request.chatId
                method.messageId = requireNotNull(request.messageId)
                method.text = requireNotNull(request.text)
                method.parseMode = request.parseMode.apiValue
                method.replyMarkup = inlineKeyboard(request.buttons)
                method.disableWebPagePreview = request.disableWebPagePreview
                executeWhenOpen(method).thenApply { result -> mutationResult(request.chatId, request.messageId, result) }
            }
            TelegramMessageMutation.DELETE -> {
                val method = DeleteMessage(request.chatId, requireNotNull(request.messageId))
                executeWhenOpen(method).thenApply { success ->
                    booleanMutationResult(request.chatId, request.messageId, success)
                }
            }
            TelegramMessageMutation.PIN -> {
                val method = PinChatMessage(request.chatId, requireNotNull(request.messageId))
                method.disableNotification = request.disableNotification
                executeWhenOpen(method).thenApply { success ->
                    booleanMutationResult(request.chatId, request.messageId, success)
                }
            }
            TelegramMessageMutation.UNPIN -> {
                val method = UnpinChatMessage(request.chatId, requireNotNull(request.messageId))
                executeWhenOpen(method).thenApply { success ->
                    booleanMutationResult(request.chatId, request.messageId, success)
                }
            }
        }

    override fun mutateChat(request: TelegramChatMutationRequest): CompletableFuture<Map<String, Any?>> {
        return when (request.operation) {
            TelegramChatMutation.UPDATE -> {
                val mutations =
                    buildList<CompletableFuture<Boolean>> {
                        request.title?.let { title -> add(executeWhenOpen(SetChatTitle(request.chatId, title))) }
                        request.description?.let { description ->
                            add(executeWhenOpen(SetChatDescription(request.chatId, description)))
                        }
                    }
                CompletableFuture.allOf(*mutations.toTypedArray()).thenApply {
                    mapOf(
                        "chatId" to request.chatId,
                        "updated" to
                            buildList {
                                if (request.title != null) add("title")
                                if (request.description != null) add("description")
                            },
                        "success" to mutations.all { it.join() },
                    )
                }
            }
            TelegramChatMutation.SET_PERMISSIONS -> {
                val method = SetChatPermissions()
                method.chatId = request.chatId
                method.permissions = requireNotNull(request.permissions).toApi()
                method.useIndependentChatPermissions = request.useIndependentPermissions
                executeWhenOpen(method).thenApply { success -> chatMutationMap(request, "permissions", success) }
            }
            TelegramChatMutation.SET_PHOTO -> {
                val method = SetChatPhoto(request.chatId, requireNotNull(request.photo).toInputFile())
                executeMediaWhenOpen(method).thenApply { success -> chatMutationMap(request, "photo", success) }
            }
            TelegramChatMutation.DELETE_PHOTO ->
                executeWhenOpen(DeleteChatPhoto(request.chatId)).thenApply { success ->
                    chatMutationMap(request, "photo", success)
                }
            TelegramChatMutation.UNPIN_ALL ->
                executeWhenOpen(UnpinAllChatMessages(request.chatId)).thenApply { success ->
                    chatMutationMap(request, "pinnedMessages", success)
                }
            TelegramChatMutation.SET_STICKER_SET ->
                executeWhenOpen(SetChatStickerSet(request.chatId, requireNotNull(request.stickerSetName))).thenApply { success ->
                    chatMutationMap(request, "stickerSet", success)
                }
            TelegramChatMutation.DELETE_STICKER_SET ->
                executeWhenOpen(DeleteChatStickerSet(request.chatId)).thenApply { success ->
                    chatMutationMap(request, "stickerSet", success)
                }
        }
    }

    override fun mutateTopic(request: TelegramTopicMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            TelegramTopicMutation.CREATE -> {
                val method = CreateForumTopic(request.chatId, requireNotNull(request.name))
                method.iconColor = request.iconColor
                method.iconCustomEmojiId = request.iconCustomEmojiId
                executeWhenOpen(method).thenApply { topic -> topicMap(request.chatId, topic) }
            }
            TelegramTopicMutation.UPDATE -> {
                executeWhenOpen(editForumTopicMethod(request)).thenApply { success -> topicMutationMap(request, success) }
            }
            TelegramTopicMutation.CLOSE ->
                executeWhenOpen(CloseForumTopic(request.chatId, requireNotNull(request.threadId)))
                    .thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.REOPEN ->
                executeWhenOpen(ReopenForumTopic(request.chatId, requireNotNull(request.threadId)))
                    .thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.DELETE ->
                executeWhenOpen(DeleteForumTopic(request.chatId, requireNotNull(request.threadId)))
                    .thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.UNPIN_ALL ->
                executeWhenOpen(UnpinAllForumTopicMessages(request.chatId, requireNotNull(request.threadId)))
                    .thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_UPDATE ->
                executeWhenOpen(EditGeneralForumTopic(request.chatId, requireNotNull(request.name)))
                    .thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_CLOSE ->
                executeWhenOpen(CloseGeneralForumTopic(request.chatId)).thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_REOPEN ->
                executeWhenOpen(ReopenGeneralForumTopic(request.chatId)).thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_HIDE ->
                executeWhenOpen(HideGeneralForumTopic(request.chatId)).thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_UNHIDE ->
                executeWhenOpen(UnhideGeneralForumTopic(request.chatId)).thenApply { success -> topicMutationMap(request, success) }
            TelegramTopicMutation.GENERAL_UNPIN_ALL ->
                executeWhenOpen(UnpinAllGeneralForumTopicMessages(request.chatId))
                    .thenApply { success -> topicMutationMap(request, success) }
        }

    override fun mutateInvite(request: TelegramInviteMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            TelegramInviteMutation.CREATE -> {
                val method = CreateChatInviteLink(request.chatId)
                method.name = request.name
                method.expireDate = request.expireDate
                method.memberLimit = request.memberLimit
                method.createsJoinRequest = request.createsJoinRequest
                executeWhenOpen(method).thenApply { invite -> inviteMap(request.chatId, invite) }
            }
            TelegramInviteMutation.EDIT -> {
                val method = EditChatInviteLink(request.chatId, requireNotNull(request.inviteLink))
                method.name = request.name
                method.expireDate = request.expireDate
                method.memberLimit = request.memberLimit
                method.createsJoinRequest = request.createsJoinRequest
                executeWhenOpen(method).thenApply { invite -> inviteMap(request.chatId, invite) }
            }
            TelegramInviteMutation.REVOKE ->
                executeWhenOpen(RevokeChatInviteLink(request.chatId, requireNotNull(request.inviteLink)))
                    .thenApply { invite -> inviteMap(request.chatId, invite) }
        }

    override fun mutateMember(request: TelegramMemberMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            TelegramMemberMutation.BAN -> {
                val method = BanChatMember(request.chatId, request.userId)
                method.untilDate = request.untilDate
                method.revokeMessages = request.revokeMessages
                executeWhenOpen(method).thenApply { success -> memberMutationMap(request, success) }
            }
            TelegramMemberMutation.UNBAN -> {
                val method = UnbanChatMember(request.chatId, request.userId)
                method.onlyIfBanned = request.onlyIfBanned
                executeWhenOpen(method).thenApply { success -> memberMutationMap(request, success) }
            }
            TelegramMemberMutation.RESTRICT -> {
                val method = RestrictChatMember(request.chatId, request.userId, requireNotNull(request.permissions).toApi())
                method.untilDate = request.untilDate
                method.useIndependentChatPermissions = request.useIndependentPermissions
                executeWhenOpen(method).thenApply { success -> memberMutationMap(request, success) }
            }
            TelegramMemberMutation.PROMOTE -> {
                val rights = requireNotNull(request.administratorRights)
                val method = PromoteChatMember(request.chatId, request.userId)
                method.canManageChat = rights.canManageChat
                method.canChangeInformation = rights.canChangeInfo
                method.canPostMessages = rights.canPostMessages
                method.canEditMessages = rights.canEditMessages
                method.canDeleteMessages = rights.canDeleteMessages
                method.canInviteUsers = rights.canInviteUsers
                method.canRestrictMembers = rights.canRestrictMembers
                method.canPinMessages = rights.canPinMessages
                method.canPromoteMembers = rights.canPromoteMembers
                method.canManageVideoChats = rights.canManageVideoChats
                method.canManageTopics = rights.canManageTopics
                method.canPostStories = rights.canPostStories
                method.canEditStories = rights.canEditStories
                method.canDeleteStories = rights.canDeleteStories
                method.isAnonymous = rights.isAnonymous
                executeWhenOpen(method).thenApply { success -> memberMutationMap(request, success) }
            }
            TelegramMemberMutation.SET_ADMIN_TITLE ->
                executeWhenOpen(
                    SetChatAdministratorCustomTitle(request.chatId, request.userId, requireNotNull(request.customTitle)),
                ).thenApply { success -> memberMutationMap(request, success) }
            TelegramMemberMutation.APPROVE_JOIN_REQUEST ->
                executeWhenOpen(ApproveChatJoinRequest(request.chatId, request.userId))
                    .thenApply { success -> memberMutationMap(request, success) }
            TelegramMemberMutation.DECLINE_JOIN_REQUEST ->
                executeWhenOpen(DeclineChatJoinRequest(request.chatId, request.userId))
                    .thenApply { success -> memberMutationMap(request, success) }
        }

    private fun sendRichMessage(request: TelegramMessageMutationRequest): CompletableFuture<Message> {
        val keyboard = inlineKeyboard(request.buttons)
        val attachment = request.attachment
        if (attachment == null) {
            val method = SendMessage(request.chatId, requireNotNull(request.text))
            method.messageThreadId = request.threadId
            method.replyToMessageId = request.replyToMessageId
            method.disableNotification = request.disableNotification
            method.parseMode = request.parseMode.apiValue
            method.disableWebPagePreview = request.disableWebPagePreview
            method.protectContent = request.protectContent
            method.replyMarkup = keyboard
            return executeWhenOpen(method)
        }
        return when (attachment.type) {
            TelegramAttachmentType.PHOTO -> {
                val method = SendPhoto(request.chatId, attachment.toInputFile())
                method.messageThreadId = request.threadId
                method.replyToMessageId = request.replyToMessageId
                method.disableNotification = request.disableNotification
                method.caption = request.text
                method.parseMode = request.parseMode.apiValue
                method.protectContent = request.protectContent
                method.hasSpoiler = attachment.hasSpoiler
                method.replyMarkup = keyboard
                executeMediaWhenOpen(method)
            }
            TelegramAttachmentType.DOCUMENT -> {
                val method = SendDocument(request.chatId, attachment.toInputFile())
                method.messageThreadId = request.threadId
                method.replyToMessageId = request.replyToMessageId
                method.disableNotification = request.disableNotification
                method.caption = request.text
                method.parseMode = request.parseMode.apiValue
                method.protectContent = request.protectContent
                method.replyMarkup = keyboard
                executeMediaWhenOpen(method)
            }
        }
    }

    private fun executeMediaWhenOpen(method: SendPhoto): CompletableFuture<Message> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("telegram bot is closed"))
        return try {
            executeAsync(method)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
    }

    private fun executeMediaWhenOpen(method: SendDocument): CompletableFuture<Message> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("telegram bot is closed"))
        return try {
            executeAsync(method)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
    }

    private fun executeMediaWhenOpen(method: SetChatPhoto): CompletableFuture<Boolean> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("telegram bot is closed"))
        return try {
            executeAsync(method)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Serializable> executeWhenOpen(method: BotApiMethod<T>): CompletableFuture<T> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("telegram bot is closed"))
        return try {
            apiExecutor?.invoke(method) as? CompletableFuture<T> ?: executeAsync(method)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
    }

    override fun close() {
        closed.set(true)
        connected.set(false)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelegramBot::class.java)
        private val FORMAT_TOKEN = Regex("%(?:sender|message)%")
        private const val TELEGRAM_MESSAGE_LIMIT = 4_096

        private fun telegramDisplayName(
            firstName: String?,
            lastName: String?,
        ): String = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifBlank { "Telegram user" }

        private fun retryMinutes(retryAt: Long): Long =
            TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
                .coerceAtLeast(1)

        private fun privateCommandMenu(): SetMyCommands =
            SetMyCommands().also { menu ->
                menu.scope = BotCommandScopeAllPrivateChats()
                menu.commands =
                    listOf(
                        BotCommand("start", "Открыть главное меню"),
                        BotCommand("verify", "Привязать Minecraft по коду"),
                        BotCommand("status", "Проверить привязку"),
                        BotCommand("unlink", "Отвязать Minecraft-аккаунт"),
                        BotCommand("help", "Показать доступные команды"),
                    )
            }

        internal fun splitMessage(message: String): List<String> {
            var remaining = message.trim()
            if (remaining.isEmpty()) return emptyList()
            val chunks = mutableListOf<String>()
            while (remaining.length > TELEGRAM_MESSAGE_LIMIT) {
                var splitAt = remaining.lastIndexOf('\n', TELEGRAM_MESSAGE_LIMIT)
                if (splitAt <= 0) splitAt = remaining.lastIndexOf(' ', TELEGRAM_MESSAGE_LIMIT)
                if (splitAt <= 0) splitAt = TELEGRAM_MESSAGE_LIMIT
                if (splitAt < remaining.length && splitAt > 0 &&
                    remaining[splitAt - 1].isHighSurrogate() && remaining[splitAt].isLowSurrogate()
                ) {
                    splitAt--
                }
                chunks += remaining.take(splitAt).trimEnd()
                remaining = remaining.drop(splitAt).trimStart()
            }
            if (remaining.isNotEmpty()) chunks += remaining
            return chunks.filter(String::isNotBlank)
        }

        private fun formatPlain(
            pattern: String,
            sender: String,
            message: String,
        ): String =
            FORMAT_TOKEN.replace(pattern) { match ->
                when (match.value) {
                    "%sender%" -> sender
                    else -> message
                }
            }

        private fun formatMinecraft(
            pattern: String,
            sender: String,
            message: String,
        ): Component =
            MiniMessage.miniMessage().deserialize(
                FORMAT_TOKEN.replace(pattern) { match ->
                    when (match.value) {
                        "%sender%" -> "<telegram_sender>"
                        else -> "<telegram_message>"
                    }
                },
                Placeholder.unparsed("telegram_sender", sender),
                Placeholder.unparsed("telegram_message", message),
            )

        private fun TelegramDestination?.matches(
            chatId: String,
            threadId: Int?,
        ): Boolean = this != null && this.chatId == chatId && this.threadId == threadId

        private fun chatMap(chat: Chat): Map<String, Any?> =
            linkedMapOf(
                "id" to chat.id.toString(),
                "type" to chat.type,
                "title" to chat.title,
                "username" to chat.userName,
                "description" to chat.description,
                "inviteLink" to chat.inviteLink,
                "linkedChatId" to chat.linkedChatId?.toString(),
                "forum" to (chat.isForum ?: false),
                "pinnedMessageId" to chat.pinnedMessage?.messageId,
            )

        private fun inlineKeyboard(rows: List<List<TelegramButtonSpec>>): InlineKeyboardMarkup? =
            rows.takeIf(List<*>::isNotEmpty)?.let { buttons ->
                InlineKeyboardMarkup(
                    buttons.map { row ->
                        row.map { spec ->
                            InlineKeyboardButton(spec.text).also { it.url = spec.url }
                        }
                    },
                )
            }

        private fun TelegramAttachmentSpec.toInputFile(): InputFile =
            dataBase64?.let { encoded ->
                InputFile(ByteArrayInputStream(Base64.getDecoder().decode(encoded)), requireNotNull(fileName))
            } ?: InputFile(requireNotNull(media))

        private fun ru.arc.ops.TelegramChatPermissionsSpec.toApi(): ChatPermissions =
            ChatPermissions().also { permissions ->
                permissions.canSendMessages = canSendMessages
                permissions.canSendAudios = canSendAudios
                permissions.canSendDocuments = canSendDocuments
                permissions.canSendPhotos = canSendPhotos
                permissions.canSendVideos = canSendVideos
                permissions.canSendVideoNotes = canSendVideoNotes
                permissions.canSendVoiceNotes = canSendVoiceNotes
                permissions.canSendPolls = canSendPolls
                permissions.canSendOtherMessages = canSendOtherMessages
                permissions.canAddWebPagePreviews = canAddWebPagePreviews
                permissions.canChangeInfo = canChangeInfo
                permissions.canInviteUsers = canInviteUsers
                permissions.canPinMessages = canPinMessages
                permissions.canManageTopics = canManageTopics
            }

        private fun chatMutationMap(
            request: TelegramChatMutationRequest,
            field: String,
            success: Boolean,
        ): Map<String, Any?> =
            mapOf(
                "chatId" to request.chatId,
                "operation" to request.operation.name,
                "updated" to listOf(field),
                "success" to success,
            )

        private fun chatMemberMap(member: ChatMember): Map<String, Any?> {
            val user = member.user
            val payload =
                linkedMapOf<String, Any?>(
                    "status" to member.status,
                    "userId" to user.id,
                    "username" to user.userName,
                    "firstName" to user.firstName,
                    "lastName" to user.lastName,
                    "bot" to user.isBot,
                )
            when (member) {
                is ChatMemberAdministrator -> {
                    payload["customTitle"] = member.customTitle
                    payload["canBeEdited"] = member.canBeEdited
                    payload["isAnonymous"] = member.isAnonymous
                    payload["canManageChat"] = member.canManageChat
                    payload["canChangeInfo"] = member.canChangeInfo
                    payload["canPostMessages"] = member.canPostMessages
                    payload["canEditMessages"] = member.canEditMessages
                    payload["canDeleteMessages"] = member.canDeleteMessages
                    payload["canInviteUsers"] = member.canInviteUsers
                    payload["canRestrictMembers"] = member.canRestrictMembers
                    payload["canPinMessages"] = member.canPinMessages
                    payload["canPromoteMembers"] = member.canPromoteMembers
                    payload["canManageVideoChats"] = member.canManageVideoChats
                    payload["canManageTopics"] = member.canManageTopics
                }
                is ChatMemberRestricted -> {
                    payload["member"] = member.isMember
                    payload["untilDate"] = member.untilDate
                    payload["canSendMessages"] = member.canSendMessages
                    payload["canSendAudios"] = member.canSendAudios
                    payload["canSendDocuments"] = member.canSendDocuments
                    payload["canSendPhotos"] = member.canSendPhotos
                    payload["canSendVideos"] = member.canSendVideos
                    payload["canSendVideoNotes"] = member.canSendVideoNotes
                    payload["canSendVoiceNotes"] = member.canSendVoiceNotes
                    payload["canSendPolls"] = member.canSendPolls
                    payload["canSendOtherMessages"] = member.canSendOtherMessages
                    payload["canAddWebPagePreviews"] = member.canAddWebpagePreviews
                    payload["canChangeInfo"] = member.canChangeInfo
                    payload["canInviteUsers"] = member.canInviteUsers
                    payload["canPinMessages"] = member.canPinMessages
                    payload["canManageTopics"] = member.canManageTopics
                }
            }
            return payload
        }

        private fun memberMutationMap(
            request: TelegramMemberMutationRequest,
            success: Boolean,
        ): Map<String, Any?> =
            mapOf(
                "chatId" to request.chatId,
                "userId" to request.userId,
                "operation" to request.operation.name,
                "success" to success,
            )

        private fun topicMap(
            chatId: String,
            topic: ForumTopic,
        ): Map<String, Any?> =
            mapOf(
                "chatId" to chatId,
                "threadId" to topic.messageThreadId,
                "name" to topic.name,
                "iconColor" to topic.iconColor,
                "iconCustomEmojiId" to topic.iconCustomEmojiId,
            )

        private fun topicMutationMap(
            request: TelegramTopicMutationRequest,
            success: Boolean,
        ): Map<String, Any?> =
            mapOf(
                "chatId" to request.chatId,
                "threadId" to request.threadId,
                "operation" to request.operation.name,
                "success" to success,
            )

        private fun inviteMap(
            chatId: String,
            invite: ChatInviteLink,
        ): Map<String, Any?> =
            mapOf(
                "chatId" to chatId,
                "inviteLink" to invite.inviteLink,
                "name" to invite.name,
                "expireDate" to invite.expireDate,
                "memberLimit" to invite.memberLimit,
                "createsJoinRequest" to invite.createsJoinRequest,
                "revoked" to invite.isRevoked,
            )

        private fun messageMap(message: Message): Map<String, Any?> =
            linkedMapOf(
                "chatId" to message.chatId.toString(),
                "messageId" to message.messageId,
                "threadId" to message.messageThreadId,
            )

        private fun mutationResult(
            chatId: String,
            messageId: Int?,
            result: Serializable,
        ): Map<String, Any?> =
            when (result) {
                is Message -> messageMap(result)
                is Boolean -> booleanMutationResult(chatId, messageId, result)
                else -> mapOf("chatId" to chatId, "messageId" to messageId, "success" to true)
            }

        private fun booleanMutationResult(
            chatId: String,
            messageId: Int?,
            success: Boolean,
        ): Map<String, Any?> =
            mapOf("chatId" to chatId, "messageId" to messageId, "success" to success)
    }
}

internal fun editForumTopicMethod(request: TelegramTopicMutationRequest): EditForumTopic =
    OptionalIconEditForumTopic().apply {
        chatId = request.chatId
        messageThreadId = requireNotNull(request.threadId)
        name = request.name
        request.iconCustomEmojiId?.let { iconCustomEmojiId = it }
    }

/** TelegramBots 6.9.7.1 incorrectly requires optional icon_custom_emoji_id. */
private class OptionalIconEditForumTopic : EditForumTopic() {
    override fun validate() {
        if (chatId.isNullOrEmpty()) throw TelegramApiValidationException("ChatId can't be empty", this)
        if (messageThreadId <= 0) {
            throw TelegramApiValidationException("Message Thread Id can't be empty", this)
        }
        if (name != null && name.length > 128) {
            throw TelegramApiValidationException("Name must be less than 128 characters", this)
        }
    }
}

interface TelegramInboundRelay {
    fun relayChat(
        discordMessage: String,
        minecraftMessage: Component,
        allowedDiscordUserMentionIds: Set<String>,
    )

    fun relayGeneral(
        discordMessage: String,
        allowedDiscordUserMentionIds: Set<String>,
    )
}

private object VelocityTelegramInboundRelay : TelegramInboundRelay {
    override fun relayChat(
        discordMessage: String,
        minecraftMessage: Component,
        allowedDiscordUserMentionIds: Set<String>,
    ) {
        Velocity.discordBot?.sendChatMessage(discordMessage, allowedDiscordUserMentionIds)
        Velocity.plugin?.sendMessageToAll(minecraftMessage)
    }

    override fun relayGeneral(
        discordMessage: String,
        allowedDiscordUserMentionIds: Set<String>,
    ) {
        Velocity.discordBot?.sendGeneralMessage(discordMessage, allowedDiscordUserMentionIds)
    }
}
