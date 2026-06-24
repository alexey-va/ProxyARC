package ru.arc.xserver;

public interface ChannelListener {

    void consume(String channel, String message, String originServer);

}
