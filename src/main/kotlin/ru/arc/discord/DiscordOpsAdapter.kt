package ru.arc.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import ru.arc.ops.DiscordHistoryRequest
import ru.arc.ops.DiscordMessageRequest
import ru.arc.ops.DiscordOpsGateway
import ru.arc.ops.DiscordSendRequest
import java.util.concurrent.CompletableFuture

internal class DiscordOpsAdapter(
    private val jdaProvider: () -> JDA?,
    private val aliasesProvider: () -> Map<String, String>,
) : DiscordOpsGateway {
    override fun isReady(): Boolean = jdaProvider() != null

    override fun isChannelAllowed(
        channelId: String,
        allowedChannelIds: Set<String>,
    ): Boolean {
        val channel = jdaProvider()?.getGuildChannelById(channelId) ?: return false
        return isAllowed(channel.id, parentChannelId(channel), allowedChannelIds)
    }

    override fun listChannels(allowedChannelIds: Set<String>): Map<String, Any?> {
        val jda = jdaProvider()
            ?: return mapOf("ready" to false, "channels" to emptyList<Any>())
        val aliasesById =
            aliasesProvider().entries
                .filter { it.value.isNotBlank() }
                .groupBy({ it.value }, { it.key })
        val roots =
            allowedChannelIds.mapNotNull(jda::getGuildChannelById)
        val children =
            jda.threadChannels.filter { it.parentChannel.id in allowedChannelIds }
        val channels =
            (roots + children)
                .distinctBy(GuildChannel::getId)
                .sortedWith(compareBy<GuildChannel>({ parentChannelId(it) ?: it.id }, { it.name }))
                .map { channel -> channelPayload(channel, aliasesById[channel.id].orEmpty()) }
        return mapOf(
            "ready" to true,
            "count" to channels.size,
            "channels" to channels,
        )
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
        return channel.retrieveMessageById(request.messageId).submit()
            .thenApply { message ->
                mapOf(
                    "channel" to channelPayload(channel, aliasesFor(channel.id)),
                    "message" to messagePayload(message),
                )
            }
    }

    override fun sendMessage(request: DiscordSendRequest): CompletableFuture<Map<String, Any?>> {
        val channel = requireMessageChannel(request.channelId)
        var action =
            channel.sendMessage(request.content)
                .setAllowedMentions(emptySet())
                .mentionRepliedUser(false)
        if (request.replyToMessageId != null) {
            action = action.setMessageReference(request.replyToMessageId)
        }
        return action.submit().thenApply { message ->
            mapOf(
                "id" to message.id,
                "channelId" to message.channelId,
                "createdAt" to message.timeCreated.toString(),
                "jumpUrl" to message.jumpUrl,
                "replyToMessageId" to request.replyToMessageId,
            )
        }
    }

    private fun requireMessageChannel(channelId: String): GuildMessageChannel {
        val jda = jdaProvider() ?: error("discord not ready")
        return jda.getTextChannelById(channelId)
            ?: jda.getNewsChannelById(channelId)
            ?: jda.getThreadChannelById(channelId)
            ?: error("Discord message channel not found: $channelId")
    }

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
        if (channel is ForumChannel) {
            payload["activeThreadCount"] = channel.threadChannels.size
        }
        return payload
    }

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
                        "fields" to
                            embed.fields.map { field ->
                                mapOf(
                                    "name" to field.name,
                                    "value" to field.value,
                                    "inline" to field.isInline,
                                )
                            },
                    )
                },
            "reactions" to
                message.reactions.map { reaction ->
                    mapOf(
                        "emoji" to reaction.emoji.formatted,
                        "count" to reaction.count,
                        "self" to reaction.isSelf,
                    )
                },
        )

    companion object {
        internal fun isAllowed(
            channelId: String,
            parentChannelId: String?,
            allowedChannelIds: Set<String>,
        ): Boolean =
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
