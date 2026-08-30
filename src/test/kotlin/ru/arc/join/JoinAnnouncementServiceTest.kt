package ru.arc.join

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.FirstJoinData
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class JoinAnnouncementServiceTest : FreeSpec({
    "the first accepted session emits the first-join announcement exactly once" {
        val fixture = fixture()
        val firstConnection = TestPlayer(UUID.randomUUID(), "Alex")

        fixture.service.onPostLogin(firstConnection, AnnouncementPermissions(external = false))
        fixture.scheduler.tick(20)

        fixture.sink.published shouldContainExactly
            listOf(PublishedAnnouncement("Alex", JoinAnnouncementKind.FIRST_TIME, null, publishExternally = false))

        fixture.service.onDisconnect(firstConnection)
        val secondConnection = firstConnection.reconnected()
        fixture.service.onPostLogin(secondConnection, AnnouncementPermissions(external = false))
        fixture.scheduler.tick(20)

        fixture.sink.published shouldContainExactly
            listOf(
                PublishedAnnouncement("Alex", JoinAnnouncementKind.FIRST_TIME, null, publishExternally = false),
                PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null, publishExternally = false),
            )
    }

    "a delayed join selection cannot publish after that connection disconnects" {
        val fixture = fixture(existingPlayer = "Alex")
        val connection = TestPlayer(UUID.randomUUID(), "Alex")
        val pending = CompletableFuture<String?>()
        fixture.source.responses[JoinAnnouncementKind.JOIN] = pending

        fixture.service.onPostLogin(connection, AnnouncementPermissions(external = false))
        fixture.scheduler.tick(20)
        fixture.service.onDisconnect(connection)
        pending.complete("ушёл раньше объявления")

        fixture.sink.published shouldBe emptyList()
    }

    "an accepted session with captured leave permission emits its selected leave phrase" {
        val fixture = fixture(existingPlayer = "Alex")
        val connection = TestPlayer(UUID.randomUUID(), "Alex")
        fixture.source.responses[JoinAnnouncementKind.LEAVE] =
            CompletableFuture.completedFuture("Алексей закрыл калитку")

        fixture.service.onPostLogin(connection, AnnouncementPermissions(external = true))
        fixture.service.onDisconnect(connection)
        fixture.scheduler.tick(20)

        fixture.sink.published shouldContainExactly
            listOf(
                PublishedAnnouncement(
                    "Alex",
                    JoinAnnouncementKind.LEAVE,
                    "Алексей закрыл калитку",
                    publishExternally = true,
                ),
            )
    }

    "a delayed leave selection cannot publish after a reconnect" {
        val fixture = fixture(existingPlayer = "Alex")
        val firstConnection = TestPlayer(UUID.randomUUID(), "Alex")
        val pending = CompletableFuture<String?>()
        fixture.source.responses[JoinAnnouncementKind.LEAVE] = pending

        fixture.service.onPostLogin(firstConnection, AnnouncementPermissions(external = false))
        fixture.service.onDisconnect(firstConnection)
        fixture.scheduler.tick(20)
        fixture.service.onPostLogin(
            firstConnection.reconnected(),
            AnnouncementPermissions(external = true),
        )
        pending.complete("вернулся до объявления")

        fixture.sink.published shouldBe emptyList()
    }

    "a disconnect that never reached post-login does not emit a leave announcement" {
        val fixture = fixture(existingPlayer = "Alex")

        fixture.service.onDisconnect(TestPlayer(UUID.randomUUID(), "Alex"))
        fixture.scheduler.executeAll()

        fixture.sink.published shouldBe emptyList()
    }

    "a regular player without external permission still emits join and leave announcements" {
        val fixture = fixture(existingPlayer = "Alex")
        val connection = TestPlayer(UUID.randomUUID(), "Alex")

        fixture.service.onPostLogin(connection, AnnouncementPermissions(external = false))
        fixture.scheduler.tick(20)
        fixture.service.onDisconnect(connection)
        fixture.scheduler.tick(20)

        fixture.sink.published shouldContainExactly
            listOf(
                PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null, publishExternally = false),
                PublishedAnnouncement("Alex", JoinAnnouncementKind.LEAVE, null, publishExternally = false),
            )
    }
})

private data class Fixture(
    val service: JoinAnnouncementService,
    val scheduler: TestTaskScheduler,
    val source: TestMessageSource,
    val sink: RecordingAnnouncementSink,
)

private fun fixture(existingPlayer: String? = null): Fixture {
    val scheduler = TestTaskScheduler()
    val source = TestMessageSource()
    val sink = RecordingAnnouncementSink()
    val firstJoin =
        FirstJoinData(
            Files.createTempDirectory("proxyarc-announcement-service-").resolve("first_time_join.json"),
        )
    existingPlayer?.let { firstJoin.claimFirstJoin(it).persisted.join() }
    val service =
        JoinAnnouncementService(
            firstJoinData = firstJoin,
            messageSource = source,
            sink = sink,
            scheduler = scheduler,
            lifecycle = TestProxyLifecycle(),
        )
    return Fixture(service, scheduler, source, sink)
}

private class TestPlayer(
    override val playerId: UUID,
    override val playerName: String,
    override val connectionIdentity: Any = Any(),
) : AnnouncementPlayer {
    override var active: Boolean = true

    fun reconnected(): TestPlayer {
        active = false
        return TestPlayer(playerId, playerName)
    }
}

private class TestMessageSource : JoinMessageSource {
    val responses = mutableMapOf<JoinAnnouncementKind, CompletableFuture<String?>>()

    override fun load(playerName: String, kind: JoinAnnouncementKind): CompletableFuture<String?> =
        responses[kind] ?: CompletableFuture.completedFuture(null)
}

private class RecordingAnnouncementSink : JoinAnnouncementSink {
    val published = mutableListOf<PublishedAnnouncement>()

    override fun publish(announcement: PublishedAnnouncement) {
        published += announcement
    }
}

private class TestProxyLifecycle : ProxyLifecycle {
    override val shuttingDown: Boolean = false
}
