package ru.arc.hooks;

import litebans.api.Database;

import java.util.UUID;

public class LiteBansHook {

    public boolean isMuted(UUID uuid, String ip) {
        return Database.get().isPlayerMuted(uuid, ip);
    }

    public boolean isBanned(UUID uuid, String ip) {
        return Database.get().isPlayerBanned(uuid, ip);
    }

}
