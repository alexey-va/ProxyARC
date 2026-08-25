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
| `DiscordIntegrationListener` | `/account`, `/online`, `/server`, `/player`, `/notifications`, `/invite`, `/event`, and buttons |
| `DiscordIntegrationStore` | atomic opt-in preferences, recovery requests, event state, and bounded audit |
| `DiscordNotificationService` | rate-bounded personal DMs and staff alerts with mentions disabled |
| `DiscordLinkProtectionService` | delayed account-link transfer with cancellation by the previous Discord |
| `DiscordModerationService` | read-only LiteBans event context and notification bridge |
| `DiscordGameEventService` | announcements, participation/reminder state, and exact event-role changes |
| `DiscordPresenceService` | bot activity plus the server-aware online dashboard heartbeat |
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

`/verify recover` issues a recovery challenge for an already linked UUID. The
new Discord claim waits for the configured protection delay. The previously
linked Discord must successfully receive a private cancel button; a failed DM
delivery cancels the transfer and alerts staff. Cancellation releases the
claim before any role or identity mutation. Only after that window does
recovery clear managed roles from the previous Discord member and replace the
snowflake. Active event participation is migrated to the new Discord id; unlink
removes it. Both the pending transfer and its outcome are audited.
`/verify unlink confirm` and Discord `/unlink confirm:true`
clear managed roles before deleting the link.

## Player integration commands

- `/account` is an ephemeral identity dashboard with the Minecraft identity,
  current backend, link date, Discord roles and last sync result. Its buttons
  reuse the same authoritative reconciler and fail-closed unlink workflow.
- `/online`, `/server [name]`, and `/player <name>` expose only public network
  presence and verification state. They never reveal UUIDs or Discord ids.
- `/notifications` owns six independently opt-in DM categories: mentions,
  auction sales, ticket replies, punishment changes, events, and invitations.
  Defaults are all off and per-user delivery is rate bounded.
- `/invite <player>` sends a fixed, non-user-authored server invitation only
  from a linked player who is currently online, and only when the linked target
  explicitly enabled invitation DMs.
- The `Не могу войти` account action creates a short-lived, staff-reviewed
  LimboAuth request in the private alerts channel. ProxyARC deliberately does
  not execute SQL, delete an auth row, or run a password-reset command.
- `/event` is Discord-permission-gated. A created event publishes in the exact
  configured channel, gives verified users join/leave buttons, reconciles one
  exact no-permission participant role, sends opt-in reminders, removes the
  temporary participant role on completion, and assigns the configured winner
  role to a verified winner.

The online dashboard now groups players by their actual Velocity backend. Bot
presence and dashboard refresh from one live proxy snapshot, so `/online`,
`/server`, and the channel panel use the same state.

## Persistence and failure semantics

Runtime-owned state is `plugins/proxyarc/data/discord-identities.json`. Each
mutation writes a complete schema-versioned snapshot to a sibling temporary
file, forces it to storage, and atomically replaces the old snapshot where the
filesystem supports it. Link state, pending challenges, rate windows, and a
bounded audit trail commit together.

Integration-owned state is separately stored in
`plugins/proxyarc/data/discord-integration.json` with the same atomic replace,
owner-only permission, schema validation, corruption fail-closed behavior and
bounded audit rules. Splitting the files prevents a preferences or event-state
problem from weakening identity uniqueness.

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
`formats.minecraft` and `formats.minecraft-reply` are MiniMessage, while
`formats.telegram` is plain text. The reply template contains the canonical
linked reply author and a bounded plain-text preview. The base templates
must contain `%player_name%` and `%message%` exactly once. Discord content is
inserted as an Adventure component or an unparsed value, so user text cannot
become MiniMessage markup.

Attachments become named clickable URLs, stickers and custom emoji retain
readable names, Discord user/role/channel mentions become bounded visible
labels, and direct or Markdown links become Adventure click actions. Outbound
Minecraft tags resolve only exact linked players and exact guild entities;
allowed mentions remain disabled at the send boundary, while opted-in player
mentions are delivered privately instead of enabling mass pings.

All integration routing, role ids, heartbeat/reminder intervals, rate limits,
command descriptions, button labels and player-facing text live in tracked
`modules/discord-integration.yml`. Token/proxy connection settings remain in
the deployment-owned `discord.yml`.

## Auction and moderation events

ARC listens for the completed zAuctionHouse `PURCHASED` transition and emits a
typed, validated `arc.auction_sale_events` Redis message. ProxyARC rejects
malformed ids, names, sizes and text bounds before looking up the seller's
verified identity. It never guesses that a disappeared listing was sold.

LiteBans integration subscribes only to the supported event API. It can post
bounded staff context and opt-in personal state changes, but it exposes no ban,
mute, kick or pardon Discord command and never accepts moderation targets from
untrusted chat text.

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
