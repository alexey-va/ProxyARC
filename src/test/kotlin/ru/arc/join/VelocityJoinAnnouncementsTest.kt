package ru.arc.join

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly

class VelocityJoinAnnouncementsTest : FreeSpec({
    "regular players publish join and leave announcements only to Minecraft" {
        listOf(JoinAnnouncementKind.FIRST_TIME, JoinAnnouncementKind.JOIN, JoinAnnouncementKind.LEAVE)
            .forEach { kind ->
                PublishedAnnouncement("Alex", kind, null, publishExternally = false).destinations() shouldContainExactly
                    listOf(JoinAnnouncementDestination.MINECRAFT)
            }
    }

    "players with external permission also publish to Discord and Telegram" {
        PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null, publishExternally = true).destinations() shouldContainExactly
            listOf(
                JoinAnnouncementDestination.DISCORD,
                JoinAnnouncementDestination.TELEGRAM,
                JoinAnnouncementDestination.MINECRAFT,
            )
    }
})
