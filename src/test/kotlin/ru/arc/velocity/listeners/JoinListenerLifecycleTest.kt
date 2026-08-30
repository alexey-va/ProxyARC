package ru.arc.velocity.listeners

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.proxy.Player
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.join.AnnouncementPermissions
import ru.arc.join.AnnouncementPlayer
import ru.arc.join.JoinSessionAnnouncements
import ru.arc.redis.RedisOperations
import ru.arc.velocity.Velocity
import ru.arc.xserver.PlayerListAnnouncer
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID

class JoinListenerLifecycleTest : FreeSpec({
    beforeTest {
        Velocity.isShuttingDown.set(false)
        Velocity.discordBot = null
        Velocity.playerActivityTracker = null
    }

    afterTest {
        Velocity.playerListAnnouncer = null
    }

    "disconnect always removes the accepted player from the network list" {
        val playerId = UUID.randomUUID()
        val player = player(playerId, "Alex")
        val event = DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)
        val announcements = RecordingSessionAnnouncements()

        val announcer = PlayerListAnnouncer(emptyInterface(RedisOperations::class.java), "players")
        announcer.addPlayer(playerId, "Alex", "survival")
        Velocity.playerListAnnouncer = announcer

        val listener = JoinListener(announcements)

        listener.onPlayerLeave(event)

        announcer.serverForUsername("Alex") shouldBe null
        announcements.disconnected.map(AnnouncementPlayer::playerId) shouldContainExactly listOf(playerId)
    }

    "post-login registers an accepted session and captures announcement permissions while active" {
        val playerId = UUID.randomUUID()
        val player =
            player(
                playerId,
                "Alex",
                permissions = setOf(JoinListener.EXTERNAL_JOIN_ANNOUNCEMENT_PERMISSION),
            )
        val announcements = RecordingSessionAnnouncements()
        val announcer = PlayerListAnnouncer(emptyInterface(RedisOperations::class.java), "players")
        Velocity.playerListAnnouncer = announcer

        JoinListener(announcements).onPlayerAuthenticated(PostLoginEvent(player))

        announcer.serverForUsername("Alex") shouldBe ""
        announcements.connected.single().permissions shouldBe
            AnnouncementPermissions(external = true)
    }
})

private fun player(id: UUID, name: String, permissions: Set<String> = emptySet()): Player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "getUniqueId" -> id
            "getUsername" -> name
            "getCurrentServer" -> Optional.empty<Any>()
            "hasPermission" -> args?.firstOrNull() in permissions
            "isActive" -> true
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "Player($name)"
            else -> defaultValue(method.returnType)
        }
    } as Player

private fun <T> emptyInterface(type: Class<T>): T =
    type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Test${type.simpleName}"
                else -> defaultValue(method.returnType)
            }
        },
    )

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

private class RecordingSessionAnnouncements : JoinSessionAnnouncements {
    val connected = mutableListOf<ConnectedCall>()
    val disconnected = mutableListOf<AnnouncementPlayer>()

    override fun onPostLogin(player: AnnouncementPlayer, permissions: AnnouncementPermissions) {
        connected += ConnectedCall(player, permissions)
    }

    override fun onDisconnect(player: AnnouncementPlayer) {
        disconnected += player
    }

    data class ConnectedCall(
        val player: AnnouncementPlayer,
        val permissions: AnnouncementPermissions,
    )
}
