# Discord identity and role synchronization

`ProxyARC` owns the RusCrafting Discord connection because Velocity is the one
network component that has both the authenticated player session and the JDA
guild session.

## Trust boundary

- A Minecraft verification or recovery challenge can be issued only to an
  active `Player` connected to an allowlisted real backend. A username supplied
  by a command argument is never accepted as identity.
- Discord completion uses the authenticated interaction user snowflake. The
  command never accepts a target Discord user id.
- Challenge codes are single-use, expire, and are stored only as SHA-256
  digests. Raw codes are returned once to the in-game player and never logged.
- Normal verification cannot replace either side of an existing link. Recovery
  is a separate challenge issued from the already linked Minecraft UUID.
- UUID and Discord user id are both unique in the persisted state.

## Ownership

| Owner | Responsibility |
| --- | --- |
| `DiscordConnectionService` | JDA lifecycle, guild/channel resolution, command registration |
| `DiscordChatConfig` | validates and renders the tracked inbound bridge formats |
| `DiscordChatService` | inbound/outbound bridge and allowed-mention enforcement |
| `DiscordTicketService` | forum ticket creation, update, listing, and reconciliation |
| `DiscordIdentityService` | atomic snapshot, challenges, uniqueness, rate limits, audit |
| `DiscordRoleService` | exact allowlisted role and nickname reconciliation |
| `DiscordRoleSyncCoordinator` | initial, periodic, and debounced LuckPerms-event reconciliation |
| `DiscordVerificationTelemetry` | bounded diagnostics and aggregate metrics without identity labels |
| `DiscordVerificationAdminCommand` | permission-gated status, manual sync, and fail-closed unlink |
| `DiscordFeedService` | join, player-list, and auction publications |
| `DiscordBot` | thin compatibility facade and service wiring |

## Player flow

1. `/verify` on Minecraft issues a link challenge in one isolated chat block.
   The whole code row copies the code, while the separately highlighted Discord
   row opens the configured RusCrafting invite.
2. The player enters it as the optional `code` value of Discord `/verify`.
3. The identity link is committed atomically.
4. Role reconciliation adds the configured verified/player roles, evaluates
   the exact LuckPerms group/permission policy, removes only configured managed
   roles that are no longer desired, and applies the configured nickname when
   Discord's member hierarchy permits it.
5. Repeating the same verification is idempotent and retries reconciliation.

`/verify recover` issues a recovery challenge for an already linked UUID.
Recovery clears managed roles from the previous Discord member before replacing
the snowflake. `/verify unlink confirm` and Discord `/unlink confirm:true`
clear managed roles before deleting the link.

## Persistence and failure semantics

Runtime-owned state is `plugins/proxyarc/data/discord-identities.json`. Each
mutation writes a complete schema-versioned snapshot to a sibling temporary
file, forces it to storage, and atomically replaces the old snapshot where the
filesystem supports it. Link state, pending challenges, rate windows, and a
bounded audit trail commit together.

A corrupt or unreadable snapshot disables identity mutation instead of starting
from an empty map. Role or nickname failure never rolls back an already committed
normal link; the link remains unprivileged and a repeated `/verify` or periodic
reconciliation retries safely. Unlink and recovery remove old managed roles
before changing identity state, so an external failure cannot leave an unlinked
account intentionally privileged.

Discord role hierarchy and member nickname hierarchy are separate boundaries.
If the target member is above the bot (including the guild owner), ProxyARC
still applies every individually manageable allowlisted role and reports that
only the nickname was skipped. During unlink, a nickname known to be managed
by ProxyARC must still be cleared before the identity is deleted; if Discord no
longer permits that cleanup, unlink remains fail-closed.

## Role policy

The tracked runtime config owns exact Discord role ids and exact LuckPerms
group/permission matchers. Policy blocks below `roles.policies` are discovered
by key, so adding a role mapping does not require a code change. Reconciliation
never discovers roles by display name and never edits roles outside that
configured set. The Discord bot's highest role must be above every managed
role; startup/readback reports an actionable hierarchy failure without
widening the policy.

To retire a managed role safely, first keep its policy block and set
`enabled: false`. Reconciliation then removes that role from every linked
member while continuing to recognize it as managed. Delete the policy block
only after that cleanup has run. Deleting the block first intentionally removes
the role from ProxyARC's authority and can leave existing assignments orphaned.

Role reconciliation runs once when Discord becomes ready, every configured
`sync.interval-seconds`, when a linked player joins, and after a debounced
LuckPerms `UserDataRecalculateEvent`. The event subscription uses the plugin-
bound LuckPerms EventBus API; the periodic pass remains the recovery fallback.

## Administration and diagnostics

All administrative identity operations require `arc.admin`. A target is an
exact stored Minecraft name, UUID, or Discord snowflake:

```text
/proxyarc discord status <account>
/proxyarc discord sync <account>
/proxyarc discord unlink <account> confirm
```

`status` reports the persisted pair plus the latest reconcile status, trigger,
age, and bounded reason. Manual `sync` runs the same authoritative reconciler.
Admin unlink rechecks the expected UUID/Discord pair after role cleanup, so a
concurrent recovery cannot delete the replacement identity.
If a recycled Minecraft name occurs in more than one historical link, lookup
fails closed and requires the UUID or Discord id instead of selecting one row.

The metrics snapshot exports storage readiness, current link/challenge counts,
last reconcile timestamps and lag, and bounded counters by operation/outcome:

```text
arc_discord_verification_storage_ready
arc_discord_identity_links
arc_discord_verification_pending_challenges
arc_discord_role_sync_last_attempt_timestamp_seconds
arc_discord_role_sync_last_success_timestamp_seconds
arc_discord_role_sync_lag_seconds
arc_discord_role_sync_results{status="..."}
arc_discord_verification_results{operation="...",outcome="..."}
```

No UUID, player name, Discord id, error text, or other unbounded value is used
as a metric label.

The in-game invite is configured as `messages.invite-url`. Validation accepts
only a direct HTTPS `discord.gg/<code>` or `discord.com/invite/<code>` URL with
no credentials, custom port, query, or fragment before it can become a chat
click action.

Player-facing inbound bridge templates are owned by the tracked
`modules/discord-chat.yml`, separate from the token-bearing `discord.yml`.
`formats.minecraft` is MiniMessage and `formats.telegram` is plain text; both
must contain `%player_name%` and `%message%` exactly once. Discord content is
inserted as an Adventure component or an unparsed value, so user text cannot
become MiniMessage markup.

## Verification

The automated acceptance matrix covers repeat verification, role-provider
failure, fail-closed unlink, identity replacement races, dynamic/disabled
policies, event debounce/suppression, admin authorization/confirmation, and
bounded metric labels. Focused tests:

```bash
./gradlew test --tests 'ru.arc.discord.*'
```

Full build:

```bash
./gradlew build
```
