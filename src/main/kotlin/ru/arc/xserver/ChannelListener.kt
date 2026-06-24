package ru.arc.xserver

interface ChannelListener {
    fun consume(channel: String, message: String, originServer: String)
}
