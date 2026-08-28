package ru.arc.ops

import java.util.concurrent.CompletableFuture

interface TelegramOpsGateway {
    fun isReady(): Boolean

    fun listChats(chatIds: Set<String>): CompletableFuture<Map<String, Any?>>

    fun readChat(chatId: String): CompletableFuture<Map<String, Any?>>

    fun mutateMessage(request: TelegramMessageMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateChat(request: TelegramChatMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateTopic(request: TelegramTopicMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateInvite(request: TelegramInviteMutationRequest): CompletableFuture<Map<String, Any?>>
}

enum class TelegramMessageMutation {
    SEND,
    EDIT,
    DELETE,
    PIN,
    UNPIN,
}

data class TelegramMessageMutationRequest(
    val operation: TelegramMessageMutation,
    val chatId: String,
    val messageId: Int? = null,
    val threadId: Int? = null,
    val text: String? = null,
    val replyToMessageId: Int? = null,
    val disableNotification: Boolean? = null,
    val parseMode: TelegramParseMode = TelegramParseMode.NONE,
    val disableWebPagePreview: Boolean? = null,
    val protectContent: Boolean? = null,
    val buttons: List<List<TelegramButtonSpec>> = emptyList(),
    val attachment: TelegramAttachmentSpec? = null,
)

enum class TelegramParseMode(val apiValue: String?) {
    NONE(null),
    HTML("HTML"),
    MARKDOWN("Markdown"),
    MARKDOWN_V2("MarkdownV2"),
}

data class TelegramButtonSpec(
    val text: String,
    val url: String,
)

enum class TelegramAttachmentType {
    PHOTO,
    DOCUMENT,
}

data class TelegramAttachmentSpec(
    val type: TelegramAttachmentType,
    val media: String? = null,
    val fileName: String? = null,
    val dataBase64: String? = null,
    val hasSpoiler: Boolean? = null,
)

enum class TelegramChatMutation {
    UPDATE,
    SET_PERMISSIONS,
}

data class TelegramChatMutationRequest(
    val operation: TelegramChatMutation,
    val chatId: String,
    val title: String? = null,
    val description: String? = null,
    val permissions: TelegramChatPermissionsSpec? = null,
    val useIndependentPermissions: Boolean? = null,
)

data class TelegramChatPermissionsSpec(
    val canSendMessages: Boolean? = null,
    val canSendAudios: Boolean? = null,
    val canSendDocuments: Boolean? = null,
    val canSendPhotos: Boolean? = null,
    val canSendVideos: Boolean? = null,
    val canSendVideoNotes: Boolean? = null,
    val canSendVoiceNotes: Boolean? = null,
    val canSendPolls: Boolean? = null,
    val canSendOtherMessages: Boolean? = null,
    val canAddWebPagePreviews: Boolean? = null,
    val canChangeInfo: Boolean? = null,
    val canInviteUsers: Boolean? = null,
    val canPinMessages: Boolean? = null,
    val canManageTopics: Boolean? = null,
)

enum class TelegramTopicMutation {
    CREATE,
    UPDATE,
    CLOSE,
    REOPEN,
    DELETE,
}

data class TelegramTopicMutationRequest(
    val operation: TelegramTopicMutation,
    val chatId: String,
    val threadId: Int? = null,
    val name: String? = null,
    val iconColor: Int? = null,
    val iconCustomEmojiId: String? = null,
)

enum class TelegramInviteMutation {
    CREATE,
    REVOKE,
}

data class TelegramInviteMutationRequest(
    val operation: TelegramInviteMutation,
    val chatId: String,
    val inviteLink: String? = null,
    val name: String? = null,
    val expireDate: Int? = null,
    val memberLimit: Int? = null,
    val createsJoinRequest: Boolean? = null,
)
