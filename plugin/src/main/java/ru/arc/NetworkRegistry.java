package ru.arc;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;


    public void init(){

        AuctionMessager auctionMessager = new AuctionMessager("arc.auction_items", DiscordBot.instance);
        redisManager.registerChannel(auctionMessager.channel, auctionMessager);

        redisManager.init();
    }

}
