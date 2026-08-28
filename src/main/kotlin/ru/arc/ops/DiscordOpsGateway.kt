package ru.arc.ops

import java.util.concurrent.CompletableFuture

interface DiscordOpsGateway {
    fun isReady(): Boolean

    fun isGuildAllowed(
        guildId: String,
        allowedGuildIds: Set<String>,
    ): Boolean

    fun isChannelAllowed(
        channelId: String,
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Boolean

    fun listGuilds(allowedGuildIds: Set<String>): Map<String, Any?>

    fun listChannels(
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Map<String, Any?>

    fun listRoles(guildId: String): Map<String, Any?>

    fun listInvites(guildId: String): CompletableFuture<Map<String, Any?>>

    fun readMember(request: DiscordMemberReadRequest): CompletableFuture<Map<String, Any?>>

    fun readHistory(request: DiscordHistoryRequest): CompletableFuture<Map<String, Any?>>

    fun readMessage(request: DiscordMessageRequest): CompletableFuture<Map<String, Any?>>

    fun readPins(request: DiscordPinsRequest): CompletableFuture<Map<String, Any?>>

    fun searchMessages(request: DiscordSearchRequest): CompletableFuture<Map<String, Any?>>

    fun mutateMessage(request: DiscordMessageMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateThread(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateMember(request: DiscordMemberMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateGuild(request: DiscordGuildMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateInvite(request: DiscordInviteMutationRequest): CompletableFuture<Map<String, Any?>>
}

data class DiscordHistoryRequest(
    val channelId: String,
    val limit: Int,
    val beforeMessageId: String? = null,
)

data class DiscordMessageRequest(
    val channelId: String,
    val messageId: String,
)

data class DiscordPinsRequest(
    val channelId: String,
    val limit: Int,
)

data class DiscordSearchRequest(
    val guildId: String,
    val query: String,
    val limit: Int,
    val channelId: String? = null,
    val authorId: String? = null,
)

data class DiscordMemberReadRequest(
    val guildId: String,
    val userId: String,
)

enum class DiscordMessageMutation {
    SEND,
    EDIT,
    DELETE,
    REACTION_ADD,
    REACTION_REMOVE,
    REACTIONS_CLEAR,
    PIN,
    UNPIN,
}

data class DiscordMessageMutationRequest(
    val operation: DiscordMessageMutation,
    val channelId: String,
    val messageId: String? = null,
    val content: String? = null,
    val replyToMessageId: String? = null,
    val embeds: List<DiscordEmbedSpec>? = null,
    val attachments: List<DiscordAttachmentSpec> = emptyList(),
    val allowedUserMentionIds: Set<String> = emptySet(),
    val emoji: String? = null,
    val reason: String? = null,
)

data class DiscordEmbedSpec(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val color: String? = null,
    val timestamp: String? = null,
    val authorName: String? = null,
    val authorUrl: String? = null,
    val authorIconUrl: String? = null,
    val footerText: String? = null,
    val footerIconUrl: String? = null,
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
    val fields: List<DiscordEmbedFieldSpec> = emptyList(),
)

data class DiscordEmbedFieldSpec(
    val name: String,
    val value: String,
    val inline: Boolean = false,
)

data class DiscordAttachmentSpec(
    val fileName: String,
    val dataBase64: String,
    val description: String? = null,
)

enum class DiscordGuildMutation {
    UPDATE,
}

data class DiscordGuildMutationRequest(
    val operation: DiscordGuildMutation,
    val guildId: String,
    val name: String? = null,
    val description: String? = null,
    val iconDataBase64: String? = null,
    val removeIcon: Boolean? = null,
    val bannerDataBase64: String? = null,
    val removeBanner: Boolean? = null,
    val verificationLevel: String? = null,
    val defaultNotificationLevel: String? = null,
    val explicitContentLevel: String? = null,
    val boostProgressBarEnabled: Boolean? = null,
    val invitesDisabled: Boolean? = null,
    val reason: String? = null,
)

enum class DiscordInviteMutation {
    CREATE,
    DELETE,
}

data class DiscordInviteMutationRequest(
    val operation: DiscordInviteMutation,
    val guildId: String,
    val channelId: String? = null,
    val code: String? = null,
    val maxAgeSeconds: Int? = null,
    val maxUses: Int? = null,
    val temporary: Boolean? = null,
    val unique: Boolean? = null,
    val reason: String? = null,
)

enum class DiscordThreadMutation {
    CREATE,
    FORUM_POST,
    UPDATE,
}

data class DiscordThreadMutationRequest(
    val operation: DiscordThreadMutation,
    val channelId: String,
    val threadId: String? = null,
    val name: String? = null,
    val starterMessageId: String? = null,
    val content: String? = null,
    val embeds: List<DiscordEmbedSpec> = emptyList(),
    val attachments: List<DiscordAttachmentSpec> = emptyList(),
    val archived: Boolean? = null,
    val locked: Boolean? = null,
    val pinned: Boolean? = null,
    val reason: String? = null,
)

data class DiscordPermissionOverrideSpec(
    val targetType: String,
    val targetId: String,
    val allow: Set<String> = emptySet(),
    val deny: Set<String> = emptySet(),
)

enum class DiscordChannelMutation {
    CREATE,
    UPDATE,
    DELETE,
}

data class DiscordChannelMutationRequest(
    val operation: DiscordChannelMutation,
    val guildId: String,
    val channelId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val parentCategoryId: String? = null,
    val topic: String? = null,
    val nsfw: Boolean? = null,
    val slowmodeSeconds: Int? = null,
    val bitrate: Int? = null,
    val userLimit: Int? = null,
    val position: Int? = null,
    val permissionOverrides: List<DiscordPermissionOverrideSpec> = emptyList(),
    val removePermissionOverrideIds: Set<String> = emptySet(),
    val reason: String? = null,
)

enum class DiscordRoleMutation {
    CREATE,
    UPDATE,
    DELETE,
    ASSIGN,
    REMOVE,
}

data class DiscordRoleMutationRequest(
    val operation: DiscordRoleMutation,
    val guildId: String,
    val roleId: String? = null,
    val userId: String? = null,
    val name: String? = null,
    val color: String? = null,
    val permissions: Set<String>? = null,
    val hoisted: Boolean? = null,
    val mentionable: Boolean? = null,
    val reason: String? = null,
)

enum class DiscordMemberMutation {
    NICKNAME,
    TIMEOUT,
    TIMEOUT_REMOVE,
    MUTE,
    DEAFEN,
    KICK,
    BAN,
    UNBAN,
}

data class DiscordMemberMutationRequest(
    val operation: DiscordMemberMutation,
    val guildId: String,
    val userId: String,
    val nickname: String? = null,
    val durationSeconds: Long? = null,
    val enabled: Boolean? = null,
    val deleteMessageSeconds: Int = 0,
    val reason: String? = null,
)
