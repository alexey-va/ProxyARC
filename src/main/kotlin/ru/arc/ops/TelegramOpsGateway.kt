package ru.arc.ops

import java.util.concurrent.CompletableFuture

interface TelegramOpsGateway {
    fun isReady(): Boolean

    fun listChats(chatIds: Set<String>): CompletableFuture<Map<String, Any?>>

    fun readChat(chatId: String): CompletableFuture<Map<String, Any?>>

    fun listAdministrators(chatId: String): CompletableFuture<Map<String, Any?>>

    fun readMember(
        chatId: String,
        userId: Long,
    ): CompletableFuture<Map<String, Any?>>

    fun mutateMessage(request: TelegramMessageMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateChat(request: TelegramChatMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateTopic(request: TelegramTopicMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateInvite(request: TelegramInviteMutationRequest): CompletableFuture<Map<String, Any?>>

    fun mutateMember(request: TelegramMemberMutationRequest): CompletableFuture<Map<String, Any?>>
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
    SET_PHOTO,
    DELETE_PHOTO,
    UNPIN_ALL,
    SET_STICKER_SET,
    DELETE_STICKER_SET,
}

data class TelegramChatMutationRequest(
    val operation: TelegramChatMutation,
    val chatId: String,
    val title: String? = null,
    val description: String? = null,
    val permissions: TelegramChatPermissionsSpec? = null,
    val useIndependentPermissions: Boolean? = null,
    val photo: TelegramAttachmentSpec? = null,
    val stickerSetName: String? = null,
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
    UNPIN_ALL,
    GENERAL_UPDATE,
    GENERAL_CLOSE,
    GENERAL_REOPEN,
    GENERAL_HIDE,
    GENERAL_UNHIDE,
    GENERAL_UNPIN_ALL,
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
    EDIT,
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

enum class TelegramMemberMutation {
    BAN,
    UNBAN,
    RESTRICT,
    PROMOTE,
    SET_ADMIN_TITLE,
    APPROVE_JOIN_REQUEST,
    DECLINE_JOIN_REQUEST,
}

data class TelegramAdministratorRightsSpec(
    val canManageChat: Boolean? = null,
    val canChangeInfo: Boolean? = null,
    val canPostMessages: Boolean? = null,
    val canEditMessages: Boolean? = null,
    val canDeleteMessages: Boolean? = null,
    val canInviteUsers: Boolean? = null,
    val canRestrictMembers: Boolean? = null,
    val canPinMessages: Boolean? = null,
    val canPromoteMembers: Boolean? = null,
    val canManageVideoChats: Boolean? = null,
    val canManageTopics: Boolean? = null,
    val canPostStories: Boolean? = null,
    val canEditStories: Boolean? = null,
    val canDeleteStories: Boolean? = null,
    val isAnonymous: Boolean? = null,
)

data class TelegramMemberMutationRequest(
    val operation: TelegramMemberMutation,
    val chatId: String,
    val userId: Long,
    val untilDate: Int? = null,
    val revokeMessages: Boolean? = null,
    val onlyIfBanned: Boolean? = null,
    val permissions: TelegramChatPermissionsSpec? = null,
    val useIndependentPermissions: Boolean? = null,
    val administratorRights: TelegramAdministratorRightsSpec? = null,
    val customTitle: String? = null,
)
