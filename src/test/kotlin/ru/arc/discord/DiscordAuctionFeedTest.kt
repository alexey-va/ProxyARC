package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import ru.arc.auction.AuctionItemDto
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class DiscordAuctionFeedTest : FreeSpec({
    val previousDataFolder = Velocity.dataFolder
    beforeSpec { Velocity.dataFolder = Files.createTempDirectory("auction-feed-runtime") }
    afterSpec { Velocity.dataFolder = previousDataFolder }

    "empty snapshot has an explicit explanation" {
        val fixture = AuctionFeedFixture()
        fixture.feed.buildAuctionEmbed(emptyList()).description shouldBe
            "Сейчас на аукционе нет активных лотов."
    }

    "timeout editing the existing card does not send a duplicate and next snapshot reconciles" {
        val fixture = AuctionFeedFixture()
        val original = fixture.message("100")
        fixture.history(listOf(fixture.message("200"), original, fixture.message("300", "human")))
        val edit = CompletableFuture<Message>()
        every { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()).submit() } returns edit

        fixture.feed.updateAuctionItems(emptyList())
        fixture.feed.updateAuctionItems(emptyList())
        verify(exactly = 1) { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()) }
        edit.completeExceptionally(SocketTimeoutException("Connect timed out"))
        verify(exactly = 0) { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()) }

        every { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()).submit() } returns
            CompletableFuture.completedFuture(original)
        fixture.feed.updateAuctionItems(emptyList())
        verify(exactly = 2) { fixture.channel.history.retrievePast(100).submit() }
        verify(exactly = 2) { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()) }
    }

    "concurrent initial snapshots create one card and retain the newest contents" {
        val fixture = AuctionFeedFixture()
        fixture.history(emptyList())
        val send = CompletableFuture<Message>()
        val original = fixture.message("100")
        every { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()).submit() } returns send
        every { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()).submit() } returns
            CompletableFuture.completedFuture(original)
        fixture.feed.updateAuctionItems(emptyList())
        fixture.feed.updateAuctionItems(listOf(AuctionItemDto(display = "Stone", amount = 1)))
        fixture.feed.updateAuctionItems(listOf(AuctionItemDto(display = "Diamond", amount = 2)))
        fixture.feed.buildAuctionEmbed(listOf(AuctionItemDto(display = "Diamond", amount = 2))).fields.size shouldBe 1
        send.complete(original)

        verify(exactly = 1) { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            fixture.channel.editMessageEmbedsById("100", match<MessageEmbed> {
                it.fields.first().name!!.contains("Diamond")
            })
        }
    }

    "failed history lookup never assumes the channel is empty" {
        val fixture = AuctionFeedFixture()
        every { fixture.channel.history.retrievePast(100).submit() } returns
            CompletableFuture.failedFuture(SocketTimeoutException("timeout"))
        fixture.feed.updateAuctionItems(emptyList())
        verify(exactly = 0) { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    "uncertain send is recovered from history without sending twice" {
        val fixture = AuctionFeedFixture()
        fixture.history(emptyList())
        every { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()).submit() } returns
            CompletableFuture.failedFuture(SocketTimeoutException("response lost"))
        fixture.feed.updateAuctionItems(emptyList())
        val original = fixture.message("100")
        fixture.history(listOf(original))
        every { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()).submit() } returns
            CompletableFuture.completedFuture(original)
        fixture.feed.updateAuctionItems(emptyList())
        verify(exactly = 1) { fixture.channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { fixture.channel.editMessageEmbedsById("100", any<MessageEmbed>()) }
    }
})

private class AuctionFeedFixture {
    val channel = mockk<TextChannel>(relaxed = true)
    private val jda = mockk<JDA>(relaxed = true)
    private val executor = mockk<ScheduledExecutorService>()
    val feed: DiscordFeedService

    init {
        every { channel.id } returns "auction"
        every { jda.selfUser.id } returns "bot"
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() }
        val session = DiscordSession()
        session.activate(jda, DiscordChannels(null, null, channel, channel, channel, null))
        val config = ProxyConfigs.module(Files.createTempDirectory("auction-feed"), "discord.yml")
        config.setString("auction.title", "Предметы на аукционе %amount%")
        feed = DiscordFeedService(session, config, config, executor)
    }

    fun message(id: String, author: String = "bot"): Message = mockk<Message>().also {
        every { it.id } returns id
        every { it.idLong } returns id.toLong()
        every { it.author.id } returns author
        every { it.embeds } returns listOf(EmbedBuilder().setTitle("Предметы на аукционе 0").build())
    }

    fun history(messages: List<Message>) {
        every { channel.history.retrievePast(100).submit() } returns CompletableFuture.completedFuture(messages)
    }
}
