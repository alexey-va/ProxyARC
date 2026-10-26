package ru.arc.staff

import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.PermissionOverride
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.google.gson.JsonParser

private fun testConfig(root: Path = Files.createTempDirectory("staff-config"), setup: (Config) -> Unit = {}): StaffBridgeConfig {
    val cfg = ConfigManager.of(root, "staff.yml")
    setup(cfg)
    return StaffBridgeConfig(cfg, root)
}

class StaffBridgeTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "finite moderation grammar excludes injected flags, bad names, secrets and line separators" {
        StaffCommands.parse("mute Player 10m Повторный флуд") shouldBe StaffCommand("proxy", "tempmute Player 10m Повторный флуд", "litebans.tempmute", "mute")
        StaffCommands.parse("ban Player permanent Нарушение правил")?.line shouldBe "ban Player Нарушение правил"
        StaffCommands.parse("unban Player")?.permission shouldBe "litebans.unban"
        listOf("ban -s 1d x", "ban Player 0d x", "ban Player 1d x -s", "ban Player 1d -N x",
            "unban Player -s", "command spawn list\nstop", "command proxy minecraft:login secret",
            "command spawn cmi changepassword secret", "mute Player 1d reason\t-s", "command spawn execute as Player run login secret",
            "command proxy minecraft:sudo Player login secret", "command spawn cmi sudo Player login secret",
            "command proxy list\u2028stop", "command proxy list\u2029stop", "ban Player 1d reason\u2028stop").forEach {
            StaffCommands.parse(it) shouldBe null
        }
        StaffCommands.parse("command spawn list")?.server shouldBe "spawn"
    }

    "kick uses the bounded proxy moderation grammar without a duration" {
        StaffCommands.parse("kick Player Повторный флуд") shouldBe
            StaffCommand("proxy", "kick Player Повторный флуд", "litebans.kick", "kick")
        StaffCommands.parse("KICK Player ${"x".repeat(256)}")?.line shouldBe "kick Player ${"x".repeat(256)}"
        listOf("kick", "kick Player", "kick Player    ", "kick -s reason", "kick Player-name reason",
            "kick ${"x".repeat(17)} reason", "kick Player -s reason", "kick Player reason -N",
            "kick Player reason\nstop", "kick Player reason\t-s", "kick Player reason\u0000stop",
            "kick Player reason\u2028stop", "kick Player reason\u2029stop", "kick Player ${"x".repeat(257)}").forEach {
            StaffCommands.parse(it) shouldBe null
        }
    }

    "proxy credential and execution-wrapper floor includes the ARC mandatory corpus in namespaced and CMI forms" {
        val arcFloor = setOf(
            "l", "login", "register", "reg", "changepassword", "changepass", "password", "unregister", "2fa", "totp",
            "auth", "authme", "limboauth", "nlogin", "resetpassword", "resetpass", "setpassword", "setpass",
            "forcepassword", "forcelogin", "forceregister", "usermeta", "token", "secret", "apikey", "api-key",
            "execute", "sudo", "runas", "eval", "dispatch", "script", "skript", "denizen", "ex", "exs",
        )
        arcFloor.forEach { command ->
            listOf("$command value", "/plugin:$command value", "cmi $command value", "plugin:cmi /other:$command value").forEach { line ->
                StaffCommands.sensitive(line) shouldBe true
                StaffCommands.parse("command proxy $line") shouldBe null
            }
        }
        StaffCommands.validLine("list\u2028stop") shouldBe false
        StaffCommands.validLine("list\u2029stop") shouldBe false
        StaffCommands.sensitive("list") shouldBe false
        StaffCommands.sensitive("cmi list") shouldBe false
    }

    "ledger survives restart and concurrent lifecycle generations without retaining command bodies" {
        val root = Files.createTempDirectory("staff-ledger")
        val first = StaffCommandLedger(root)
        val second = StaffCommandLedger(root)
        first.claim("discord:123", 1_000) shouldBe true
        second.claim("discord:123", 1_001) shouldBe false
        second.claim("discord:124", 1_001) shouldBe true
        first.claim("discord:124", 1_002) shouldBe false
        StaffCommandLedger(root).claim("discord:123", 1_003) shouldBe false
        first.claim("discord:123", 602_000) shouldBe true
    }

    "command service denies missing link, missing staff role, native permission and backend access for moderators" {
        val root = Files.createTempDirectory("staff-denials")
        val config = testConfig()
        val identity = StaffIdentity(UUID.randomUUID(), "Moderator")
        var linked: StaffIdentity? = null
        var actor: StaffActor? = null
        val calls = AtomicInteger()
        StaffCommandService(config, root, { _, _ -> linked }, { CompletableFuture.completedFuture(actor) },
            { _, _, _ -> calls.incrementAndGet(); CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList())) },
            clock = { 10_000 }).use { service ->
            fun execute(input: String) = service.execute(StaffRequest("discord:123", "discord", "123", 10_000, input)).get(5, TimeUnit.SECONDS)
            execute("ban Player 1d reason") shouldBe config.message("denied")
            linked = identity
            execute("ban Player 1d reason") shouldBe config.message("denied")
            actor = StaffActor(identity, false) { false }
            execute("ban Player 1d reason") shouldBe config.message("denied")
            actor = StaffActor(identity, false) { true }
            execute("command spawn list") shouldBe config.message("admin-required")
            calls.get() shouldBe 0
        }
    }

    "public routes require an administrator without consuming denied events and retain native permissions" {
        val config = testConfig()
        val identity = StaffIdentity(UUID.randomUUID(), "Staff")
        var actor = StaffActor(identity, false) { true }
        var now = 10_000L
        val dispatched = ArrayList<StaffCommand>()
        val request = StaffRequest("discord:100", "discord", "100", now, "kick Player reason", adminOnly = true)
        StaffCommandService(config, Files.createTempDirectory("staff-public-access"), { _, _ -> identity }, {
            CompletableFuture.completedFuture(actor)
        }, { _, _, command ->
            dispatched += command
            CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList()))
        }, clock = { now }).use { service ->
            fun execute(input: StaffRequest) = service.execute(input).get(5, TimeUnit.SECONDS)
            execute(request) shouldBe config.message("admin-required")
            actor = StaffActor(identity, true) { false }
            execute(request) shouldBe config.message("denied")
            dispatched shouldBe emptyList()
            actor = StaffActor(identity, true) { it == "litebans.kick" }
            execute(request) shouldBe config.message("no-output")
            dispatched.single() shouldBe StaffCommands.parse(request.input)!!.copy(adminOnly = true)
            actor = StaffActor(identity, false) { it == "litebans.kick" }
            now += 3_000
            execute(request.copy(eventId = "discord:101", createdAt = now, adminOnly = false)) shouldBe config.message("no-output")
            dispatched.last() shouldBe StaffCommands.parse(request.input)
            dispatched.size shouldBe 2
        }
    }

    "public route loses administrator access before service dispatch after a role downgrade" {
        val identity = StaffIdentity(UUID.randomUUID(), "Staff")
        val config = testConfig()
        val calls = AtomicInteger()
        val moderator = StaffActor(identity, false) { true }
        val actor = StaffActor(identity, true, refresh = { moderator }) { true }
        StaffCommandService(config, Files.createTempDirectory("staff-public-downgrade"), { _, _ -> identity }, {
            CompletableFuture.completedFuture(actor)
        }, { _, _, _ -> calls.incrementAndGet(); CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList())) },
            clock = { 10_000 }).use { service ->
            service.execute(StaffRequest("telegram:100:1", "telegram", "100", 10_000, "kick Player reason", adminOnly = true))
                .get(5, TimeUnit.SECONDS) shouldBe config.message("admin-required")
            calls.get() shouldBe 0
        }
    }

    "identity is rechecked after asynchronous authorization" {
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        var linked: StaffIdentity? = identity
        val authorization = CompletableFuture<StaffActor?>()
        val reached = CompletableFuture<Unit>()
        val calls = AtomicInteger()
        val config = testConfig()
        StaffCommandService(config, Files.createTempDirectory("staff-race"), { _, _ -> linked }, {
            reached.complete(Unit); authorization
        }, { _, _, _ -> calls.incrementAndGet(); CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList())) },
            clock = { 10_000 }).use { service ->
            val result = service.execute(StaffRequest("discord:123", "discord", "123", 10_000, "command proxy list"))
            reached.get(5, TimeUnit.SECONDS)
            linked = null
            authorization.complete(StaffActor(identity, true) { true })
            result.get(5, TimeUnit.SECONDS) shouldBe config.message("denied")
            calls.get() shouldBe 0
        }
    }

    "kick requires its native permission both before and after authorization refresh" {
        val identity = StaffIdentity(UUID.randomUUID(), "Moderator")
        val config = testConfig()
        val permissions = ArrayList<String>()
        val calls = AtomicInteger()
        fun permittedActor(allowed: Boolean) = StaffActor(identity, false) { permission ->
            permissions += permission
            allowed && permission == "litebans.kick"
        }
        var actor = permittedActor(false)
        var now = 10_000L
        StaffCommandService(config, Files.createTempDirectory("staff-kick-permission"), { _, _ -> identity }, {
            CompletableFuture.completedFuture(actor)
        }, { _, _, command ->
            command shouldBe StaffCommand("proxy", "kick Player reason", "litebans.kick", "kick")
            calls.incrementAndGet()
            CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList()))
        }, clock = { now }).use { service ->
            fun execute(eventId: String) = service.execute(StaffRequest(eventId, "discord", "100", now, "kick Player reason"))
                .get(5, TimeUnit.SECONDS)
            execute("discord:100") shouldBe config.message("denied")
            permissions shouldBe listOf("litebans.kick")
            actor = StaffActor(identity, false, refresh = { permittedActor(false) }) { permission ->
                permissions += permission
                permission == "litebans.kick"
            }
            now += 3_000
            execute("discord:101") shouldBe config.message("denied")
            permissions shouldBe List(3) { "litebans.kick" }
            calls.get() shouldBe 0
            actor = permittedActor(true)
            now += 3_000
            execute("discord:102") shouldBe config.message("no-output")
            permissions shouldBe List(5) { "litebans.kick" }
            calls.get() shouldBe 1
        }
    }

    "revoked loaded staff authorization cannot reach dispatch after asynchronous work" {
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        val config = testConfig()
        val calls = AtomicInteger()
        val actor = StaffActor(identity, true, refresh = { null }) { true }
        StaffCommandService(config, Files.createTempDirectory("staff-revoked"), { _, _ -> identity }, {
            CompletableFuture.completedFuture(actor)
        }, { _, _, _ -> calls.incrementAndGet(); CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList())) },
            clock = { 10_000 }).use { service ->
            service.execute(StaffRequest("discord:100", "discord", "100", 10_000, "command proxy list"))
                .get(5, TimeUnit.SECONDS) shouldBe config.message("denied")
            calls.get() shouldBe 0
        }
        StaffCommandDispatcher(mockk(), config).use { dispatcher ->
            dispatcher.execute(UUID.randomUUID(), actor, StaffCommands.parse("command proxy list")!!).join().dispatched shouldBe false
        }
    }

    "queued admin source is denied by the final native event guard after revocation or lifecycle close" {
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        var authorized: StaffActor? = StaffActor(identity, true) { true }
        var active = true
        val retained = StaffActor(identity, true, refresh = { authorized }) { true }
        val source = StaffCommandSource(retained) { active }
        val revokedEvent = CommandExecuteEvent(source, "list")
        authorized = null
        StaffCommandSource.guard(revokedEvent)
        revokedEvent.result.isAllowed shouldBe false
        authorized = StaffActor(identity, false) { true }
        val downgradedEvent = CommandExecuteEvent(source, "list")
        StaffCommandSource.guard(downgradedEvent)
        downgradedEvent.result.isAllowed shouldBe false
        authorized = StaffActor(identity, true) { true }
        val closedEvent = CommandExecuteEvent(source, "list")
        active = false
        StaffCommandSource.guard(closedEvent)
        closedEvent.result.isAllowed shouldBe false
        val playerEvent = CommandExecuteEvent(mockk<Player>(), "list")
        StaffCommandSource.guard(playerEvent)
        playerEvent.result.isAllowed shouldBe true
    }

    "repeat and old provider events cannot dispatch and unknown outcome stays reserved after restart" {
        val root = Files.createTempDirectory("staff-replay")
        val config = testConfig()
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        val calls = AtomicInteger()
        fun service() = StaffCommandService(config, root, { _, _ -> identity }, {
            CompletableFuture.completedFuture(StaffActor(identity, true) { true })
        }, { _, _, _ -> calls.incrementAndGet(); CompletableFuture.failedFuture(IllegalStateException("lost response")) }, clock = { 200_000 })
        val request = StaffRequest("telegram:123:45", "telegram", "123", 200_000, "command proxy list")
        service().use {
            it.execute(request.copy(createdAt = 1)).get(5, TimeUnit.SECONDS) shouldBe config.message("stale")
            it.execute(request).get(5, TimeUnit.SECONDS) shouldBe config.message("unknown-outcome")
            it.execute(request).get(5, TimeUnit.SECONDS) shouldBe config.message("duplicate")
        }
        service().use { it.execute(request).get(5, TimeUnit.SECONDS) shouldBe config.message("duplicate") }
        calls.get() shouldBe 1
    }

    "moderator execution uses the actual online Player and never a synthetic console substitute" {
        val proxy = mockk<ProxyServer>()
        val commands = mockk<CommandManager>()
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        val actor = StaffActor(StaffIdentity(id, "Moderator"), false) { true }
        every { proxy.commandManager } returns commands
        every { commands.hasCommand("tempmute") } returns true
        every { commands.hasCommand("kick") } returns true
        every { proxy.getPlayer(id) } returns Optional.empty()
        every { commands.executeAsync(any<CommandSource>(), any()) } returns CompletableFuture.completedFuture(true)
        StaffCommandDispatcher(proxy, testConfig()).use { dispatcher ->
            val moderation = listOf("mute Player 10m spam", "kick Player spam").map { StaffCommands.parse(it)!! }
            moderation.forEach { cmd -> dispatcher.execute(UUID.randomUUID(), actor, cmd).join().dispatched shouldBe false }
            verify(exactly = 0) { commands.executeAsync(any<CommandSource>(), any()) }
            every { proxy.getPlayer(id) } returns Optional.of(player)
            moderation.forEach { cmd -> dispatcher.execute(UUID.randomUUID(), actor, cmd).join().dispatched shouldBe true }
            verify(exactly = 1) { commands.executeAsync(player, "tempmute Player 10m spam") }
            verify(exactly = 1) { commands.executeAsync(player, "kick Player spam") }
        }
    }

    "public dispatcher refuses an administrator downgraded to an online moderator without Player fallback" {
        val proxy = mockk<ProxyServer>()
        val commands = mockk<CommandManager>()
        val player = mockk<Player>()
        val identity = StaffIdentity(UUID.randomUUID(), "Staff")
        val moderator = StaffActor(identity, false) { true }
        val actor = StaffActor(identity, true, refresh = { moderator }) { true }
        val command = StaffCommands.parse("kick Player reason")!!.copy(adminOnly = true)
        every { proxy.commandManager } returns commands
        every { commands.hasCommand("kick") } returns true
        every { proxy.getPlayer(identity.uuid) } returns Optional.of(player)
        every { commands.executeAsync(any<CommandSource>(), any()) } returns CompletableFuture.completedFuture(true)
        StaffCommandDispatcher(proxy, testConfig()).use { dispatcher ->
            dispatcher.execute(UUID.randomUUID(), actor, command).join().dispatched shouldBe false
            verify(exactly = 0) { proxy.getPlayer(identity.uuid) }
            verify(exactly = 0) { commands.executeAsync(any<CommandSource>(), any()) }
            dispatcher.execute(UUID.randomUUID(), actor, command.copy(adminOnly = false)).join().dispatched shouldBe true
            verify(exactly = 1) { commands.executeAsync(player, "kick Player reason") }
        }
    }

    "service returns an explicit refusal with the offline moderator detail and never an accepted prefix" {
        val config = testConfig()
        val identity = StaffIdentity(UUID.randomUUID(), "Moderator")
        val proxy = mockk<ProxyServer>()
        every { proxy.getPlayer(identity.uuid) } returns Optional.empty()
        StaffCommandDispatcher(proxy, config).use { dispatcher ->
            StaffCommandService(config, Files.createTempDirectory("staff-offline-refusal"), { _, _ -> identity }, {
                CompletableFuture.completedFuture(StaffActor(identity, false) { true })
            }, dispatcher::execute, clock = { 10_000 }).use { service ->
                val response = service.execute(StaffRequest("telegram:100:1", "telegram", "100", 10_000, "mute Player 10m reason"))
                    .get(5, TimeUnit.SECONDS)
                response shouldBe "${config.message("not-dispatched")}\n${config.message("online-required")}"
                response shouldNotContain config.message("accepted")
            }
        }
    }

    "backend command uses authenticated fixed endpoint and exact actor request correlation" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestId = UUID.randomUUID()
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        val received = CompletableFuture<Unit>()
        server.createContext("/ops/staff-command") { exchange ->
            try {
                exchange.requestMethod shouldBe "POST"
                exchange.requestHeaders.getFirst("Authorization") shouldBe "Bearer test-token"
                val body = JsonParser.parseString(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).asJsonObject
                body.keySet() shouldBe setOf("requestId", "actorUuid", "command")
                body["requestId"].asString shouldBe requestId.toString()
                body["actorUuid"].asString shouldBe identity.uuid.toString()
                body["command"].asString shouldBe "list"
                val bytes = """{"ok":true,"requestId":"$requestId","dispatched":true,"output":["Players: 3"]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                received.complete(Unit)
            } catch (error: Throwable) { received.completeExceptionally(error) }
            finally { exchange.close() }
        }
        server.start()
        try {
            val config = testConfig { it.setStructured("backends", mapOf("spawn" to mapOf("base-url" to "http://127.0.0.1:${server.address.port}", "token" to "test-token"))) }
            StaffCommandDispatcher(mockk(), config).use { dispatcher ->
                dispatcher.execute(requestId, StaffActor(identity, true) { true }, StaffCommands.parse("command spawn list")!!)
                    .get(5, TimeUnit.SECONDS) shouldBe StaffExecutionResult(true, listOf("Players: 3"))
                received.get(5, TimeUnit.SECONDS)
            }
        } finally { server.stop(0) }
    }

    "both providers share the same linked-player command rate limit" {
        val config = testConfig()
        val identity = StaffIdentity(UUID.randomUUID(), "Admin")
        val calls = AtomicInteger()
        StaffCommandService(config, Files.createTempDirectory("staff-rate"), { _, _ -> identity }, {
            CompletableFuture.completedFuture(StaffActor(identity, true) { true })
        }, { _, _, _ -> calls.incrementAndGet(); CompletableFuture.completedFuture(StaffExecutionResult(true, emptyList())) },
            clock = { 20_000 }).use { service ->
            service.execute(StaffRequest("discord:100", "discord", "100", 20_000, "command proxy list")).get(5, TimeUnit.SECONDS)
            service.execute(StaffRequest("telegram:200:1", "telegram", "200", 20_000, "command proxy list")).get(5, TimeUnit.SECONDS) shouldBe config.message("rate-limited")
            calls.get() shouldBe 1
        }
    }

    "staff destinations cannot overlap public sync and a console channel may contain multiple labelled origins" {
        val command = "11111111111111111"
        val console = "22222222222222222"
        val config = testConfig { cfg ->
            cfg.setString("discord.guild-id", "33333333333333333")
            cfg.setString("discord.command-channel-id", command)
            cfg.setStructured("discord.admin-role-ids", listOf("44444444444444444"))
            cfg.setStructured("discord.console-channels", mapOf("spawn" to console, "survival" to console))
        }
        config.validate()
        shouldThrow<IllegalArgumentException> { config.validate(setOf(command)) }
        shouldThrow<IllegalArgumentException> { config.validate(setOf(console)) }
    }

    "backend token file stays under data root and rejects symlinks and traversal" {
        val root = Files.createTempDirectory("staff-token")
        Files.createDirectories(root.resolve("secrets"))
        Files.writeString(root.resolve("secrets/backend.token"), "test-only-token\n")
        val cfg = ConfigManager.of(root, "staff.yml")
        cfg.setStructured("backends", mapOf("spawn" to mapOf("base-url" to "http://127.0.0.1:25833", "token-file" to "secrets/backend.token")))
        StaffBridgeConfig(cfg, root).also { it.validate() }.backends["spawn"]?.token shouldBe "test-only-token"
        cfg.setStructured("backends", mapOf("spawn" to mapOf("base-url" to "http://127.0.0.1:25833", "token-file" to "../outside")))
        shouldThrow<IllegalArgumentException> { StaffBridgeConfig(cfg, root) }
        Files.createSymbolicLink(root.resolve("secrets/link.token"), root.resolve("secrets/backend.token"))
        cfg.setStructured("backends", mapOf("spawn" to mapOf("base-url" to "http://127.0.0.1:25833", "token-file" to "secrets/link.token")))
        shouldThrow<IllegalArgumentException> { StaffBridgeConfig(cfg, root) }
    }

    "privacy denies a helper view override and an everyone channel, permits native Discord administrators" {
        val channel = mockk<TextChannel>()
        val guild = mockk<Guild>()
        val everyone = mockk<Role>()
        val bot = mockk<SelfMember>()
        val deny = mockk<PermissionOverride>()
        val extra = mockk<PermissionOverride>()
        val extraRole = mockk<Role>()
        every { channel.guild } returns guild
        every { guild.publicRole } returns everyone
        every { guild.selfMember } returns bot
        every { bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND) } returns true
        every { channel.getPermissionOverride(everyone) } returns deny
        every { deny.denied } returns EnumSet.of(Permission.VIEW_CHANNEL)
        every { deny.allowed } returns EnumSet.noneOf(Permission::class.java)
        every { channel.permissionOverrides } returns listOf(deny, extra)
        every { extra.allowed } returns EnumSet.of(Permission.VIEW_CHANNEL)
        every { extra.isRoleOverride } returns true
        every { extra.id } returns "helper-role"
        every { extra.role } returns extraRole
        every { extraRole.hasPermission(Permission.ADMINISTRATOR) } returns false
        StaffDiscordPrivacy.privateChannel(channel, setOf("admin-role")) shouldBe false
        every { extra.id } returns "admin-role"
        StaffDiscordPrivacy.privateChannel(channel, setOf("admin-role")) shouldBe true
        every { extra.id } returns "native-admin-role"
        every { extraRole.hasPermission(Permission.ADMINISTRATOR) } returns true
        StaffDiscordPrivacy.privateChannel(channel, setOf("admin-role")) shouldBe true
        every { deny.denied } returns EnumSet.noneOf(Permission::class.java)
        StaffDiscordPrivacy.privateChannel(channel, setOf("admin-role")) shouldBe false
    }

    "spy wire codecs reject duplicate fields, foreign shape, oversized and sensitive console entries" {
        val id = UUID.randomUUID().toString()
        val event = """{"v":1,"id":"$id","type":"command","senderUuid":"$id","senderName":"Player","content":"/list","createdAt":12345}"""
        StaffSpyCodecs.spy.decode(event).content shouldBe "/list"
        shouldThrow<IllegalArgumentException> { StaffSpyCodecs.spy.decode(event.replace("\"v\":1", "\"v\":1,\"v\":1")) }
        shouldThrow<IllegalArgumentException> { StaffSpyCodecs.spy.decode(event.replace("/list", "a".repeat(2049))) }
        shouldThrow<IllegalArgumentException> { StaffSpyCodecs.spy.decode(event.replace("/list", "/list\\u2028stop")) }
        shouldThrow<IllegalArgumentException> { StaffSpyCodecs.spy.decode(event.replace("\"type\":\"command\"", "\"type\":\"unknown\"")) }
        val console = """{"v":1,"id":"$id","actor":"CONSOLE","command":"login secret","createdAt":12345}"""
        shouldThrow<IllegalArgumentException> { StaffSpyCodecs.console.decode(console) }
        StaffSpyCodecs.current(1, 200_000) shouldBe false
        StaffSpyCodecs.current(200_000, 200_000) shouldBe true
        StaffDiscordGateway.literal("hi``` @everyone") shouldContain "`\u200b``"
    }
})
