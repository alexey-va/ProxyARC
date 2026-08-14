package ru.arc.velocity.listeners

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.KickedFromServerEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class RestartKickListener {
    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = false)
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val reason =
            event.serverKickReason
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .orElse("")
                .trim()
        if (!isPlannedRestart(reason)) return

        event.result =
            when (val result = event.result) {
                is KickedFromServerEvent.RedirectPlayer ->
                    KickedFromServerEvent.RedirectPlayer.create(result.server, ONLINE_MESSAGE)

                is KickedFromServerEvent.Notify ->
                    KickedFromServerEvent.Notify.create(ONLINE_MESSAGE)

                is KickedFromServerEvent.DisconnectPlayer ->
                    KickedFromServerEvent.DisconnectPlayer.create(DISCONNECT_MESSAGE)
            }
    }

    companion object {
        private val WARNING = TextColor.color(0xFF9F0F)
        private val BODY = TextColor.color(0xE6FFF3)
        private val MUTED = TextColor.color(0x969696)
        private val ACCENT = TextColor.color(0x92BED8)

        private val TITLE =
            Component.text("⚠ Сервер перезапускается", WARNING, TextDecoration.BOLD)

        private val ONLINE_MESSAGE =
            Component.empty()
                .append(Component.newline())
                .append(Component.text("  "))
                .append(TITLE)
                .append(Component.newline())
                .append(Component.text("  "))
                .append(Component.text("Вы остались в сети на другом сервере.", BODY))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(Component.text("Вернуться можно через ", MUTED))
                .append(Component.text("несколько минут", ACCENT))
                .append(Component.text(".", MUTED))
                .append(Component.newline())

        private val DISCONNECT_MESSAGE =
            Component.empty()
                .append(TITLE)
                .append(Component.newline())
                .append(Component.text("Мы уже запускаем сервер снова.", BODY))
                .append(Component.newline())
                .append(Component.text("Зайдите снова через ", MUTED))
                .append(Component.text("несколько минут", ACCENT))
                .append(Component.text(".", MUTED))

        private fun isPlannedRestart(reason: String): Boolean =
            reason.startsWith("Сервер перезагружается") ||
                reason.startsWith("Сервер перезапускается")
    }
}
