package ru.arc;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.awt.*;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiscordBot {

    public static DiscordBot instance;

    Config config;
    JDA jda;

    TextChannel joinChannel;
    TextChannel playerListChannel;
    TextChannel auctionChannel;

    ExecutorService service = Executors.newFixedThreadPool(4);

    public DiscordBot(Config config) {
        this.config = config;
        String token = config.string("token", "token");
        if (token.equals("token")) {
            System.out.println("Could not initialize discord bot");
            return;
        }
        JDABuilder builder = JDABuilder.createDefault(token);
        builder.disableCache(CacheFlag.VOICE_STATE, CacheFlag.MEMBER_OVERRIDES);
        this.jda = builder.build();
        service.submit(() -> {
            try {
                this.jda.awaitReady();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                joinChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.join-messages", "none"));
                System.out.println("Join: "+joinChannel);
            } catch (Exception e) {
                System.out.println("Join channel not found!");
                e.printStackTrace();
            }
            try {
                playerListChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.player-list", "none"));
                System.out.println("Player list: "+playerListChannel);
            } catch (Exception e) {
                System.out.println("Player list channel not found!");

                e.printStackTrace();
            }
            try {
                auctionChannel = (TextChannel) jda.getGuildChannelById(config.string("channels.auction", "none"));
                System.out.println("Auction: "+auctionChannel);
            } catch (Exception e) {
                System.out.println("Auction channel not found!");
                e.printStackTrace();
            }
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
                .replace("%amount%", auctionItemDtos.size()+""));
        embedBuilder.setColor(Color.GREEN); // You can change the color as per your preference

        // Add fields for each AuctionItemDto
        int count = 0;
        for (int i = 0; i < auctionItemDtos.size(); i++) {
            AuctionItemDto item = auctionItemDtos.get(i);
            embedBuilder.addField(item.getAmount()+" x "+item.getDisplay()+"\u2003\u2003\u2003", getItemDescription(item), true);
            count++;
            if(count >=3 && i < auctionItemDtos.size()-1){
                embedBuilder.addField("\u200B", "\u200B", false);
                count=0;
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
                .replace("%seller%", item.seller)
                .replace("%price%", item.price)
                .replace("%expire%", Utils.formatTime(item.expire-System.currentTimeMillis()))
                .replace("%category%", item.category);
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

    public void sendJoinEmbed(String playerName, JoinType joinType) {
        if (joinChannel == null) {
            System.out.println("Join channel is null! SKipping");
            return;
        }
        ColoredTitle coloredTitle = getTitle(playerName, joinType);
        String url = config.string("messages.url", "https://rus-crafting.ru");
        String icon = config.string("messages.icon", "https://cravatar.eu/helmavatar/%player_name%/128.png")
                .replace("%player_name%", playerName);
        MessageEmbed embed = new EmbedBuilder()
                .setColor(coloredTitle.color)
                .setAuthor(coloredTitle.title, url, icon)
                .setTimestamp(OffsetDateTime.now())
                .build();
        joinChannel.sendMessageEmbeds(embed).queue();
    }

    public record ColoredTitle(Color color, String title) {
    }

    private ColoredTitle getTitle(String playerName, JoinType joinType) {
        String title = "";
        Color color = Color.GRAY;
        if (joinType == JoinType.FIRST_TIME) {
            color = Color.decode(config.string("messages.first-time.color", "#0000ff"));
            title = config.string("messages.first-time.message", "Игрок %player_name% впервые на сервере!")
                    .replace("%player_name%", playerName);
        } else if (joinType == JoinType.JOIN) {
            color = Color.decode(config.string("messages.join.color", "#00ff00"));
            title = config.string("messages.join.message", "Игрок %player_name% присоединился к серверу!")
                    .replace("%player_name%", playerName);
        } else if (joinType == JoinType.LEAVE) {
            color = Color.decode(config.string("messages.leave.color", "#ff0000"));
            title = config.string("messages.leave.message", "Игрок %player_name% покинул сервер!")
                    .replace("%player_name%", playerName);
        }
        return new ColoredTitle(color, title);
    }

    public enum JoinType {
        FIRST_TIME, JOIN, LEAVE
    }
}
