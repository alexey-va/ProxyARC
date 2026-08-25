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
| `DiscordChatService` | inbound/outbound bridge and allowed-mention enforcement |
| `DiscordTicketService` | forum ticket creation, update, listing, and reconciliation |
| `DiscordIdentityService` | atomic snapshot, challenges, uniqueness, rate limits, audit |
| `DiscordRoleService` | exact allowlisted role and nickname reconciliation |
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
group/permission matchers. Reconciliation never discovers roles by display
name and never edits roles outside that configured set. The Discord bot's
highest role must be above every managed role; startup/readback reports an
actionable hierarchy failure without widening the policy.

The in-game invite is configured as `messages.invite-url`. Validation accepts
only a direct HTTPS `discord.gg/<code>` or `discord.com/invite/<code>` URL with
no credentials, custom port, query, or fragment before it can become a chat
click action.

## Verification

Focused tests:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  ./gradlew test --tests 'ru.arc.discord.*'
```

Full build:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  ./gradlew build
```
