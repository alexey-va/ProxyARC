package ru.arc.xserver;

import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import ru.arc.config.Config;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


@RequiredArgsConstructor
public class PlayerListAnnouncer{



    final Config config;
    final RedisManager redisManager;
    final String channel;

    Gson gson = new Gson();
    Map<UUID, PlayerData> map = new ConcurrentHashMap<>();


    public void addPlayer(UUID uuid, String username, String server){
        map.put(uuid, new PlayerData(username, server, uuid, System.currentTimeMillis()));
    }

    public void updatePlayer(UUID uuid, String username, String server){
        PlayerData data = map.get(uuid);
        if(data != null){
            data.setServer(server);
        } else{
            addPlayer(uuid, username, server);
        }
    }

    public void removePlayer(UUID uuid){
        map.remove(uuid);
    }

    public void removeAllPlayers(){
        map.clear();
    }

    public void announce(){
        CompletableFuture.supplyAsync(() -> gson.toJson(map.values()))
                        .thenAccept(json -> redisManager.publish(channel, json));
    }


    @Data
    @AllArgsConstructor
    static class PlayerData{
        String username, server;
        UUID uuid;
        long joinTime;
    }

}
