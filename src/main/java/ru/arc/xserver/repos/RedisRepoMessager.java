package ru.arc.xserver.repos;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import ru.arc.CommonCore;
import ru.arc.xserver.ChannelListener;
import ru.arc.xserver.RedisManager;

@RequiredArgsConstructor
@Log4j2
public class RedisRepoMessager implements ChannelListener {
    private final RedisRepo<?> redisRepo;
    private final RedisManager redisManager;
    @Override
    public void consume(String channel, String message, String originServer) {
        if(originServer.equals(CommonCore.config.string("server-name","proxy"))) return;
        redisRepo.receiveUpdate(message);
    }

    public void send(String channel, String message){
        redisManager.publish(channel, message);
    }
}
