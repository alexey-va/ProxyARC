package ru.arc.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel

internal data class DiscordChannels(
    val join: TextChannel?,
    val playerList: TextChannel?,
    val auction: TextChannel?,
    val chat: TextChannel,
    val general: TextChannel,
    val issueTickets: Channel?,
)

internal data class DiscordSessionSnapshot(
    val jda: JDA,
    val channels: DiscordChannels,
)

internal class DiscordSession {
    @Volatile
    private var snapshot: DiscordSessionSnapshot? = null

    fun activate(
        jda: JDA,
        channels: DiscordChannels,
    ) {
        snapshot = DiscordSessionSnapshot(jda, channels)
    }

    fun deactivate() {
        snapshot = null
    }

    fun snapshot(): DiscordSessionSnapshot? = snapshot

    fun jda(): JDA? = snapshot?.jda

    fun isReady(): Boolean = snapshot != null
}
