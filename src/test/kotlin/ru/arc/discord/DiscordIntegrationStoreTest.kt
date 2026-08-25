package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.util.UUID

class DiscordIntegrationStoreTest : FreeSpec({
    "preferences are opt-in, atomic and survive reload" {
        val file = Files.createTempDirectory("discord-integration-store").resolve("state.json")
        val userId = "297430445975404544"
        val store = DiscordIntegrationStore(file)

        store.preferences(userId).enabled shouldBe emptySet()
        store.toggle(userId, DiscordNotificationKind.MENTIONS).enabled shouldBe setOf(DiscordNotificationKind.MENTIONS)
        store.toggle(userId, DiscordNotificationKind.AUCTION).enabled shouldBe
            setOf(DiscordNotificationKind.MENTIONS, DiscordNotificationKind.AUCTION)

        DiscordIntegrationStore(file).preferences(userId).enabled shouldBe
            setOf(DiscordNotificationKind.MENTIONS, DiscordNotificationKind.AUCTION)
    }

    "recovery requests are unique while active" {
        var now = 1_000_000L
        val store =
            DiscordIntegrationStore(
                Files.createTempDirectory("discord-recovery-store").resolve("state.json"),
                clock = { now },
            )
        val link =
            DiscordIdentityLink(
                UUID.randomUUID(),
                "GrocerMC",
                "297430445975404544",
                now,
                now,
            )

        val first = store.createRecoveryRequest(link, 600)
        val second = store.createRecoveryRequest(link, 600)
        second.id shouldBe first.id

        now += 601_000
        store.activeRecoveryRequest(link.discordUserId) shouldBe null
        store.createRecoveryRequest(link, 600).id shouldNotBe first.id
    }

    "event participation toggles and reminders are idempotently marked" {
        val store = DiscordIntegrationStore(Files.createTempDirectory("discord-event-store").resolve("state.json"))
        val event = store.createEvent("Турнир", "Дуэли", startsAt = 4_000_000)
        val userId = "297430445975404544"

        store.toggleEventParticipant(event.id, userId)?.second shouldBe true
        store.toggleEventParticipant(event.id, userId)?.second shouldBe false
        store.toggleEventParticipant(event.id, userId)?.second shouldBe true
        store.activeEvent()?.participantDiscordIds shouldContainExactly setOf(userId)

        store.remindersDue(3_100_000, listOf(15)).size shouldBe 1
        store.markReminderSent(event.id, 15)
        store.remindersDue(3_100_000, listOf(15)).size shouldBe 0
    }

    "only one event can be active" {
        val store = DiscordIntegrationStore(Files.createTempDirectory("discord-event-single").resolve("state.json"))
        store.createEvent("Турнир", "Первый", startsAt = 4_000_000)

        shouldThrow<IllegalStateException> {
            store.createEvent("Дуэль", "Второй", startsAt = 5_000_000)
        }
    }
})
