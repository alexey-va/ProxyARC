package ru.arc.discord

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import org.slf4j.LoggerFactory
import ru.arc.hooks.LuckpermsHook
import java.util.concurrent.CompletableFuture

internal fun interface DiscordRoleFactsProvider {
    fun load(
        link: DiscordIdentityLink,
        rules: Collection<DiscordRolePolicyRule>,
    ): CompletableFuture<DiscordRoleFacts>
}

internal interface DiscordRoleReconciler {
    fun reconcile(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult>

    fun clearManagedRoles(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult>
}

internal class DiscordRoleService(
    private val session: DiscordSession,
    private val config: DiscordVerificationConfig,
    private val factsProvider: DiscordRoleFactsProvider,
) : DiscordRoleReconciler {
    override fun reconcile(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult> {
        val snapshot = session.snapshot()
            ?: return completed(DiscordRoleReconcileResult.Status.NOT_READY, "discord-not-ready")
        if (!config.enabled) {
            return completed(DiscordRoleReconcileResult.Status.CONFIG_ERROR, "verification-disabled")
        }
        val guild = snapshot.jda.getGuildById(config.guildId)
            ?: return completed(DiscordRoleReconcileResult.Status.CONFIG_ERROR, "guild-not-found")
        val roles = resolveManagedRoles(guild)
            ?: return completed(DiscordRoleReconcileResult.Status.CONFIG_ERROR, "managed-role-not-found")
        val memberFuture = guild.retrieveMemberById(link.discordUserId).submit()
        val factsFuture = factsProvider.load(link, config.policyRules)
        return memberFuture.thenCombine(factsFuture) { member, facts -> member to facts }
            .thenCompose { (member, facts) -> reconcileMember(guild, member, link, facts, roles) }
            .exceptionally { error ->
                log.debug("Discord role reconciliation failed for {}: {}", link.playerUuid, error.javaClass.simpleName)
                DiscordRoleReconcileResult(
                    status = classifyFailure(error),
                    reason = error.javaClass.simpleName,
                )
            }
    }

    override fun clearManagedRoles(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult> {
        val snapshot = session.snapshot()
            ?: return completed(DiscordRoleReconcileResult.Status.NOT_READY, "discord-not-ready")
        val guild = snapshot.jda.getGuildById(config.guildId)
            ?: return completed(DiscordRoleReconcileResult.Status.CONFIG_ERROR, "guild-not-found")
        val roles = resolveExistingManagedRoles(guild)
            ?: return completed(DiscordRoleReconcileResult.Status.CONFIG_ERROR, "managed-role-unmanageable")
        return guild.retrieveMemberById(link.discordUserId).submit()
            .thenCompose { member ->
                val toRemove = member.roles.filter { it.id in roles.keys }
                val ownsNickname = member.nickname == config.nickname(link.playerName)
                if ((toRemove.isNotEmpty() || ownsNickname) && !guild.selfMember.canInteract(member)) {
                    return@thenCompose CompletableFuture.completedFuture(
                        DiscordRoleReconcileResult(
                            DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED,
                            reason = "member-hierarchy",
                        ),
                    )
                }
                val blocked = toRemove.firstOrNull { !guild.selfMember.canInteract(it) }
                if (blocked != null) {
                    return@thenCompose CompletableFuture.completedFuture(
                        DiscordRoleReconcileResult(
                            DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED,
                            reason = "role:${blocked.id}",
                        ),
                    )
                }
                val removeFuture =
                    if (toRemove.isEmpty()) {
                        CompletableFuture.completedFuture(null)
                    } else {
                        guild.modifyMemberRoles(member, emptyList(), toRemove)
                            .reason("RusCrafting account unlink")
                            .submit()
                    }
                removeFuture.thenCompose {
                    clearNickname(guild, member, config.nickname(link.playerName)).thenApply { nicknameChanged ->
                        DiscordRoleReconcileResult(
                            status =
                                if (toRemove.isEmpty() && !nicknameChanged) {
                                    DiscordRoleReconcileResult.Status.UNCHANGED
                                } else {
                                    DiscordRoleReconcileResult.Status.UPDATED
                                },
                            removedRoleIds = toRemove.mapTo(linkedSetOf(), Role::getId),
                            nicknameChanged = nicknameChanged,
                        )
                    }
                }
            }
            .exceptionally { error ->
                val status = classifyFailure(error)
                if (status == DiscordRoleReconcileResult.Status.MEMBER_NOT_FOUND) {
                    DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UNCHANGED)
                } else {
                    DiscordRoleReconcileResult(status, reason = error.javaClass.simpleName)
                }
            }
    }

    fun hierarchyProblems(): List<String> {
        val snapshot = session.snapshot() ?: return listOf("discord-not-ready")
        val guild = snapshot.jda.getGuildById(config.guildId) ?: return listOf("guild-not-found")
        return config.managedRoleIds.mapNotNull { roleId ->
            val role = guild.getRoleById(roleId) ?: return@mapNotNull "missing:$roleId"
            when {
                role.isManaged || role.isPublicRole -> "unmanageable:$roleId"
                !guild.selfMember.canInteract(role) -> "hierarchy:$roleId"
                else -> null
            }
        }
    }

    private fun reconcileMember(
        guild: Guild,
        member: Member,
        link: DiscordIdentityLink,
        facts: DiscordRoleFacts,
        roles: Map<String, Role>,
    ): CompletableFuture<DiscordRoleReconcileResult> {
        val desiredIds = desiredRoleIds(facts)
        val currentManagedIds = member.roles.map(Role::getId).filterTo(linkedSetOf()) { it in roles }
        val addIds = desiredIds - currentManagedIds
        val removeIds = currentManagedIds - desiredIds
        val changedRoles = (addIds + removeIds).mapNotNull(roles::get)
        val nicknameChange = config.nicknameEnabled && member.nickname != config.nickname(link.playerName)
        if ((changedRoles.isNotEmpty() || nicknameChange) && !guild.selfMember.canInteract(member)) {
            return CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(
                    DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED,
                    reason = "member-hierarchy",
                ),
            )
        }
        val blocked = changedRoles.firstOrNull { !guild.selfMember.canInteract(it) }
        if (blocked != null) {
            return CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(
                    DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED,
                    reason = "role:${blocked.id}",
                ),
            )
        }
        val addRoles = addIds.mapNotNull(roles::get)
        val removeRoles = removeIds.mapNotNull(roles::get)
        val rolesFuture =
            if (addRoles.isEmpty() && removeRoles.isEmpty()) {
                CompletableFuture.completedFuture(null)
            } else {
                guild.modifyMemberRoles(member, addRoles, removeRoles)
                    .reason("RusCrafting identity role reconciliation")
                    .submit()
            }
        return rolesFuture.thenCompose {
            applyNickname(guild, member, config.nickname(link.playerName)).thenApply { nicknameChanged ->
                DiscordRoleReconcileResult(
                    status =
                        if (addIds.isEmpty() && removeIds.isEmpty() && !nicknameChanged) {
                            DiscordRoleReconcileResult.Status.UNCHANGED
                        } else {
                            DiscordRoleReconcileResult.Status.UPDATED
                        },
                    addedRoleIds = addIds,
                    removedRoleIds = removeIds,
                    nicknameChanged = nicknameChanged,
                )
            }
        }
    }

    internal fun desiredRoleIds(facts: DiscordRoleFacts): Set<String> =
        buildSet {
            add(config.verifiedRoleId)
            add(config.playerRoleId)
            config.policyRules.filter { it.matches(facts) }.mapTo(this, DiscordRolePolicyRule::roleId)
        }

    private fun resolveManagedRoles(guild: Guild): Map<String, Role>? {
        val roles = config.managedRoleIds.associateWith(guild::getRoleById)
        if (roles.values.any { it == null }) return null
        if (roles.values.filterNotNull().any { it.isManaged || it.isPublicRole }) return null
        return roles.mapValues { requireNotNull(it.value) }
    }

    /** Deleted roles cannot remain on a member, so unlink may safely clear the managed roles that still exist. */
    private fun resolveExistingManagedRoles(guild: Guild): Map<String, Role>? {
        val roles = config.managedRoleIds.mapNotNull(guild::getRoleById).associateBy(Role::getId)
        if (roles.values.any { it.isManaged || it.isPublicRole }) return null
        return roles
    }

    private fun applyNickname(
        guild: Guild,
        member: Member,
        nickname: String,
    ): CompletableFuture<Boolean> {
        if (!config.nicknameEnabled || member.nickname == nickname) {
            return CompletableFuture.completedFuture(false)
        }
        if (nickname.isBlank() || !guild.selfMember.canInteract(member)) {
            return CompletableFuture.failedFuture(IllegalStateException("nickname-hierarchy"))
        }
        return member.modifyNickname(nickname)
            .reason("RusCrafting verified Minecraft identity")
            .submit()
            .thenApply { true }
    }

    private fun clearNickname(
        guild: Guild,
        member: Member,
        managedNickname: String,
    ): CompletableFuture<Boolean> {
        if (!config.nicknameEnabled || member.nickname != managedNickname) {
            return CompletableFuture.completedFuture(false)
        }
        if (!guild.selfMember.canInteract(member)) {
            return CompletableFuture.failedFuture(IllegalStateException("nickname-hierarchy"))
        }
        return member.modifyNickname(null)
            .reason("RusCrafting account unlink")
            .submit()
            .thenApply { true }
    }

    private fun classifyFailure(error: Throwable): DiscordRoleReconcileResult.Status {
        val message = generateSequence(error as Throwable?) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return when {
            "unknown member" in message || "10007" in message -> DiscordRoleReconcileResult.Status.MEMBER_NOT_FOUND
            "nickname-hierarchy" in message || "hierarchy" in message -> DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED
            "luckperms" in message || "provider" in message -> DiscordRoleReconcileResult.Status.PROVIDER_UNAVAILABLE
            else -> DiscordRoleReconcileResult.Status.FAILED
        }
    }

    private fun completed(
        status: DiscordRoleReconcileResult.Status,
        reason: String,
    ): CompletableFuture<DiscordRoleReconcileResult> =
        CompletableFuture.completedFuture(DiscordRoleReconcileResult(status, reason = reason))

    companion object {
        private val log = LoggerFactory.getLogger(DiscordRoleService::class.java)

        fun luckPermsProvider(hookProvider: () -> LuckpermsHook?): DiscordRoleFactsProvider =
            DiscordRoleFactsProvider { link, rules ->
                val hook = hookProvider()
                    ?: return@DiscordRoleFactsProvider CompletableFuture.failedFuture(
                        IllegalStateException("LuckPerms provider unavailable"),
                    )
                hook.getDiscordRoleFacts(link.playerUuid, rules)
            }
    }
}
