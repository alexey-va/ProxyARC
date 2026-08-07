package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.attribute.IPositionableChannel
import net.dv8tion.jda.api.entities.channel.attribute.IPostContainer
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.MediaChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import ru.arc.ops.DiscordAttachmentSpec
import ru.arc.ops.DiscordChannelMutation
import ru.arc.ops.DiscordChannelMutationRequest
import ru.arc.ops.DiscordEmbedSpec
import ru.arc.ops.DiscordHistoryRequest
import ru.arc.ops.DiscordMemberMutation
import ru.arc.ops.DiscordMemberMutationRequest
import ru.arc.ops.DiscordMemberReadRequest
import ru.arc.ops.DiscordMessageMutation
import ru.arc.ops.DiscordMessageMutationRequest
import ru.arc.ops.DiscordMessageRequest
import ru.arc.ops.DiscordOpsGateway
import ru.arc.ops.DiscordPermissionOverrideSpec
import ru.arc.ops.DiscordPinsRequest
import ru.arc.ops.DiscordRoleMutation
import ru.arc.ops.DiscordRoleMutationRequest
import ru.arc.ops.DiscordSearchRequest
import ru.arc.ops.DiscordThreadMutation
import ru.arc.ops.DiscordThreadMutationRequest
import java.awt.Color
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class DiscordOpsAdapter(
    private val jdaProvider: () -> JDA?,
    private val aliasesProvider: () -> Map<String, String>,
) : DiscordOpsGateway {
    override fun isReady(): Boolean = jdaProvider() != null

    override fun isGuildAllowed(
        guildId: String,
        allowedGuildIds: Set<String>,
    ): Boolean = ALL in allowedGuildIds || guildId in allowedGuildIds

    override fun isChannelAllowed(
        channelId: String,
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Boolean {
        val channel = jdaProvider()?.getGuildChannelById(channelId) ?: return false
        return (allowedGuildIds.isEmpty() || isGuildAllowed(channel.guild.id, allowedGuildIds)) &&
            isAllowed(channel.id, parentChannelId(channel), allowedChannelIds)
    }

    override fun listGuilds(allowedGuildIds: Set<String>): Map<String, Any?> {
        val jda = jdaProvider() ?: return mapOf("ready" to false, "guilds" to emptyList<Any>())
        val guilds =
            jda.guilds
                .filter { isGuildAllowed(it.id, allowedGuildIds) }
                .sortedBy { it.name.lowercase() }
                .map { guild ->
                    mapOf(
                        "id" to guild.id,
                        "name" to guild.name,
                        "ownerId" to guild.ownerId,
                        "memberCount" to guild.memberCount,
                        "channelCount" to guild.channels.size,
                        "roleCount" to guild.roles.size,
                        "iconUrl" to guild.iconUrl,
                    )
                }
        return mapOf("ready" to true, "count" to guilds.size, "guilds" to guilds)
    }

    override fun listChannels(
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Map<String, Any?> {
        val jda = jdaProvider() ?: return mapOf("ready" to false, "channels" to emptyList<Any>())
        val aliasesById = aliasesById()
        val candidates =
            if (ALL in allowedChannelIds) {
                jda.guilds
                    .filter { isGuildAllowed(it.id, allowedGuildIds) }
                    .flatMap { it.channels }
            } else {
                val roots = allowedChannelIds.mapNotNull(jda::getGuildChannelById)
                val children =
                    jda.threadChannels.filter { thread ->
                        thread.parentChannel.id in allowedChannelIds
                    }
                roots + children
            }
        val channels =
            candidates
                .filter { allowedGuildIds.isEmpty() || isGuildAllowed(it.guild.id, allowedGuildIds) }
                .distinctBy(GuildChannel::getId)
                .sortedWith(compareBy<GuildChannel>({ it.guild.name.lowercase() }, { parentChannelId(it) ?: it.id }, { it.name.lowercase() }))
                .map { channel -> channelPayload(channel, aliasesById[channel.id].orEmpty()) }
        return mapOf("ready" to true, "count" to channels.size, "channels" to channels)
    }

    override fun listRoles(guildId: String): Map<String, Any?> {
        val guild = requireGuild(guildId)
        val roles = guild.roles.sortedByDescending(Role::getPosition).map(::rolePayload)
        return mapOf("guildId" to guild.id, "count" to roles.size, "roles" to roles)
    }

    override fun readMember(request: DiscordMemberReadRequest): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        return guild.retrieveMemberById(request.userId).submit().thenApply { member ->
            mapOf("guildId" to guild.id, "member" to memberPayload(member))
        }
    }

    override fun readHistory(request: DiscordHistoryRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireMessageChannel(request.channelId)
        val future =
            if (request.beforeMessageId == null) {
                channel.history.retrievePast(request.limit).submit()
            } else {
                channel.getHistoryBefore(request.beforeMessageId, request.limit)
                    .submit()
                    .thenApply { it.retrievedHistory }
            }
        return future.thenApply { messages ->
            mapOf(
                "channel" to channelPayload(channel, aliasesFor(channel.id)),
                "count" to messages.size,
                "messages" to messages.map(::messagePayload),
                "nextBefore" to messages.lastOrNull()?.id,
            )
        }
    }

    override fun readMessage(request: DiscordMessageRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireMessageChannel(request.channelId)
        return channel.retrieveMessageById(request.messageId).submit().thenApply { message ->
            mapOf(
                "channel" to channelPayload(channel, aliasesFor(channel.id)),
                "message" to messagePayload(message),
            )
        }
    }

    override fun readPins(request: DiscordPinsRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireMessageChannel(request.channelId)
        return channel.retrievePinnedMessages().limit(request.limit).submit().thenApply { pins ->
            mapOf(
                "channel" to channelPayload(channel, aliasesFor(channel.id)),
                "count" to pins.size,
                "pins" to pins.map { pin ->
                    mapOf(
                        "pinnedAt" to pin.timePinned.toString(),
                        "message" to messagePayload(pin.message),
                    )
                },
            )
        }
    }

    override fun searchMessages(request: DiscordSearchRequest): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        var action = guild.searchMessages().content(request.query).limit(request.limit)
        request.channelId?.let { action = action.channels(it) }
        request.authorId?.let { action = action.authors(it) }
        return action.submit().thenApply { response ->
            if (response.isNotReady) {
                mapOf(
                    "guildId" to guild.id,
                    "ready" to false,
                    "retryAfterSeconds" to response.asNotReady().retryAfter.seconds,
                )
            } else {
                val messages = response.asResults().messages
                mapOf(
                    "guildId" to guild.id,
                    "ready" to true,
                    "count" to messages.size,
                    "messages" to messages.map(::messagePayload),
                )
            }
        }
    }

    override fun mutateMessage(request: DiscordMessageMutationRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireMessageChannel(request.channelId)
        return when (request.operation) {
            DiscordMessageMutation.SEND -> sendMessage(channel, request)
            DiscordMessageMutation.EDIT -> editMessage(channel, request)
            DiscordMessageMutation.DELETE -> withMessage(channel, requireMessageId(request)) { message ->
                reason(message.delete(), request.reason).submit()
            }
            DiscordMessageMutation.REACTION_ADD -> withMessage(channel, requireMessageId(request)) { message ->
                message.addReaction(Emoji.fromFormatted(requireEmoji(request))).submit()
            }
            DiscordMessageMutation.REACTION_REMOVE -> withMessage(channel, requireMessageId(request)) { message ->
                message.removeReaction(Emoji.fromFormatted(requireEmoji(request))).submit()
            }
            DiscordMessageMutation.REACTIONS_CLEAR -> withMessage(channel, requireMessageId(request)) { message ->
                message.clearReactions().submit()
            }
            DiscordMessageMutation.PIN -> withMessage(channel, requireMessageId(request)) { message ->
                reason(message.pin(), request.reason).submit()
            }
            DiscordMessageMutation.UNPIN -> withMessage(channel, requireMessageId(request)) { message ->
                reason(message.unpin(), request.reason).submit()
            }
        }.thenApply { result ->
            if (result is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result as Map<String, Any?>
            } else {
                mutationResult(request.operation.name.lowercase(), request.channelId, request.messageId)
            }
        }
    }

    override fun mutateThread(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            DiscordThreadMutation.CREATE -> createThread(request)
            DiscordThreadMutation.FORUM_POST -> createForumPost(request)
            DiscordThreadMutation.UPDATE -> updateThread(request)
        }

    override fun mutateChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            DiscordChannelMutation.CREATE -> createChannel(request)
            DiscordChannelMutation.UPDATE -> updateChannel(request)
            DiscordChannelMutation.DELETE -> deleteChannel(request)
        }

    override fun mutateRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>> =
        when (request.operation) {
            DiscordRoleMutation.CREATE -> createRole(request)
            DiscordRoleMutation.UPDATE -> updateRole(request)
            DiscordRoleMutation.DELETE -> deleteRole(request)
            DiscordRoleMutation.ASSIGN -> assignRole(request, add = true)
            DiscordRoleMutation.REMOVE -> assignRole(request, add = false)
        }

    override fun mutateMember(request: DiscordMemberMutationRequest): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        val user = UserSnowflake.fromId(request.userId)
        val action: CompletableFuture<*> =
            when (request.operation) {
                DiscordMemberMutation.NICKNAME ->
                    guild.retrieveMemberById(request.userId).submit().thenCompose { member ->
                        reason(member.modifyNickname(request.nickname), request.reason).submit()
                    }
                DiscordMemberMutation.TIMEOUT ->
                    reason(
                        guild.timeoutFor(user, request.durationSeconds ?: error("durationSeconds required"), TimeUnit.SECONDS),
                        request.reason,
                    ).submit()
                DiscordMemberMutation.TIMEOUT_REMOVE -> reason(guild.removeTimeout(user), request.reason).submit()
                DiscordMemberMutation.MUTE ->
                    reason(guild.mute(user, request.enabled ?: error("enabled required")), request.reason).submit()
                DiscordMemberMutation.DEAFEN ->
                    reason(guild.deafen(user, request.enabled ?: error("enabled required")), request.reason).submit()
                DiscordMemberMutation.KICK -> reason(guild.kick(user), request.reason).submit()
                DiscordMemberMutation.BAN ->
                    reason(
                        guild.ban(user, request.deleteMessageSeconds, TimeUnit.SECONDS),
                        request.reason,
                    ).submit()
                DiscordMemberMutation.UNBAN -> reason(guild.unban(user), request.reason).submit()
            }
        return action.thenApply {
            mapOf(
                "operation" to request.operation.name.lowercase(),
                "guildId" to request.guildId,
                "userId" to request.userId,
            )
        }
    }

    private fun sendMessage(
        channel: GuildMessageChannel,
        request: DiscordMessageMutationRequest,
    ): CompletableFuture<Map<String, Any?>> {
        val (data, uploads) = createMessageData(request.content, request.embeds.orEmpty(), request.attachments)
        var action =
            channel.sendMessage(data)
                .setAllowedMentions(emptySet())
                .mentionRepliedUser(false)
        request.replyToMessageId?.let { action = action.setMessageReference(it) }
        return action.submit()
            .whenComplete { _, _ -> uploads.forEach(FileUpload::close) }
            .thenApply(::messageMutationPayload)
    }

    private fun editMessage(
        channel: GuildMessageChannel,
        request: DiscordMessageMutationRequest,
    ): CompletableFuture<Map<String, Any?>> {
        val uploads = request.attachments.map(::fileUpload)
        return channel.retrieveMessageById(requireMessageId(request)).submit().thenCompose { message ->
            val builder = MessageEditBuilder.fromMessage(message)
            request.content?.let(builder::setContent)
            request.embeds?.let { embeds -> builder.setEmbeds(embeds.map(::buildEmbed)) }
            if (uploads.isNotEmpty()) builder.setFiles(uploads)
            message.editMessage(builder.build())
                .setAllowedMentions(emptySet())
                .submit()
        }.whenComplete { _, _ -> uploads.forEach(FileUpload::close) }
            .thenApply(::messageMutationPayload)
    }

    private fun <T> withMessage(
        channel: GuildMessageChannel,
        messageId: String,
        mutation: (Message) -> CompletableFuture<T>,
    ): CompletableFuture<Any?> =
        channel.retrieveMessageById(messageId).submit().thenCompose(mutation).thenApply { it }

    private fun createThread(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireGuildChannel(request.channelId)
        val container = channel as? IThreadContainer ?: error("channel does not support threads: ${request.channelId}")
        val name = request.name?.trim().orEmpty().ifBlank { error("thread name required") }
        val action =
            request.starterMessageId?.let { container.createThreadChannel(name, it) }
                ?: container.createThreadChannel(name)
        request.reason?.takeIf(String::isNotBlank)?.let(action::reason)
        return action.submit().thenApply { thread ->
            mapOf("operation" to "create", "thread" to channelPayload(thread, aliasesFor(thread.id)))
        }
    }

    private fun createForumPost(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>> {
        val container = requireGuildChannel(request.channelId) as? IPostContainer
            ?: error("channel does not support forum/media posts: ${request.channelId}")
        val name = request.name?.trim().orEmpty().ifBlank { error("post name required") }
        val (data, uploads) = createMessageData(request.content, request.embeds, request.attachments)
        val action = container.createForumPost(name, data)
        return action.submit()
            .whenComplete { _, _ -> uploads.forEach(FileUpload::close) }
            .thenApply { post ->
                mapOf(
                    "operation" to "forum_post",
                    "thread" to channelPayload(post.threadChannel, aliasesFor(post.threadChannel.id)),
                    "message" to messagePayload(post.message),
                )
            }
    }

    private fun updateThread(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>> {
        val id = request.threadId ?: request.channelId
        val thread = jda().getThreadChannelById(id) ?: error("Discord thread not found: $id")
        val manager = thread.manager
        request.name?.let(manager::setName)
        request.archived?.let(manager::setArchived)
        request.locked?.let(manager::setLocked)
        request.pinned?.let(manager::setPinned)
        request.reason?.takeIf(String::isNotBlank)?.let(manager::reason)
        return manager.submit().thenApply {
            mapOf("operation" to "update", "thread" to channelPayload(thread, aliasesFor(thread.id)))
        }
    }

    private fun createChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        val name = request.name?.trim().orEmpty().ifBlank { error("channel name required") }
        val type = request.type?.trim()?.lowercase().orEmpty().ifBlank { "text" }
        val action =
            when (type) {
                "text" -> guild.createTextChannel(name)
                "news", "announcement" -> guild.createNewsChannel(name)
                "voice" -> guild.createVoiceChannel(name)
                "stage" -> guild.createStageChannel(name)
                "category" -> guild.createCategory(name)
                "forum" -> guild.createForumChannel(name)
                "media" -> guild.createMediaChannel(name)
                else -> error("unsupported channel type: $type")
            }
        request.parentCategoryId?.let { parentId ->
            val parent = guild.getCategoryById(parentId) ?: error("Category not found: $parentId")
            action.setParent(parent)
        }
        request.position?.let(action::setPosition)
        if (type in MESSAGE_CONTAINER_TYPES) {
            request.topic?.let(action::setTopic)
            request.nsfw?.let(action::setNSFW)
            request.slowmodeSeconds?.let(action::setSlowmode)
        }
        if (type in setOf("voice", "stage")) request.bitrate?.let(action::setBitrate)
        if (type == "voice") request.userLimit?.let(action::setUserlimit)
        applyPermissionOverrides(action, request.permissionOverrides)
        request.reason?.takeIf(String::isNotBlank)?.let(action::reason)
        return action.submit().thenApply { channel ->
            mapOf("operation" to "create", "channel" to channelPayload(channel, aliasesFor(channel.id)))
        }
    }

    private fun updateChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>> {
        val channelId = request.channelId ?: error("channelId required")
        val channel = requireGuildChannel(channelId)
        require(channel.guild.id == request.guildId) { "channel does not belong to guild" }
        val managerFuture: CompletableFuture<Void> =
            when (channel) {
                is TextChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.topic?.let(::setTopic)
                    request.nsfw?.let(::setNSFW)
                    request.slowmodeSeconds?.let(::setSlowmode)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is NewsChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.topic?.let(::setTopic)
                    request.nsfw?.let(::setNSFW)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is ForumChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.topic?.let(::setTopic)
                    request.nsfw?.let(::setNSFW)
                    request.slowmodeSeconds?.let(::setSlowmode)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is MediaChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.topic?.let(::setTopic)
                    request.nsfw?.let(::setNSFW)
                    request.slowmodeSeconds?.let(::setSlowmode)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is VoiceChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.bitrate?.let(::setBitrate)
                    request.userLimit?.let(::setUserLimit)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is StageChannel -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.parentCategoryId?.let { setParent(requireCategory(channel.guild.id, it)) }
                    request.bitrate?.let(::setBitrate)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                is Category -> channel.manager.apply {
                    request.name?.let(::setName)
                    request.position?.let(::setPosition)
                    request.reason?.takeIf(String::isNotBlank)?.let(::reason)
                }.submit()
                else -> error("unsupported channel type for update: ${channel.type}")
            }
        return managerFuture.thenCompose {
            applyPermissionOverrides(channel, request.permissionOverrides, request.removePermissionOverrideIds)
        }.thenApply {
            mapOf("operation" to "update", "channel" to channelPayload(channel, aliasesFor(channel.id)))
        }
    }

    private fun deleteChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>> {
        val channelId = request.channelId ?: error("channelId required")
        val channel = requireGuildChannel(channelId)
        require(channel.guild.id == request.guildId) { "channel does not belong to guild" }
        return reason(channel.delete(), request.reason).submit().thenApply {
            mapOf("operation" to "delete", "guildId" to request.guildId, "channelId" to channelId)
        }
    }

    private fun createRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        val action = guild.createRole().setName(request.name?.trim().orEmpty().ifBlank { error("role name required") })
        request.color?.let { action.setColor(parseColor(it)) }
        request.permissions?.let { action.setPermissions(parsePermissions(it)) }
        request.hoisted?.let(action::setHoisted)
        request.mentionable?.let(action::setMentionable)
        request.reason?.takeIf(String::isNotBlank)?.let(action::reason)
        return action.submit().thenApply { role ->
            mapOf("operation" to "create", "role" to rolePayload(role))
        }
    }

    private fun updateRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>> {
        val role = requireRole(request.guildId, request.roleId)
        val manager = role.manager
        request.name?.let(manager::setName)
        request.color?.let { manager.setColor(parseColor(it)) }
        request.permissions?.let { manager.setPermissions(parsePermissions(it)) }
        request.hoisted?.let(manager::setHoisted)
        request.mentionable?.let(manager::setMentionable)
        request.reason?.takeIf(String::isNotBlank)?.let(manager::reason)
        return manager.submit().thenApply { mapOf("operation" to "update", "role" to rolePayload(role)) }
    }

    private fun deleteRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>> {
        val role = requireRole(request.guildId, request.roleId)
        return reason(role.delete(), request.reason).submit().thenApply {
            mapOf("operation" to "delete", "guildId" to request.guildId, "roleId" to role.id)
        }
    }

    private fun assignRole(
        request: DiscordRoleMutationRequest,
        add: Boolean,
    ): CompletableFuture<Map<String, Any?>> {
        val guild = requireGuild(request.guildId)
        val role = requireRole(request.guildId, request.roleId)
        val user = UserSnowflake.fromId(request.userId ?: error("userId required"))
        val action = if (add) guild.addRoleToMember(user, role) else guild.removeRoleFromMember(user, role)
        request.reason?.takeIf(String::isNotBlank)?.let(action::reason)
        return action.submit().thenApply {
            mapOf(
                "operation" to if (add) "assign" else "remove",
                "guildId" to guild.id,
                "roleId" to role.id,
                "userId" to user.id,
            )
        }
    }

    private fun createMessageData(
        content: String?,
        embeds: List<DiscordEmbedSpec>,
        attachments: List<DiscordAttachmentSpec>,
    ): Pair<net.dv8tion.jda.api.utils.messages.MessageCreateData, List<FileUpload>> {
        require(!content.isNullOrBlank() || embeds.isNotEmpty() || attachments.isNotEmpty()) {
            "message requires content, embeds, or attachments"
        }
        val uploads = attachments.map(::fileUpload)
        val builder = MessageCreateBuilder()
        content?.let(builder::setContent)
        if (embeds.isNotEmpty()) builder.setEmbeds(embeds.map(::buildEmbed))
        if (uploads.isNotEmpty()) builder.setFiles(uploads)
        return builder.build() to uploads
    }

    private fun buildEmbed(spec: DiscordEmbedSpec): net.dv8tion.jda.api.entities.MessageEmbed =
        EmbedBuilder().apply {
            if (spec.title != null) setTitle(spec.title, spec.url)
            spec.description?.let(::setDescription)
            spec.color?.let { setColor(parseColor(it)) }
            spec.timestamp?.let { setTimestamp(OffsetDateTime.parse(it)) }
            spec.authorName?.let { setAuthor(it, spec.authorUrl, spec.authorIconUrl) }
            spec.footerText?.let { setFooter(it, spec.footerIconUrl) }
            spec.thumbnailUrl?.let(::setThumbnail)
            spec.imageUrl?.let(::setImage)
            spec.fields.forEach { field -> addField(field.name, field.value, field.inline) }
        }.build()

    private fun fileUpload(spec: DiscordAttachmentSpec): FileUpload {
        val name = spec.fileName.trim()
        require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "invalid attachment filename" }
        val bytes = Base64.getDecoder().decode(spec.dataBase64)
        require(bytes.size <= MAX_ATTACHMENT_BYTES) { "attachment exceeds $MAX_ATTACHMENT_BYTES bytes" }
        return FileUpload.fromData(bytes, name).apply {
            spec.description?.let(::setDescription)
        }
    }

    private fun applyPermissionOverrides(
        action: net.dv8tion.jda.api.requests.restaction.ChannelAction<out GuildChannel>,
        overrides: List<DiscordPermissionOverrideSpec>,
    ) {
        overrides.forEach { override ->
            val allow = Permission.getRaw(parsePermissions(override.allow))
            val deny = Permission.getRaw(parsePermissions(override.deny))
            when (override.targetType.lowercase()) {
                "role" -> action.addRolePermissionOverride(override.targetId.toLong(), allow, deny)
                "member", "user" -> action.addMemberPermissionOverride(override.targetId.toLong(), allow, deny)
                else -> error("permission override targetType must be role or member")
            }
        }
    }

    private fun applyPermissionOverrides(
        channel: GuildChannel,
        overrides: List<DiscordPermissionOverrideSpec>,
        removeIds: Set<String>,
    ): CompletableFuture<Void> {
        val container = channel as? IPermissionContainer
        if (container == null) {
            require(overrides.isEmpty() && removeIds.isEmpty()) { "channel does not support permission overrides" }
            return CompletableFuture.completedFuture(null)
        }
        val futures = mutableListOf<CompletableFuture<*>>()
        overrides.forEach { override ->
            val allow = parsePermissions(override.allow)
            val deny = parsePermissions(override.deny)
            val holder =
                when (override.targetType.lowercase()) {
                    "role" -> channel.guild.getRoleById(override.targetId) ?: error("Role not found: ${override.targetId}")
                    "member", "user" -> channel.guild.retrieveMemberById(override.targetId).complete()
                    else -> error("permission override targetType must be role or member")
                }
            futures += container.upsertPermissionOverride(holder).setAllowed(allow).setDenied(deny).submit()
        }
        removeIds.forEach { targetId ->
            container.permissionOverrides.firstOrNull { it.permissionHolder?.id == targetId }?.let { override ->
                futures += override.delete().submit()
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    private fun parsePermissions(names: Collection<String>): Set<Permission> =
        names.map { name -> Permission.valueOf(name.trim().uppercase().replace('-', '_')) }.toSet()

    private fun parseColor(value: String): Color {
        val normalized = value.trim().removePrefix("#").removePrefix("0x")
        require(normalized.matches(Regex("[0-9a-fA-F]{6}"))) { "color must be #RRGGBB" }
        return Color(normalized.toInt(16))
    }

    private fun requireGuild(guildId: String) = jda().getGuildById(guildId) ?: error("Discord guild not found: $guildId")

    private fun requireGuildChannel(channelId: String): GuildChannel =
        jda().getGuildChannelById(channelId) ?: error("Discord guild channel not found: $channelId")

    private fun requireMessageChannel(channelId: String): GuildMessageChannel {
        val jda = jda()
        return jda.getTextChannelById(channelId)
            ?: jda.getNewsChannelById(channelId)
            ?: jda.getThreadChannelById(channelId)
            ?: error("Discord message channel not found: $channelId")
    }

    private fun requireCategory(
        guildId: String,
        categoryId: String,
    ): Category = requireGuild(guildId).getCategoryById(categoryId) ?: error("Category not found: $categoryId")

    private fun requireRole(
        guildId: String,
        roleId: String?,
    ): Role = requireGuild(guildId).getRoleById(roleId ?: error("roleId required")) ?: error("Role not found: $roleId")

    private fun requireMessageId(request: DiscordMessageMutationRequest): String =
        request.messageId ?: error("messageId required for ${request.operation.name.lowercase()}")

    private fun requireEmoji(request: DiscordMessageMutationRequest): String =
        request.emoji?.takeIf(String::isNotBlank) ?: error("emoji required")

    private fun jda(): JDA = jdaProvider() ?: error("discord not ready")

    private fun aliasesById(): Map<String, List<String>> =
        aliasesProvider().entries
            .filter { it.value.isNotBlank() }
            .groupBy({ it.value }, { it.key })

    private fun aliasesFor(channelId: String): List<String> =
        aliasesProvider().filterValues { it == channelId }.keys.sorted()

    private fun channelPayload(
        channel: GuildChannel,
        aliases: List<String>,
    ): Map<String, Any?> {
        val payload =
            linkedMapOf<String, Any?>(
                "id" to channel.id,
                "name" to channel.name,
                "type" to channel.type.name.lowercase(),
                "guildId" to channel.guild.id,
                "parentChannelId" to parentChannelId(channel),
                "position" to (channel as? IPositionableChannel)?.position,
                "aliases" to aliases.sorted(),
                "jumpUrl" to channel.jumpUrl,
                "readable" to (channel is GuildMessageChannel),
                "writable" to ((channel as? GuildMessageChannel)?.canTalk() == true),
            )
        if (channel is ThreadChannel) {
            payload["archived"] = channel.isArchived
            payload["locked"] = channel.isLocked
            payload["messageCount"] = channel.messageCount
        }
        if (channel is IPostContainer) payload["activeThreadCount"] = channel.threadChannels.size
        if (channel is IPermissionContainer) {
            payload["permissionOverrides"] =
                channel.permissionOverrides.map { override ->
                    mapOf(
                        "targetId" to (override.permissionHolder?.id ?: override.id),
                        "targetType" to if (override.isRoleOverride) "role" else "member",
                        "allowed" to override.allowed.map(Permission::name).sorted(),
                        "denied" to override.denied.map(Permission::name).sorted(),
                    )
                }
        }
        return payload
    }

    private fun rolePayload(role: Role): Map<String, Any?> {
        val colors = role.colors
        return mapOf(
            "id" to role.id,
            "name" to role.name,
            "color" to colors.primaryRaw,
            "secondaryColor" to colors.secondaryRaw,
            "tertiaryColor" to colors.tertiaryRaw,
            "colorStyle" to
                when {
                    colors.isHolographic -> "holographic"
                    colors.isGradient -> "gradient"
                    colors.isSolid -> "solid"
                    else -> "default"
                },
            "position" to role.position,
            "hoisted" to role.isHoisted,
            "mentionable" to role.isMentionable,
            "managed" to role.isManaged,
            "publicRole" to role.isPublicRole,
            "permissions" to role.permissions.map(Permission::name).sorted(),
        )
    }

    private fun memberPayload(member: Member): Map<String, Any?> =
        mapOf(
            "id" to member.id,
            "username" to member.user.name,
            "displayName" to member.effectiveName,
            "nickname" to member.nickname,
            "owner" to member.isOwner,
            "joinedAt" to member.timeJoined.toString(),
            "timeoutUntil" to member.timeOutEnd?.toString(),
            "roles" to member.roles.map(::rolePayload),
            "permissions" to member.permissions.map(Permission::name).sorted(),
        )

    private fun messagePayload(message: Message): Map<String, Any?> =
        linkedMapOf(
            "id" to message.id,
            "channelId" to message.channelId,
            "guildId" to message.guildId,
            "createdAt" to message.timeCreated.toString(),
            "editedAt" to message.timeEdited?.toString(),
            "jumpUrl" to message.jumpUrl,
            "author" to
                mapOf(
                    "id" to message.author.id,
                    "username" to message.author.name,
                    "displayName" to (message.member?.effectiveName ?: message.author.effectiveName),
                    "bot" to message.author.isBot,
                    "system" to message.author.isSystem,
                ),
            "contentRaw" to message.contentRaw,
            "contentDisplay" to message.contentDisplay,
            "pinned" to message.isPinned,
            "tts" to message.isTTS,
            "webhook" to message.isWebhookMessage,
            "replyToMessageId" to message.messageReference?.messageId,
            "attachments" to
                message.attachments.map { attachment ->
                    mapOf(
                        "id" to attachment.id,
                        "fileName" to attachment.fileName,
                        "description" to attachment.description,
                        "contentType" to attachment.contentType,
                        "size" to attachment.size,
                        "width" to attachment.width,
                        "height" to attachment.height,
                        "image" to attachment.isImage,
                        "video" to attachment.isVideo,
                        "ephemeral" to attachment.isEphemeral,
                        "url" to attachment.url,
                        "proxyUrl" to attachment.proxyUrl,
                    )
                },
            "embeds" to
                message.embeds.map { embed ->
                    mapOf(
                        "type" to embed.type.name.lowercase(),
                        "title" to embed.title,
                        "description" to embed.description,
                        "url" to embed.url,
                        "timestamp" to embed.timestamp?.toString(),
                        "color" to embed.colorRaw,
                        "author" to embed.author?.name,
                        "footer" to embed.footer?.text,
                        "thumbnailUrl" to embed.thumbnail?.url,
                        "imageUrl" to embed.image?.url,
                        "fields" to
                            embed.fields.map { field ->
                                mapOf("name" to field.name, "value" to field.value, "inline" to field.isInline)
                            },
                    )
                },
            "reactions" to
                message.reactions.map { reaction ->
                    mapOf("emoji" to reaction.emoji.formatted, "count" to reaction.count, "self" to reaction.isSelf)
                },
        )

    private fun messageMutationPayload(message: Message): Map<String, Any?> =
        mapOf(
            "id" to message.id,
            "channelId" to message.channelId,
            "createdAt" to message.timeCreated.toString(),
            "jumpUrl" to message.jumpUrl,
            "message" to messagePayload(message),
        )

    private fun mutationResult(
        operation: String,
        channelId: String,
        messageId: String?,
    ): Map<String, Any?> = mapOf("operation" to operation, "channelId" to channelId, "messageId" to messageId)

    private fun <T> reason(
        action: AuditableRestAction<T>,
        reason: String?,
    ): AuditableRestAction<T> {
        reason?.takeIf(String::isNotBlank)?.let(action::reason)
        return action
    }

    companion object {
        private const val ALL = "*"
        private const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
        private val MESSAGE_CONTAINER_TYPES = setOf("text", "news", "announcement", "forum", "media")

        internal fun isAllowed(
            channelId: String,
            parentChannelId: String?,
            allowedChannelIds: Set<String>,
        ): Boolean =
            ALL in allowedChannelIds ||
                channelId in allowedChannelIds ||
                (parentChannelId != null && parentChannelId in allowedChannelIds)

        private fun parentChannelId(channel: GuildChannel): String? =
            when (channel) {
                is ThreadChannel -> channel.parentChannel.id
                is ICategorizableChannel -> channel.parentCategory?.id
                else -> null
            }
    }
}
