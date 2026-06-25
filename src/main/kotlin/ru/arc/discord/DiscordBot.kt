package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import ru.arc.velocity.Velocity
import ru.arc.Utils
import ru.arc.Utils.mm
import ru.arc.Utils.plain
import ru.arc.auction.AuctionItemDto
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.awt.Color
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Deque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean

class DiscordBot {

    private val log = LoggerFactory.getLogger(DiscordBot::class.java)

    private val config: Config get() = ProxyConfigs.module("discord.yml")
    private val joinConfig: Config get() = ProxyConfigs.module("join_config.yml")
    private var jda: JDA? = null
    private var joinChannel: TextChannel? = null
    private var playerListChannel: TextChannel? = null
    private var auctionChannel: TextChannel? = null
    private var chatChannel: TextChannel? = null
    private var generalChannel: TextChannel? = null
    private val service: ExecutorService = Executors.newFixedThreadPool(4)
    private val deleteTasks: MutableMap<String, AtomicBoolean> = ConcurrentHashMap()
    private var discordListener: DiscordListener? = null
    private var isEnabled: Boolean = false
    @Volatile
    private var channelsReady: Boolean = false

    fun isReady(): Boolean = isEnabled && channelsReady

    init {
        try {
            if (config.bool("enabled", false)) {
                val token = config.string("token", "token")
                if (token != "token") {
                    val builder = JDABuilder.createDefault(token)
                    builder.disableCache(CacheFlag.VOICE_STATE, CacheFlag.MEMBER_OVERRIDES)
                    jda = builder
                        .enableIntents(Arrays.asList(*GatewayIntent.values()))
                        .build()
                    service.submit {
                        try {
                            jda!!.awaitReady()
                        } catch (e: InterruptedException) {
                            throw RuntimeException(e)
                        }
                        println("Discord bot is ready!")
                        jda!!.textChannels.forEach { channel ->
                            println("${channel.name} ${channel.id}")
                        }
                        jda!!.guilds.forEach { guild ->
                            println("${guild.name} ${guild.id}")
                        }
                        try {
                            joinChannel = jda!!.getGuildChannelById(config.string("channels.join-messages", "none")) as TextChannel?
                            println("Join: $joinChannel")
                        } catch (e: Exception) {
                            log.error("Join channel not found", e)
                        }
                        try {
                            playerListChannel = jda!!.getGuildChannelById(config.string("channels.player-list", "none")) as TextChannel?
                            println("Player list: $playerListChannel")
                        } catch (e: Exception) {
                            log.error("Player list channel not found", e)
                        }
                        try {
                            auctionChannel = jda!!.getGuildChannelById(config.string("channels.auction", "none")) as TextChannel?
                            println("Auction: $auctionChannel")
                        } catch (e: Exception) {
                            log.error("Auction channel not found", e)
                        }
                        try {
                            chatChannel = jda!!.getGuildChannelById(config.string("channels.chat", "none")) as TextChannel?
                            println("Chat: $chatChannel")
                        } catch (e: Exception) {
                            log.error("Chat channel not found", e)
                        }
                        try {
                            generalChannel = jda!!.getGuildChannelById(config.string("channels.general", "none")) as TextChannel?
                            println("General: $generalChannel")
                        } catch (e: Exception) {
                            log.error("General channel not found", e)
                        }

                        discordListener = DiscordListener(chatChannel!!, generalChannel!!)
                        jda!!.addEventListener(discordListener)
                        channelsReady = true
                    }
                    isEnabled = true
                } else {
                    println("Could not initialize discord bot")
                }
            } else {
                println("Discord bot is disabled in config")
            }
        } catch (e: Exception) {
            log.error("Discord bot initialization failed", e)
        }
        instance = this
    }

    fun updateAuctionItems(auctionItemDtos: List<AuctionItemDto>) {
        if (!isEnabled) return

        if (auctionChannel == null) {
            println("Auction channel is null! SKipping")
            return
        }

        val embedBuilder = EmbedBuilder()
        embedBuilder.setTitle(
            config.string("auction.title", "Предметы на аукционе")
                .replace("%amount%", auctionItemDtos.size.toString()),
        )
        embedBuilder.setColor(Color.GREEN)

        var count = 0
        for (i in auctionItemDtos.indices) {
            val item = auctionItemDtos[i]
            embedBuilder.addField(
                "${item.amount} x ${item.display}\u2003\u2003\u2003",
                getItemDescription(item),
                true,
            )
            count++
            if (count >= 3 && i < auctionItemDtos.size - 1) {
                embedBuilder.addField("\u200B", "\u200B", false)
                count = 0
            }
        }

        val embed: MessageEmbed = embedBuilder.setTimestamp(OffsetDateTime.now()).build()
        val messageHistory: List<Message> = auctionChannel!!.history.retrievePast(1).complete()

        val latestId = auctionChannel!!.latestMessageId
        if (messageHistory.isEmpty() || messageHistory[0].id != latestId) {
            auctionChannel!!.sendMessageEmbeds(embed).queue()
        } else {
            auctionChannel!!.editMessageEmbedsById(latestId, embed).queue()
        }
    }

