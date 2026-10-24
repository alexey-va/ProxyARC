package ru.arc.hooks

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.user.UserDataRecalculateEvent
import net.luckperms.api.model.user.User
import net.luckperms.api.query.QueryOptions
import ru.arc.chat.ExternalChatMeta
import ru.arc.discord.DiscordRoleFacts
import ru.arc.discord.DiscordRolePolicyRule
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.staff.StaffActor
import ru.arc.staff.StaffIdentity

class LuckpermsHook(private val luckPerms: () -> LuckPerms = { LuckPermsProvider.get() }) {
    internal fun staffActor(identity: StaffIdentity, adminGroups: Set<String>, moderatorGroups: Set<String>): CompletableFuture<StaffActor?> {
        val api = luckPerms()
        fun currentActor(): StaffActor? {
            val user = api.userManager.getUser(identity.uuid) ?: return null
            if (user.uniqueId != identity.uuid) return null
            val groups = user.getInheritedGroups(effectiveQueryOptions(api, user)).map { it.name.lowercase(Locale.ROOT) }.toSet()
            val admin = groups.any(adminGroups::contains)
            if (!admin && groups.none(moderatorGroups::contains)) return null
            return StaffActor(identity, admin, ::currentActor) { permission ->
                val currentUser = api.userManager.getUser(identity.uuid)
                currentUser?.cachedData?.getPermissionData(effectiveQueryOptions(api, currentUser))
                    ?.checkPermission(permission)?.asBoolean() == true
            }
        }
        return api.userManager.loadUser(identity.uuid).thenApply { currentActor() }
    }

    fun getChatMeta(uuid: UUID): CompletableFuture<ExternalChatMeta> =
        luckPerms().userManager.loadUser(uuid).thenApply { user ->
            val meta = user.cachedData.metaData
            ExternalChatMeta(meta.prefix.orEmpty())
        }

    internal fun subscribeUserDataRecalculation(
        plugin: Any,
        handler: (UUID) -> Unit,
    ): AutoCloseable =
        luckPerms().eventBus.subscribe(plugin, UserDataRecalculateEvent::class.java) { event ->
            handler(event.user.uniqueId)
        }

    fun getMeta(uuid: UUID, key: String): CompletableFuture<String?> {
        val userManager = luckPerms().userManager
        return userManager.loadUser(uuid)
            .thenApply { user -> user.cachedData.metaData.getMetaValue(key) }
    }

    internal fun getDiscordRoleFacts(
        uuid: UUID,
        rules: Collection<DiscordRolePolicyRule>,
    ): CompletableFuture<DiscordRoleFacts> {
        val api = luckPerms()
        return api.userManager.loadUser(uuid).thenApply { user ->
            val options = effectiveQueryOptions(api, user)
            val groups =
                user.getInheritedGroups(options)
                    .mapTo(linkedSetOf()) { it.name.lowercase(Locale.ROOT) }
            val permissionData = user.cachedData.getPermissionData(options)
            val permissions =
                rules.asSequence()
                    .flatMap { it.permissions.asSequence() }
                    .distinct()
                    .filter { permissionData.checkPermission(it).asBoolean() }
                    .toCollection(linkedSetOf())
            DiscordRoleFacts(groups, permissions)
        }
    }

    private fun effectiveQueryOptions(api: LuckPerms, user: User): QueryOptions =
        api.contextManager.getQueryOptions(user).orElseGet { api.contextManager.staticQueryOptions }
}
