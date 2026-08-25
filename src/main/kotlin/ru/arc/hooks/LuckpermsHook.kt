package ru.arc.hooks

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.user.UserDataRecalculateEvent
import net.luckperms.api.query.QueryOptions
import ru.arc.discord.DiscordRoleFacts
import ru.arc.discord.DiscordRolePolicyRule
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckpermsHook {
    internal fun subscribeUserDataRecalculation(
        plugin: Any,
        handler: (UUID) -> Unit,
    ): AutoCloseable =
        LuckPermsProvider.get().eventBus.subscribe(plugin, UserDataRecalculateEvent::class.java) { event ->
            handler(event.user.uniqueId)
        }

    fun getMeta(uuid: UUID, key: String): CompletableFuture<String?> {
        val userManager = LuckPermsProvider.get().userManager
        return userManager.loadUser(uuid)
            .thenApply { user -> user.cachedData.metaData.getMetaValue(key) }
    }

    internal fun getDiscordRoleFacts(
        uuid: UUID,
        rules: Collection<DiscordRolePolicyRule>,
    ): CompletableFuture<DiscordRoleFacts> {
        val userManager = LuckPermsProvider.get().userManager
        return userManager.loadUser(uuid).thenApply { user ->
            val groups =
                user.getInheritedGroups(QueryOptions.nonContextual())
                    .mapTo(linkedSetOf()) { it.name.lowercase(Locale.ROOT) }
            groups += user.primaryGroup.lowercase(Locale.ROOT)
            val permissionData = user.cachedData.permissionData
            val permissions =
                rules.asSequence()
                    .flatMap { it.permissions.asSequence() }
                    .distinct()
                    .filter { permissionData.checkPermission(it).asBoolean() }
                    .toCollection(linkedSetOf())
            DiscordRoleFacts(groups, permissions)
        }
    }
}