    private fun getItemDescription(item: AuctionItemDto): String =
        config.string(
            "auction.description",
            "Seller: %seller%\nPrice: %price%\nExpire: %expire%\nCategory: %category%",
        )
            .replace("%seller%", item.seller ?: "")
            .replace("%price%", item.price ?: "")
            .replace("%expire%", Utils.formatTime(item.expire - System.currentTimeMillis()))
            .replace("%category%", item.category ?: "")

    fun clearChat(id: String) {
        if (!isEnabled) return
        if (deleteTasks.containsKey(id)) {
            deleteTasks[id]!!.set(false)
        }
        deleteTasks[id] = AtomicBoolean(true)
        ForkJoinPool.commonPool().submit {
            val channel: Channel? = jda!!.getGuildChannelById(id)
            if (channel is TextChannel) {
                val deque: Deque<Message> = ArrayDeque()
                for (message in channel.iterableHistory) {
                    if (!deleteTasks.containsKey(id) || !deleteTasks[id]!!.get()) {
                        println("Interrupted clear chat task")
                        return@submit
                    }
                    deque.add(message)
                    if (deque.size >= 5) {
                        deque.pollFirst().delete().queue()
                        try {
                            Thread.sleep(5000)
                        } catch (e: InterruptedException) {
                            println("Interrupted clear chat task")
                            throw RuntimeException(e)
                        }
                    }
                }
            }
        }
    }

    fun stopClearTask(id: String) {
        if (deleteTasks.containsKey(id)) {
            println("Stopping clear task for $id")
            deleteTasks[id]!!.set(false)
        }
    }

    fun updatePlayerList(players: Collection<String>) {
        if (!isEnabled || !channelsReady || playerListChannel == null) return
        val maxPlayers = config.integer("player-list.max-players", 100)
        val current = players.size
        val author = config.string("player-list.title", "Игроки на сервере (%amount%/%max%)")
            .replace("%amount%", current.toString())
            .replace("%max%", maxPlayers.toString())
        val embed: MessageEmbed = EmbedBuilder()
            .setColor(Color.GREEN)
            .setAuthor(author)
            .setDescription(players.joinToString("\n"))
            .setTimestamp(OffsetDateTime.now())
            .build()

        val messageHistory: List<Message> = playerListChannel!!.history.retrievePast(10).complete()

        val latestId = playerListChannel!!.latestMessageId
        if (messageHistory.isEmpty() || messageHistory[0].id != latestId) {
            playerListChannel!!.sendMessageEmbeds(embed).queue()
        } else {
            playerListChannel!!.editMessageEmbedsById(latestId, embed).queue()
        }
    }

    fun sendChatMessage(message: String) {
        if (!isEnabled) return
        if (chatChannel == null) {
            println("Chat channel is null! Skipping")
            return
        }
        chatChannel!!.sendMessage(message).queue()
    }

    fun sendJoinEmbed(playerName: String, joinType: JoinType, override: String?) {
        if (!isEnabled) return
        if (joinChannel == null) {
            println("Join channel is null! Skipping")
            return
        }
        var messageOverride = override
        if (messageOverride != null) {
            messageOverride = plain(mm(messageOverride))
        }
        val coloredTitle = getTitle(playerName, joinType, messageOverride)
        val url = joinConfig.string("discord.url", "https://rus-crafting.ru")
        val icon = joinConfig.string("discord.icon", "https://cravatar.eu/helmavatar/%player_name%/128.png")
            .replace("%player_name%", playerName)
        val embed: MessageEmbed = EmbedBuilder()
            .setColor(coloredTitle.color)
            .setAuthor(coloredTitle.title, url, icon)
            .setTimestamp(OffsetDateTime.now())
            .build()
        joinChannel!!.sendMessageEmbeds(embed).queue()
    }

    fun sendGeneralMessage(message: String) {
        if (!isEnabled) return
        if (generalChannel == null) {
            println("General channel is null! Skipping")
            return
        }
        generalChannel!!.sendMessage(message).queue()
    }

    data class ColoredTitle(val color: Color, val title: String)

    enum class JoinType {
        FIRST_TIME,
        JOIN,
        LEAVE,
    }

    private fun getTitle(playerName: String, joinType: JoinType, override: String?): ColoredTitle {
        var title = ""
        var color = Color.GRAY

        when (joinType) {
            JoinType.FIRST_TIME -> {
                val text = override ?: config.string("discord.first-time.message", "Игрок %player_name% впервые на сервере!")
                color = Color.decode(config.string("discord.first-time.color", "#0000ff"))
                title = text.replace("%player_name%", playerName)
            }
            JoinType.JOIN -> {
                val text = override ?: config.string("discord.join.message", "Игрок %player_name% присоединился к серверу!")
                color = Color.decode(config.string("discord.join.color", "#00ff00"))
                title = text.replace("%player_name%", playerName)
            }
            JoinType.LEAVE -> {
                val text = override ?: config.string("discord.leave.message", "Игрок %player_name% покинул сервер!")
                color = Color.decode(config.string("discord.leave.color", "#ff0000"))
                title = text.replace("%player_name%", playerName)
            }
        }
        return ColoredTitle(color, title)
    }

    companion object {
        @JvmField
        var instance: DiscordBot? = null
    }
}
