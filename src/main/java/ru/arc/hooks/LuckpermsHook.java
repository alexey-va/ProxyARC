package ru.arc.hooks;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LuckpermsHook {


    public CompletableFuture<String> getMeta(UUID uuid, String key) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        return userManager.loadUser(uuid)
                .thenApply(user -> user.getCachedData().getMetaData().getMetaValue(key));
    }

}
