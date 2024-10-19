package ru.arc.discord;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import ru.arc.CommonCore;
import ru.arc.Utils;
import ru.arc.auction.AuctionItemDto;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;

import java.awt.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.dv8tion.jda.api.requests.GatewayIntent.values;
import static ru.arc.Utils.mm;
import static ru.arc.Utils.plain;

@Slf4j
public class DiscordBot {

    public static DiscordBot instance;

    Config config = ConfigManager.of(CommonCore.folder, "discord.yml");
    Config joinConfig = ConfigManager.of(CommonCore.folder, "join_config.yml");
    JDA jda;
    TextChannel joinChannel;
    TextChannel playerListChannel;
    TextChannel auctionChannel;
    TextChannel chatChannel;
    TextChannel generalChannel;
    ExecutorService service = Executors.newFixedThreadPool(4);

    Map<String, AtomicBoolean> deleteTasks = new ConcurrentHashMap<>();

    DiscordListener discordListener;

    public DiscordBot() {
        String token = config.string("token", "token");
        if (token.equals("token")) {
            System.out.println("Could not initialize discord bot");
            return;
        }
        JDABuilder builder = JDABuilder.createDefault(token);
        builder.disableCache(CacheFlag.VOICE_STATE, CacheFlag.MEMBER_OVERRIDES);
        this.jda = builder
                .enableIntents(Arrays.asList(values()))
                .build();
        service.submit(() -> {
            try {
                this.jda.awaitReady();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Discord bot is ready!");
            jda.getTextChannels().forEach(
                    channel -> System.out.println(channel.getName() + " " + channel.getId())
            );
            jda.getGuilds().forEach(
                    guild -> System.out.println(guild.getName() + " " + guild.getId())
            );
            try {
                joinChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.join-messages", "none"));
                System.out.println("Join: " + joinChannel);
            } catch (Exception e) {
                log.error("Join channel not found", e);
            }
            try {
                playerListChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.player-list", "none"));
                System.out.println("Player list: " + playerListChannel);
            } catch (Exception e) {
                log.error("Player list channel not found", e);
            }
            try {
                auctionChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.auction", "none"));
                System.out.println("Auction: " + auctionChannel);
            } catch (Exception e) {
                log.error("Auction channel not found", e);
            }
            try {
                chatChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.chat", "none"));
                System.out.println("Chat: " + chatChannel);
            } catch (Exception e) {
                log.error("Chat channel not found", e);
            }
            try {
                generalChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.general", "none"));
                System.out.println("General: " + generalChannel);
            } catch (Exception e) {
                log.error("General channel not found", e);
            }

            discordListener = new DiscordListener(chatChannel, generalChannel);
            jda.addEventListener(discordListener);
        });


        instance = this;
    }

    public void updateAuctionItems(List<AuctionItemDto> auctionItemDtos) {
        if (auctionChannel == null) {
            System.out.println("Auction channel is null! SKipping");
            return;
        }
        //int currentSize =auctionItemDtos.size();

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle(config.string("auction.title", "Предметы на аукционе")
                .replace("%amount%", auctionItemDtos.size() + ""));
        embedBuilder.setColor(Color.GREEN); // You can change the color as per your preference

        // Add fields for each AuctionItemDto
        int count = 0;
        for (int i = 0; i < auctionItemDtos.size(); i++) {
            AuctionItemDto item = auctionItemDtos.get(i);
            embedBuilder.addField(item.getAmount() + " x " + item.getDisplay() + "\u2003\u2003\u2003", getItemDescription(item), true);
            count++;
            if (count >= 3 && i < auctionItemDtos.size() - 1) {
                embedBuilder.addField("\u200B", "\u200B", false);
                count = 0;
            }
        }

        // Build the MessageEmbed
        MessageEmbed embed = embedBuilder.setTimestamp(OffsetDateTime.now()).build();
        List<Message> messageHistory = auctionChannel.getHistory().retrievePast(1).complete();

        String latestId = auctionChannel.getLatestMessageId();
        if (messageHistory.isEmpty() || !messageHistory.get(0).getId().equals(latestId)) {
            // If there are no messages, send a new embed
            auctionChannel.sendMessageEmbeds(embed).queue();
        } else {
            // If there are messages, edit the latest message
            auctionChannel.editMessageEmbedsById(latestId, embed).queue();
        }
    }

    private String getItemDescription(AuctionItemDto item) {
        // Customize the format of the item description as per your preference

        return config.string("auction.description",
                        "Seller: %seller%\nPrice: %price%\nExpire: %expire%\nCategory: %category%")
                .replace("%seller%", item.getSeller())
                .replace("%price%", item.getPrice())
                .replace("%expire%", Utils.formatTime(item.getExpire() - System.currentTimeMillis()))
                .replace("%category%", item.getCategory());
    }

    public void clearChat(String id) {
        if (deleteTasks.containsKey(id)) {
            deleteTasks.get(id).set(false);
        }
        deleteTasks.put(id, new AtomicBoolean(true));
        ForkJoinPool.commonPool().submit(() -> {
            Channel channel = jda.getGuildChannelById(id);
            if (channel instanceof TextChannel textChannel) {
                Deque<Message> deque = new ArrayDeque<>();
                for (Message message : textChannel.getIterableHistory()) {
                    if (!deleteTasks.containsKey(id) || !deleteTasks.get(id).get()) {
                        System.out.println("Interrupted clear chat task");
                        return;
                    }
                    deque.add(message);
                    if (deque.size() >= 5) {
                        deque.pollFirst().delete().queue();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            System.out.println("Interrupted clear chat task");
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        });
    }

    public void stopClearTask(String id) {
        if (deleteTasks.containsKey(id)) {
            System.out.println("Stopping clear task for " + id);
            deleteTasks.get(id).set(false);
        }
    }

    public void updatePlayerList(Collection<String> players) {
        if (playerListChannel == null) {
            System.out.println("Player list channel is null! SKipping");
            return;
        }
        int maxPlayers = config.integer("player-list.max-players", 100);
        int current = players.size();
        String author = config.string("player-list.title", "Игроки на сервере (%amount%/%max%)")
                .replace("%amount%", current + "")
                .replace("%max%", maxPlayers + "");
        MessageEmbed embed = new EmbedBuilder()
                .setColor(Color.GREEN)
                .setAuthor(author)
                .setDescription(String.join("\n", players))
                .setTimestamp(OffsetDateTime.now())
                .build();

        List<Message> messageHistory = playerListChannel.getHistory().retrievePast(10).complete();

        String latestId = playerListChannel.getLatestMessageId();
        if (messageHistory.isEmpty() || !messageHistory.get(0).getId().equals(latestId)) {
            // If there are no messages, send a new embed
            playerListChannel.sendMessageEmbeds(embed).queue();
        } else {
            // If there are messages, edit the latest message
            playerListChannel.editMessageEmbedsById(latestId, embed).queue();
        }
    }

    public void sendChatMessage(String message) {
        if (chatChannel == null) {
            System.out.println("Chat channel is null! Skipping");
            return;
        }
        chatChannel.sendMessage(message).queue();
    }

    public void sendJoinEmbed(String playerName, JoinType joinType, String override) {
        if (joinChannel == null) {
            System.out.println("Join channel is null! Skipping");
            return;
        }
        if (override != null) override = plain(mm(override));
        ColoredTitle coloredTitle = getTitle(playerName, joinType, override);
        String url = joinConfig.string("discord.url", "https://rus-crafting.ru");
        String icon = joinConfig.string("discord.icon", "https://cravatar.eu/helmavatar/%player_name%/128.png")
                .replace("%player_name%", playerName);
        MessageEmbed embed = new EmbedBuilder()
                .setColor(coloredTitle.color)
                .setAuthor(coloredTitle.title, url, icon)
                .setTimestamp(OffsetDateTime.now())
                .build();
        joinChannel.sendMessageEmbeds(embed).queue();
    }

    public void sendGeneralMessage(String message) {
        if (generalChannel == null) {
            System.out.println("General channel is null! Skipping");
            return;
        }
        generalChannel.sendMessage(message).queue();
    }

    public record ColoredTitle(Color color, String title) {
    }

    private ColoredTitle getTitle(String playerName, JoinType joinType, String override) {
        String title = "";
        Color color = Color.GRAY;

        if (joinType == JoinType.FIRST_TIME) {
            String text = override == null ? config.string("discord.first-time.message", "Игрок %player_name% впервые на сервере!") : override;
            color = Color.decode(config.string("discord.first-time.color", "#0000ff"));
            title = text.replace("%player_name%", playerName);
        } else if (joinType == JoinType.JOIN) {
            String text = override == null ? config.string("discord.join.message", "Игрок %player_name% присоединился к серверу!") : override;
            color = Color.decode(config.string("discord.join.color", "#00ff00"));
            title = text.replace("%player_name%", playerName);
        } else if (joinType == JoinType.LEAVE) {
            String text = override == null ? config.string("discord.leave.message", "Игрок %player_name% покинул сервер!") : override;
            color = Color.decode(config.string("discord.leave.color", "#ff0000"));
            title = text.replace("%player_name%", playerName);
        }
        return new ColoredTitle(color, title);
    }

    public enum JoinType {
        FIRST_TIME, JOIN, LEAVE
    }
}
