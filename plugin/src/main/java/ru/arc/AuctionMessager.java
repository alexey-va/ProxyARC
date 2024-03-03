package ru.arc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class AuctionMessager implements ChannelListener {
    public final String channelPartial;
    public final String channelAll;
    public final DiscordBot discordBot;

    Map<UUID, AuctionItemDto> map = new ConcurrentHashMap<>();

    @Override
    public void consume(String channel, String message, String originServer) {
        if (channel.equals(channelAll)) {
            map.clear();
        }

        Gson gson = new Gson();
        Type listType = new TypeToken<List<AuctionItemDto>>() {
        }.getType();
        List<AuctionItemDto> auctionItemDtos = gson.fromJson(message, listType);

        for (AuctionItemDto auctionItemDto : auctionItemDtos) {
            try {
                if (auctionItemDto.exist) {
                    map.put(UUID.fromString(auctionItemDto.uuid), auctionItemDto);
                } else {
                    map.remove(UUID.fromString(auctionItemDto.uuid));
                }
            } catch (Exception e) {
                System.out.println("Error: " + auctionItemDto);
                e.printStackTrace();
            }
        }

        List<AuctionItemDto> dtos = map.values().stream()
                .sorted(Comparator.comparingInt(dto -> dto.priority))
                .toList();

        if (discordBot != null) discordBot.updateAuctionItems(dtos);
        else {
            System.out.println("Discord bot is null!");
        }
    }
}
