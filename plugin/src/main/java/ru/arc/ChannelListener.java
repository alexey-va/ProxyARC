package ru.arc;

public interface ChannelListener {

    void consume(String channel, String message, String originServer);

}
