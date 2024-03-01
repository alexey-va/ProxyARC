package ru.arc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
public class AuctionMessager implements ChannelListener{
    public final String channel;
    public final DiscordBot discordBot;
    @Override
    public void consume(String channel, String message, String originServer) {
        Gson gson = new Gson();
        Type listType = new TypeToken<List<AuctionItemDto>>(){}.getType();
        List<AuctionItemDto> auctionItemDtos = gson.fromJson(message, listType);
        auctionItemDtos.sort(Comparator.comparingInt(dto -> dto.priority));
        if (discordBot != null) discordBot.updateAuctionItems(auctionItemDtos);
        else {
            System.out.println("Discord bot is null!");
        }
    }
}
