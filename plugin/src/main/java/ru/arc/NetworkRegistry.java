package ru.arc;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;


    public void init(){

        AuctionMessager auctionMessager = new AuctionMessager("arc.auction_items", "arc.auction_items_all", DiscordBot.instance);
        redisManager.registerChannel(auctionMessager.channelPartial, auctionMessager);
        redisManager.registerChannel(auctionMessager.channelAll, auctionMessager);

        redisManager.init();
    }

}
