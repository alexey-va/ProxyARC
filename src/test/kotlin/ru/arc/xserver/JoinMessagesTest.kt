package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.join.JoinAnnouncementKind

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

    "typed selector ignores blank phrases and chooses only from the requested kind" {
        val messages = JoinMessages("Alex")
        messages.randomMessage(JoinAnnouncementKind.JOIN) shouldBe null
        messages.randomMessage(JoinAnnouncementKind.LEAVE) shouldBe null
        messages.joinMessages = setOf("", "only")
        messages.leaveMessages = setOf("bye")

        messages.randomMessage(JoinAnnouncementKind.JOIN) shouldBe "only"
        messages.randomMessage(JoinAnnouncementKind.LEAVE) shouldBe "bye"
        messages.randomMessage(JoinAnnouncementKind.FIRST_TIME) shouldBe null
    }
})
