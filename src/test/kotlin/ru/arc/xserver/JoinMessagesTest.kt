package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class JoinMessagesTest : FreeSpec({
    "merge replaces both snapshots and copies timestamp" {
        val local = JoinMessages("Alex")
        local.joinMessages = setOf("old")
        local.leaveMessages = setOf("old-leave")
        val remote = JoinMessages("Alex")
        remote.joinMessages = mutableSetOf("new")
        remote.leaveMessages = mutableSetOf("new-leave")
        remote.timestamp = 42

        local.merge(remote)
        (remote.joinMessages as MutableSet).add("later")

        local.joinMessages shouldBe setOf("new")
        local.leaveMessages shouldBe setOf("new-leave")
        local.timestamp shouldBe 42
    }

    "random selectors return null for empty sets and a configured message otherwise" {
        val messages = JoinMessages("Alex")
        messages.randomJoinMessage() shouldBe null
        messages.randomLeaveMessage() shouldBe null
        messages.joinMessages = setOf("only")
        messages.leaveMessages = setOf("bye")

        messages.randomJoinMessage() shouldBe "only"
        messages.randomLeaveMessage() shouldBe "bye"
    }
})
