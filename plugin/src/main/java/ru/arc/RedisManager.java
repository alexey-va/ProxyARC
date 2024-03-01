package ru.arc;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisManager extends JedisPubSub {

    JedisPooled sub;
    JedisPooled pub;
    ExecutorService executorService;

    Map<String, List<ChannelListener>> channelListeners = new ConcurrentHashMap<>();
    Set<String> channelList = new HashSet<>();

    private static final String SERVER_DELIMITER = "<>#<>#<>";

    public RedisManager(String ip, int port, String userName, String password) {
        sub = new JedisPooled(ip, port, userName, password);
        pub = new JedisPooled(ip, port, userName, password);
        executorService = Executors.newCachedThreadPool();
        System.out.println("Setting up redis...");
    }

    @Override
    public void onMessage(String channel, String message) {
        //System.out.println(channel+" "+message);
        if(!channelListeners.containsKey(channel)){
            System.out.println("No listener for "+channel);
            return;
        }
        String[] strings = message.split(SERVER_DELIMITER, 2);
        if(strings.length == 1) channelListeners.get(channel).forEach((listener) -> listener.consume(channel, strings[0], null));
        else channelListeners.get(channel).forEach((listener) -> listener.consume(channel, strings[1], strings[0]));
    }

    public void registerChannel(String channel, ChannelListener channelListener) {
        if (!channelListeners.containsKey(channel)) channelListeners.put(channel, new ArrayList<>());
        channelListeners.get(channel).add(channelListener);
        channelList.add(channel);
    }

    public void init(){
        executorService.execute(() -> sub.subscribe(this, channelList.toArray(String[]::new)));
    }

    public void onSubscribe(String channel, int subscribedChannels) {
        System.out.println(channel+" subbed!");
    }

    public void publish(String channel, String message) {
        //System.out.println("Publishing: "+channel+" | "+message);
        executorService.execute(() -> pub.publish(channel,
                ConfigManager.get("config").string("server-name", "proxy")+SERVER_DELIMITER+message));
    }

}
