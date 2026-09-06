package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import com.google.gson.Gson
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
        local.customJoinMessages shouldBe emptySet()
        local.timestamp shouldBe 42
    }

    "legacy JSON defaults custom fields to empty" {
        val messages = Gson().fromJson("{\"player\":\"Alex\",\"joinMessages\":[\"joined\"]}", JoinMessages::class.java)

        messages.customJoinMessages shouldBe emptySet()
        messages.customLeaveMessages shouldBe emptySet()
    }

    "custom suffix validation trims, rejects markup and caps each kind" {
        val valid = JoinMessages.validCustomMessages(
            linkedSetOf("  hello  ", "", "<red>x", "ok%", "#bad", "real", " \n ") + (1..11).map { "item$it" },
        )

        valid shouldContainExactly listOf("hello", "real", "item1", "item2", "item3", "item4", "item5", "item6", "item7", "item8")
    }

    "full templates preserve markup and use a reset selection key" {
        val template = "<gold>Добро пожаловать, <bold>%player_name%</bold>!"
        JoinMessages.validCustomMessages(setOf(template)) shouldContainExactly listOf(template)
        JoinMessages.customSelectionKey(template) shouldBe "<reset>$template"
        JoinMessages.customSelectionKey("hello") shouldBe "%player_name% hello"
    }

    "full templates reject unsafe or unknown markup" {
        JoinMessages.validCustomMessages(setOf("<click:run_command:'/op %player_name%'>x</click>")) shouldBe emptyList()
        JoinMessages.validCustomMessages(setOf("<not-a-tag>%player_name%</not-a-tag>")) shouldBe emptyList()
        JoinMessages.validCustomMessages(setOf("<red>%player_name% %player_name%</red>")) shouldBe emptyList()
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
