package ru.arc.portal

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID

class PortalOutboundChatTest : FreeSpec({
    "game delivery treats portal content as literal text and fans out after Minecraft accepts it" {
        val minecraft = mutableListOf<Component>()
        val discord = RecordingCommunitySink()
        val telegram = RecordingCommunitySink()
        val delivery =
            PortalOutboundChatDelivery(
                formatter =
                    PortalOutboundChatFormatter(
                        "<dark_aqua>Сайт</dark_aqua> <dark_gray>| <gray>%player_name% <dark_gray>» <white>%message%",
                        "%player_name% » %message%",
                    ),
                minecraft = PortalMinecraftChatSink { message -> minecraft += message; true },
                discord = discord,
                telegram = telegram,
            )

        delivery.deliver(message(content = "<red>@everyone</red>")) shouldBe true

        PlainTextComponentSerializer.plainText().serialize(minecraft.single()) shouldBe
            "Сайт | Explorer » <red>@everyone</red>"
        discord.game.single() shouldBe "Explorer » <red>@everyone</red>"
        telegram.game.single() shouldBe "Explorer » <red>@everyone</red>"
    }

    "game delivery remains pending when the Minecraft sink is unavailable" {
        val discord = RecordingCommunitySink()
        val telegram = RecordingCommunitySink()
        val delivery =
            PortalOutboundChatDelivery(
                formatter = PortalOutboundChatFormatter("%player_name% » %message%", "%player_name% » %message%"),
                minecraft = PortalMinecraftChatSink { false },
                discord = discord,
                telegram = telegram,
            )

        delivery.deliver(message()) shouldBe false
        discord.game shouldBe emptyList()
        telegram.game shouldBe emptyList()
    }

    "community delivery targets Discord and Telegram without entering Minecraft" {
        var minecraftCalls = 0
        val discord = RecordingCommunitySink()
        val telegram = RecordingCommunitySink()
        val delivery =
            PortalOutboundChatDelivery(
                formatter = PortalOutboundChatFormatter("%player_name% » %message%", "%player_name% » %message%"),
                minecraft = PortalMinecraftChatSink { minecraftCalls += 1; true },
                discord = discord,
                telegram = telegram,
            )

        delivery.deliver(message(channel = PortalChatChannel.COMMUNITY)) shouldBe true
        minecraftCalls shouldBe 0
        discord.community.single() shouldBe "Explorer » сообщение"
        telegram.community.single() shouldBe "Explorer » сообщение"
    }

    "delivery remains pending until both external bridges accept it" {
        val discord = RecordingCommunitySink(accept = false)
        val telegram = RecordingCommunitySink()
        val delivery =
            PortalOutboundChatDelivery(
                formatter = PortalOutboundChatFormatter("%player_name% » %message%", "%player_name% » %message%"),
                minecraft = PortalMinecraftChatSink { true },
                discord = discord,
                telegram = telegram,
            )

        delivery.deliver(message()) shouldBe false
        delivery.deliver(message(channel = PortalChatChannel.COMMUNITY)) shouldBe false
        discord.game.single() shouldBe "Explorer » сообщение"
        telegram.game.single() shouldBe "Explorer » сообщение"
        discord.community.single() shouldBe "Explorer » сообщение"
        telegram.community.single() shouldBe "Explorer » сообщение"
    }
})

private class RecordingCommunitySink(
    private val accept: Boolean = true,
) : PortalCommunityChatSink {
    val game = mutableListOf<String>()
    val community = mutableListOf<String>()

    override fun sendGame(message: String): Boolean {
        game += message
        return accept
    }

    override fun sendCommunity(message: String): Boolean {
        community += message
        return accept
    }
}

private fun message(
    channel: PortalChatChannel = PortalChatChannel.GAME,
    content: String = "сообщение",
) = PortalOutboundChatMessage(
    id = 7,
    sourceEventId = "website:event-7",
    channel = channel,
    authorUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
    authorName = "Explorer",
    content = content,
    createdAt = 1_800_000_000_000,
)
