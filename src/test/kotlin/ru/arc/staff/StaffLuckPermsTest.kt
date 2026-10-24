package ru.arc.staff

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.context.ContextManager
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import ru.arc.discord.DiscordRolePolicyRule
import ru.arc.hooks.LuckpermsHook
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class StaffLuckPermsTest : FreeSpec({
    "an admin membership in another context and an orphaned primary label grant neither staff nor Discord log access" {
        val fixture = LuckPermsFixture()
        // Only the proxy's effective context excludes the unrelated server's admin and VIP grants.
        every { fixture.user.getInheritedGroups(any()) } returns listOf(group("admin"))
        every { fixture.user.getInheritedGroups(fixture.staticOptions) } returns listOf(group("default"))
        every { fixture.user.primaryGroup } returns "admin"
        fixture.hook.staffActor(fixture.identity, setOf("admin"), setOf("moderator")).join() shouldBe null
        val facts = fixture.hook.getDiscordRoleFacts(fixture.identity.uuid, policies).join()
        facts.groups shouldBe setOf("default")
        facts.permissions shouldBe emptySet()
        policies.filter { it.matches(facts) } shouldBe emptyList()
        verify(exactly = 0) { fixture.user.primaryGroup }
        verify { fixture.cached.getPermissionData(fixture.staticOptions) }
    }

    "an online user's effective query is shared by inherited group and permission checks and revocation is immediate" {
        val fixture = LuckPermsFixture()
        val onlineOptions = mockk<QueryOptions>()
        every { fixture.context.getQueryOptions(fixture.user) } returns Optional.of(onlineOptions)
        every { fixture.user.getInheritedGroups(onlineOptions) } returns listOf(group("admin"))
        every { fixture.cached.getPermissionData(onlineOptions) } returns fixture.permissions
        every { fixture.permissions.checkPermission("litebans.ban") } returns Tristate.TRUE
        val actor = requireNotNull(fixture.hook.staffActor(fixture.identity, setOf("admin"), setOf("moderator")).join())
        actor.admin shouldBe true
        actor.permission("litebans.ban") shouldBe true
        every { fixture.user.getInheritedGroups(onlineOptions) } returns listOf(group("default"))
        every { fixture.permissions.checkPermission("litebans.ban") } returns Tristate.FALSE
        actor.current() shouldBe null
        actor.permission("litebans.ban") shouldBe false
        verify(exactly = 0) { fixture.context.staticQueryOptions }
    }

    "global VIP administration helper and admin-log policies preserve their intended audiences" {
        val fixture = LuckPermsFixture()
        val cases = mapOf(
            setOf("vip") to setOf("vip"),
            setOf("supervip") to setOf("vip"),
            setOf("helper") to setOf("helper"),
            setOf("moderator") to setOf("administration"),
            setOf("admin") to setOf("administration", "admin-logs"),
        )
        cases.forEach { (groups, expectedPolicies) ->
            every { fixture.user.getInheritedGroups(fixture.staticOptions) } returns groups.map(::group)
            val facts = fixture.hook.getDiscordRoleFacts(fixture.identity.uuid, policies).join()
            policies.filter { it.matches(facts) }.map { it.name }.toSet() shouldBe expectedPolicies
        }
        every { fixture.user.getInheritedGroups(fixture.staticOptions) } returns emptyList()
        every { fixture.permissions.checkPermission("arc.vip") } returns Tristate.TRUE
        val facts = fixture.hook.getDiscordRoleFacts(fixture.identity.uuid, policies).join()
        policies.filter { it.matches(facts) }.map { it.name }.toSet() shouldBe setOf("vip")
    }
})

private val policies = listOf(
    DiscordRolePolicyRule("vip", "1", true, setOf("vip", "supervip"), setOf("arc.vip")),
    DiscordRolePolicyRule("administration", "2", true, setOf("admin", "moderator"), emptySet()),
    DiscordRolePolicyRule("helper", "3", true, setOf("helper"), emptySet()),
    DiscordRolePolicyRule("admin-logs", "4", true, setOf("admin"), emptySet()),
)

private fun group(name: String): Group = mockk<Group>().also { every { it.name } returns name }

private class LuckPermsFixture {
    val identity = StaffIdentity(UUID.randomUUID(), "Staff")
    val api = mockk<LuckPerms>()
    val manager = mockk<UserManager>()
    val user = mockk<User>()
    val context = mockk<ContextManager>()
    val staticOptions = mockk<QueryOptions>()
    val cached = mockk<CachedDataManager>()
    val permissions = mockk<CachedPermissionData>()
    val hook = LuckpermsHook { api }

    init {
        every { api.userManager } returns manager
        every { api.contextManager } returns context
        every { manager.loadUser(identity.uuid) } returns CompletableFuture.completedFuture(user)
        every { manager.getUser(identity.uuid) } returns user
        every { user.uniqueId } returns identity.uuid
        every { user.cachedData } returns cached
        every { user.getInheritedGroups(staticOptions) } returns emptyList()
        every { context.getQueryOptions(user) } returns Optional.empty()
        every { context.staticQueryOptions } returns staticOptions
        every { cached.getPermissionData(staticOptions) } returns permissions
        every { permissions.checkPermission(any()) } returns Tristate.FALSE
    }
}
