package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ProxyGlobalChatServiceTest : FreeSpec({
    "read returns the newest slice in chronological order and cursor pages do not skip overflow" {
        val service = ProxyGlobalChatService(historyCapacity = 10, instanceId = INSTANCE_A)
        val one = service.record(ProxyGlobalChatSource.MINECRAFT, "Alex", UUID.randomUUID(), "one")!!
        val two = service.record(ProxyGlobalChatSource.TELEGRAM, "Foll", null, "two")!!
        val three = service.record(ProxyGlobalChatSource.DISCORD, "Mira", null, "three")!!

        val recent = service.read(limit = 2)
        recent.messages.map { it.content }.shouldContainExactly("two", "three")
        recent.nextCursor shouldBe three.cursor
        recent.historyGap shouldBe true
        recent.gapReason shouldBe "instance-start"

        val firstPage = service.read(limit = 1, after = one.cursor)
        firstPage.messages.map { it.content }.shouldContainExactly("two")
        firstPage.nextCursor shouldBe two.cursor

        val secondPage = service.read(limit = 1, after = firstPage.nextCursor)
        secondPage.messages.map { it.content }.shouldContainExactly("three")
        secondPage.nextCursor shouldBe three.cursor
        secondPage.historyGap shouldBe false
    }

    "player filter matches author and linked UUID" {
        val service = ProxyGlobalChatService(instanceId = INSTANCE_A)
        val uuid = UUID.randomUUID()
        service.record(ProxyGlobalChatSource.MINECRAFT, "PlayerOne", uuid, "game")
        service.record(ProxyGlobalChatSource.TELEGRAM, "Foll", uuid, "telegram")
        service.record(ProxyGlobalChatSource.DISCORD, "Other", null, "other")

        service.read(limit = 10, player = "playerone").messages.map { it.content }.shouldContainExactly("game")
        service.read(limit = 10, player = uuid.toString()).messages.map { it.content }.shouldContainExactly("game", "telegram")
    }

    "expired history and previous process cursors report a gap explicitly" {
        val truncated = ProxyGlobalChatService(historyCapacity = 2, instanceId = INSTANCE_A)
        truncated.record(ProxyGlobalChatSource.MINECRAFT, "A", null, "one")
        truncated.record(ProxyGlobalChatSource.MINECRAFT, "B", null, "two")
        val last = truncated.record(ProxyGlobalChatSource.MINECRAFT, "C", null, "three")!!

        val expired = truncated.read(limit = 1, after = "$INSTANCE_A:0")
        expired.messages.map { it.content }.shouldContainExactly("two")
        expired.historyGap shouldBe true
        expired.gapReason shouldBe "buffer-truncated"

        val restarted = ProxyGlobalChatService(instanceId = INSTANCE_B)
        restarted.record(ProxyGlobalChatSource.MINECRAFT, "D", null, "current")
        val oldEpoch = restarted.read(limit = 5, after = last.cursor)
        oldEpoch.messages.map { it.content }.shouldContainExactly("current")
        oldEpoch.historyGap shouldBe true
        oldEpoch.gapReason shouldBe "instance-changed"
    }

    "send receipts deduplicate identical request ids and reject changed content" {
        val service = ProxyGlobalChatService(instanceId = INSTANCE_A)
        var sendCount = 0
        val delivery = ProxyGlobalChatDelivery {
            sendCount++
            CompletableFuture.completedFuture(
                ProxyGlobalChatDeliveryResult(
                    minecraft = ProxyGlobalChatSinkReceipt("accepted", recipients = 4),
                    discord = ProxyGlobalChatSinkReceipt("accepted"),
                    telegram = ProxyGlobalChatSinkReceipt("confirmed", messageId = 81, chatId = "-1001", threadId = 2),
                ),
            )
        }

        val first = service.submit(REQUEST_A, INSTANCE_A, "Codex message", delivery) as ProxyGlobalChatSubmission.Submitted
        val receipt = first.receipt.get()
        val replay = service.submit(REQUEST_A, INSTANCE_A, "Codex message", delivery) as ProxyGlobalChatSubmission.Submitted

        receipt.deliveries["minecraft"]?.recipients shouldBe 4
        receipt.deliveries["telegram"]?.messageId shouldBe 81
        replay.replayed shouldBe true
        replay.receipt.get() shouldBe receipt
        sendCount shouldBe 1
        service.read(limit = 1).messages.single().let {
            it.source shouldBe "codex"
            it.author shouldBe "Codex"
            it.content shouldBe "Codex message"
        }

        val conflict = service.submit(REQUEST_A, INSTANCE_A, "different text", delivery) as ProxyGlobalChatSubmission.Rejected
        conflict.httpStatus shouldBe 409
        sendCount shouldBe 1
    }

    "receipt capacity fails closed and content validation bounds plain chat" {
        val service = ProxyGlobalChatService(receiptCapacity = 1, instanceId = INSTANCE_A)
        val delivery = ProxyGlobalChatDelivery {
            CompletableFuture.completedFuture(
                ProxyGlobalChatDeliveryResult(
                    ProxyGlobalChatSinkReceipt("unavailable"),
                    ProxyGlobalChatSinkReceipt("unavailable"),
                    ProxyGlobalChatSinkReceipt("unavailable"),
                ),
            )
        }
        service.submit(REQUEST_A, INSTANCE_A, "one", delivery)
        val full = service.submit(REQUEST_B, INSTANCE_A, "two", delivery) as ProxyGlobalChatSubmission.Rejected

        full.httpStatus shouldBe 429
        service.validateOutgoingContent("a".repeat(500)) shouldBe null
        service.validateOutgoingContent("😀".repeat(500)) shouldBe null
        service.validateOutgoingContent("😀".repeat(501)) shouldBe "content must contain 1..500 characters and at most 2000 UTF-8 bytes"
        service.validateOutgoingContent("line\nbreak") shouldBe "content must not contain control characters or line breaks"
        service.validateOutgoingContent("separator\u2028line") shouldBe "content must not contain control characters or line breaks"
    }
})

private const val INSTANCE_A = "00000000-0000-0000-0000-00000000000a"
private const val INSTANCE_B = "00000000-0000-0000-0000-00000000000b"
private const val REQUEST_A = "00000000-0000-0000-0000-0000000000a1"
private const val REQUEST_B = "00000000-0000-0000-0000-0000000000b2"
