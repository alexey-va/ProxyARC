package ru.arc.xserver;

import lombok.RequiredArgsConstructor;
import ru.arc.ai.tools.ToolsMessager;
import ru.arc.discord.DiscordBot;
import ru.arc.auction.AuctionMessager;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;


    public void init(){

        AuctionMessager auctionMessager = new AuctionMessager("arc.auction_items", "arc.auction_items_all", DiscordBot.instance);
        redisManager.registerChannelUnique(auctionMessager.channelPartial, auctionMessager);
        redisManager.registerChannelUnique(auctionMessager.channelAll, auctionMessager);

        ToolsMessager toolsMessager = new ToolsMessager(redisManager, 2);
        redisManager.registerChannelUnique(ToolsMessager.CHANNEL_RESPONSE_TOOLS, toolsMessager);
        ToolsMessager.instance = toolsMessager;

        redisManager.init();
    }

}
