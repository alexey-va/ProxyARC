package ru.arc.hooks

import net.luckperms.api.LuckPermsProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckpermsHook {
    fun getMeta(uuid: UUID, key: String): CompletableFuture<String?> {
        val userManager = LuckPermsProvider.get().userManager
        return userManager.loadUser(uuid)
            .thenApply { user -> user.cachedData.metaData.getMetaValue(key) }
    }
}
