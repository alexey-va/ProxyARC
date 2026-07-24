package ru.arc.ai.tickets

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import ru.arc.redis.InMemoryRedis

class IssueTicketStoreTest : FreeSpec({
    "IssueTicketStore" - {
        "generates sequential ticket ids" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            val first = IssueTicketStore.nextTicketId()
            val second = IssueTicketStore.nextTicketId()
            first.shouldStartWith("RB-")
            second.shouldStartWith("RB-")
            first shouldBe "RB-00001"
            second shouldBe "RB-00002"
        }

        "finds open ticket by reporter" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            IssueTicketStore.save(
                IssueTicket(
                    ticketId = "RB-00010",
                    threadId = "1",
                    starterMessageId = "2",
                    reporter = "Grocer",
                    title = "rtp broken",
                    createdAt = 1L,
                ),
            )
            IssueTicketStore.findOpenByReporter("grocer")?.ticketId shouldBe "RB-00010"
            IssueTicketStore.listRecent(5, "Grocer").shouldHaveSize(1)
            IssueTicketStore.listOpenRecent(5).shouldHaveSize(1)
        }

        "closes open tickets when forum thread was deleted" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            IssueTicketStore.save(
                IssueTicket(
                    ticketId = "RB-00020",
                    threadId = "thread-deleted",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "old bug",
                    createdAt = 1L,
                ),
            )
            IssueTicketStore.reconcileForumThreads(setOf("other-thread")) shouldBe 1
            IssueTicketStore.findOpenByReporter("Grocer") shouldBe null
            IssueTicketStore.find("RB-00020")?.status shouldBe IssueTicket.STATUS_CLOSED
        }
    }
})
