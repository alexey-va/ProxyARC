package ru.arc

import net.kyori.adventure.text.Component

interface Arc {
    fun sendMessageToAll(component: Component)
}
