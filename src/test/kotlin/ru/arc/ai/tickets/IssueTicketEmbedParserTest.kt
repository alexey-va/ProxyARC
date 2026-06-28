package ru.arc.ai.tickets

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import java.time.OffsetDateTime

class IssueTicketEmbedParserTest : FreeSpec({
    "IssueTicketEmbedParser" - {
        "parses embed fields from forum thread" {
            val thread = mockk<ThreadChannel>()
            every { thread.id } returns "thread-1"
            every { thread.name } returns "rtp broken"
            every { thread.isArchived } returns false
            every { thread.timeCreated } returns OffsetDateTime.parse("2026-06-25T12:00:00Z")

            val embed = mockk<MessageEmbed>()
            every { embed.title } returns "rtp broken"
            every { embed.description } returns "после ртп кикает с сервера"
            every { embed.fields } returns
                listOf(
                    field("ID", "RB-00042"),
                    field("Репортёр", "grocer"),
                    field("Сервер", "Survival"),
                )

            val ticket = IssueTicketEmbedParser.fromThread(thread, embed)
            ticket?.ticketId shouldBe "RB-00042"
            ticket?.reporter shouldBe "grocer"
            ticket?.server shouldBe "Survival"
            ticket?.summary shouldBe "после ртп кикает с сервера"
            ticket?.status shouldBe IssueTicket.STATUS_OPEN
        }
    }
})

private fun field(name: String, value: String): MessageEmbed.Field {
    val field = mockk<MessageEmbed.Field>()
    every { field.name } returns name
    every { field.value } returns value
    return field
}
