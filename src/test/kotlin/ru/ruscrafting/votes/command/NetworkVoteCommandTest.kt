package ru.ruscrafting.votes.command

import com.mojang.brigadier.CommandDispatcher
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class NetworkVoteCommandTest : StringSpec({
    "admin inspection completes players from every proxy backend" {
        val sender = player("Operator")
        val proxy = mockk<ProxyServer>()
        every { proxy.allPlayers } returns listOf(sender, player("RemoteAlice"), player("RemoteBob"))
        val command = NetworkVoteCommand(proxy).command

        suggestions(command, sender, "vote ") shouldBe listOf("check", "history", "status")
        suggestions(command, sender, "vote check remotea") shouldBe listOf("RemoteAlice")
        suggestions(command, sender, "vote history Remote") shouldBe listOf("RemoteAlice", "RemoteBob")
    }

    "proxy suggestions do not depend on proxy-local permission state" {
        val sender = player("Steve")
        val proxy = mockk<ProxyServer>()
        every { sender.hasPermission(any()) } returns false
        every { proxy.allPlayers } returns listOf(sender, player("RemoteAlice"))
        val command = NetworkVoteCommand(proxy).command

        suggestions(command, sender, "vote ") shouldBe listOf("check", "history", "status")
        suggestions(command, sender, "vote check ") shouldBe listOf("RemoteAlice", "Steve")
    }

    "vote execution is forwarded to ArcVotes on the current Paper backend" {
        val sender = player("Operator")
        val command = NetworkVoteCommand(mockk(relaxed = true)).command

        dispatcher(command).execute("vote history RemoteAlice 2", sender) shouldBe BrigadierCommand.FORWARD
    }
})

private fun player(name: String): Player = mockk<Player>().also { player ->
    every { player.username } returns name
}

private fun suggestions(
    command: BrigadierCommand,
    source: CommandSource,
    input: String,
): List<String> {
    val dispatcher = dispatcher(command)
    return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).get().list
        .map { it.text }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
}

private fun dispatcher(command: BrigadierCommand): CommandDispatcher<CommandSource> =
    CommandDispatcher<CommandSource>().also { it.root.addChild(command.node) }
