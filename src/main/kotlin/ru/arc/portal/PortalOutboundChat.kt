package ru.arc.portal

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import ru.arc.ops.TelegramParseMode
import ru.arc.velocity.Velocity

internal class PortalOutboundChatFormatter(
    private val minecraftFormat: String,
    private val externalFormat: String,
) {
    init {
        validate("outbound.minecraft-format", minecraftFormat)
        validate("outbound.external-format", externalFormat)
        runCatching { minecraft("PlayerName", "message") }
            .getOrElse { error ->
                throw IllegalArgumentException("outbound.minecraft-format must be valid MiniMessage", error)
            }
    }

    fun minecraft(
        playerName: String,
        content: String,
    ): Component =
        MiniMessage.miniMessage().deserialize(
            minecraftFormat
                .replace(PLAYER_NAME_TOKEN, PLAYER_NAME_TAG)
                .replace(MESSAGE_TOKEN, MESSAGE_TAG),
            Placeholder.unparsed("player_name", playerName),
            Placeholder.unparsed("message", content),
        )

    fun external(
        playerName: String,
        content: String,
    ): String =
        FORMAT_TOKEN.replace(externalFormat) { match ->
            when (match.value) {
                PLAYER_NAME_TOKEN -> playerName
                else -> content
            }
        }

    private fun validate(
        path: String,
        format: String,
    ) {
        require(format.isNotBlank()) { "$path must not be blank" }
        require(format.length <= MAX_FORMAT_LENGTH) { "$path must not exceed $MAX_FORMAT_LENGTH characters" }
        require('\n' !in format && '\r' !in format) { "$path must be a single line" }
        require(format.countToken(PLAYER_NAME_TOKEN) == 1) { "$path must contain %player_name% exactly once" }
        require(format.countToken(MESSAGE_TOKEN) == 1) { "$path must contain %message% exactly once" }
    }

    companion object {
        private const val PLAYER_NAME_TOKEN = "%player_name%"
        private const val MESSAGE_TOKEN = "%message%"
        private const val PLAYER_NAME_TAG = "<player_name>"
        private const val MESSAGE_TAG = "<message>"
        private const val MAX_FORMAT_LENGTH = 512
        private val FORMAT_TOKEN = Regex("%(?:player_name|message)%")

        private fun String.countToken(token: String): Int = windowed(token.length).count { it == token }
    }
}

internal fun interface PortalMinecraftChatSink {
    fun send(message: Component): Boolean
}

internal interface PortalCommunityChatSink {
    fun sendGame(message: String): Boolean

    fun sendCommunity(message: String): Boolean
}

internal object VelocityPortalMinecraftChatSink : PortalMinecraftChatSink {
    override fun send(message: Component): Boolean {
        val plugin = Velocity.plugin ?: return false
        plugin.sendMessageToAll(message)
        return true
    }
}

internal object VelocityPortalDiscordChatSink : PortalCommunityChatSink {
    override fun sendGame(message: String): Boolean {
        val bot = Velocity.discordBot ?: return false
        if (!bot.isReady()) return false
        bot.sendChatMessage(message)
        return true
    }

    override fun sendCommunity(message: String): Boolean {
        val bot = Velocity.discordBot ?: return false
        if (!bot.isReady()) return false
        bot.sendGeneralMessage(message)
        return true
    }
}

internal object VelocityPortalTelegramChatSink : PortalCommunityChatSink {
    override fun sendGame(message: String): Boolean {
        val bot = Velocity.telegramBot ?: return false
        if (!bot.isReady()) return false
        bot.sendChatMessage(message, TelegramParseMode.NONE)
        return true
    }

    override fun sendCommunity(message: String): Boolean {
        val bot = Velocity.telegramBot ?: return false
        if (!bot.isReady()) return false
        bot.sendGeneralMessage(message, TelegramParseMode.NONE)
        return true
    }
}

/**
 * Routes one authenticated portal message to the existing network chat sinks.
 * Returning false keeps the durable portal outbox row pending for a later poll.
 */
internal class PortalOutboundChatDelivery(
    private val formatter: PortalOutboundChatFormatter,
    private val minecraft: PortalMinecraftChatSink = VelocityPortalMinecraftChatSink,
    private val discord: PortalCommunityChatSink = VelocityPortalDiscordChatSink,
    private val telegram: PortalCommunityChatSink = VelocityPortalTelegramChatSink,
) {
    fun deliver(message: PortalOutboundChatMessage): Boolean {
        val external = formatter.external(message.authorName, message.content)
        return when (message.channel) {
            PortalChatChannel.GAME -> {
                val minecraftAccepted = minecraft.send(formatter.minecraft(message.authorName, message.content))
                if (!minecraftAccepted) return false
                val discordAccepted = discord.sendGame(external)
                val telegramAccepted = telegram.sendGame(external)
                discordAccepted && telegramAccepted
            }
            PortalChatChannel.COMMUNITY -> {
                val discordAccepted = discord.sendCommunity(external)
                val telegramAccepted = telegram.sendCommunity(external)
                discordAccepted && telegramAccepted
            }
        }
    }
}
