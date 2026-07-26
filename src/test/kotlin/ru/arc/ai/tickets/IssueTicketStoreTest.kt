package ru.arc.ai.tickets

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.assertions.throwables.shouldThrowAny
import ru.arc.redis.InMemoryRedis
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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

        "generates unique ticket ids under concurrent requests" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            val executor = Executors.newFixedThreadPool(16)
            val start = CountDownLatch(1)
            try {
                val futures =
                    List(100) {
                        CompletableFuture.supplyAsync(
                            {
                                start.await()
                                IssueTicketStore.nextTicketId()
                            },
                            executor,
                        )
                    }
                start.countDown()
                val ids = futures.map { it.get() }

                ids.toSet().size shouldBe ids.size
            } finally {
                executor.shutdownNow()
            }
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

        "continues local numbering from the highest cached ticket" {
            IssueTicketStore.bind(null)
            IssueTicketStore.replaceAll(
                listOf(
                    IssueTicket(
                        ticketId = "RB-00042",
                        threadId = "42",
                        starterMessageId = null,
                        reporter = "Grocer",
                        title = "existing",
                        createdAt = 42L,
                    ),
                ),
            )

            IssueTicketStore.nextTicketId() shouldBe "RB-00043"
        }

        "does not publish a failed save into the cache" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            redis.failOnSave = true
            val ticket =
                IssueTicket(
                    ticketId = "RB-00050",
                    threadId = "50",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "will fail",
                    createdAt = 50L,
                )

            shouldThrowAny { IssueTicketStore.save(ticket) }

            IssueTicketStore.find(ticket.ticketId) shouldBe null
        }

        "keeps a ticket cached when Redis deletion fails" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            val ticket =
                IssueTicket(
                    ticketId = "RB-00051",
                    threadId = "51",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "keep me",
                    createdAt = 51L,
                )
            IssueTicketStore.save(ticket)
            redis.failOnSave = true

            shouldThrowAny { IssueTicketStore.delete(ticket.ticketId) }

            IssueTicketStore.find(ticket.ticketId) shouldBe ticket
        }

        "retries loading after a transient Redis failure" {
            val redis = InMemoryRedis()
            redis.failOnLoad = true
            IssueTicketStore.bind(redis)
            val ticket =
                IssueTicket(
                    ticketId = "RB-00052",
                    threadId = "52",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "loaded later",
                    createdAt = 52L,
                )
            redis.setHash(IssueTicketStore.STORAGE_KEY, mapOf(ticket.ticketId to Gson().toJson(ticket)))
            redis.failOnLoad = false

            IssueTicketStore.find(ticket.ticketId) shouldBe ticket
        }

        "deletes ticket ids case-insensitively" {
            val redis = InMemoryRedis()
            IssueTicketStore.bind(redis)
            val ticket =
                IssueTicket(
                    ticketId = "RB-00053",
                    threadId = "53",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "case insensitive",
                    createdAt = 53L,
                )
            IssueTicketStore.save(ticket)

            IssueTicketStore.delete("rb-00053") shouldBe true

            IssueTicketStore.find(ticket.ticketId) shouldBe null
        }

        "skips null JSON without blocking valid tickets" {
            val redis = InMemoryRedis()
            val ticket =
                IssueTicket(
                    ticketId = "RB-00054",
                    threadId = "54",
                    starterMessageId = null,
                    reporter = "Grocer",
                    title = "valid",
                    createdAt = 54L,
                )
            redis.setHash(
                IssueTicketStore.STORAGE_KEY,
                mapOf("broken" to "null", ticket.ticketId to Gson().toJson(ticket)),
            )

            IssueTicketStore.bind(redis)

            IssueTicketStore.find(ticket.ticketId) shouldBe ticket
        }
    }
})
