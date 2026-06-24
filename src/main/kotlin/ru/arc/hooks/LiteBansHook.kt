package ru.arc.hooks

import litebans.api.Database
import java.util.UUID

class LiteBansHook {
    fun isMuted(uuid: UUID, ip: String): Boolean =
        Database.get().isPlayerMuted(uuid, ip)

    fun isBanned(uuid: UUID, ip: String): Boolean =
        Database.get().isPlayerBanned(uuid, ip)
}
