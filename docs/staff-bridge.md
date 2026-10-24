# Staff bridge

`StaffBridgeModule` owns remote Minecraft moderation, command dispatch and private
Discord observations. `modules/staff-bridge.yml` is disabled in bundled defaults.
Runtime destinations and backend credentials belong in the operator configuration.

Discord accepts native `/mc` subcommands in the dedicated command channel, or the
same text grammar prefixed by `!mc`. Telegram accepts `/mc` only in a direct chat
with the bot. Edited, forwarded and anonymous Telegram commands do not execute.
Telegram group creation and group spy feeds are outside this feature.

```text
/mc mute Player 10m Повторный флуд
/mc ban Player 7d Нарушение правил
/mc unmute Player
/mc unban Player
/mc command proxy list
/mc command spawn list
```

For Discord's slash interface, select the named subcommand and fill its fields;
the text examples describe the Telegram grammar. `permanent` is accepted in place
of a duration. Native LiteBans permissions and restrictions still apply.
Backend names are the configured keys under `backends`; the operator may enable
only Spawn and Survival while still observing Parkour.

Every request resolves the provider's existing authenticated UUID link and freshly
loads the linked user's LuckPerms groups. Group and permission checks use the user's
current effective query options, or the proxy's static query options when offline;
context restrictions are never ignored. Loaded state is checked again immediately
before dispatch, and a final Velocity command-event guard rejects an administrator
source after role revocation or module close. Only configured `admin` and `moderator` groups qualify; Discord
role membership alone cannot execute a Minecraft command. Discord role sync uses
the same context rules and does not treat an orphaned primary-group label as membership.
The existing Discord `Администрация` role may include both groups, so it must never
grant access to the admin spy channels.

Moderators must be online under that linked Minecraft account. Dispatch uses the
actual Velocity `Player`, preserving each plugin's player permissions, punishment
limits and target restrictions. Their detailed native response appears in Minecraft;
the bot acknowledges dispatch. There is no synthetic offline moderator sender:
LiteBans classifies non-Player sources as console. Administrators can execute proxy
commands while offline through a capturing source with their LuckPerms permissions.
Backend console commands are administrator-only and use authenticated HTTP
`POST /ops/staff-command` with `requestId`, `actorUuid` and `command`. ARC performs
its own fresh administrator check. Backend listeners and routing are managed in ARC.

Only single-line commands up to 512 characters are accepted. Typed punishment
arguments exclude switches, name injection and multiline reasons. Authentication
commands and opaque execution wrappers (`execute`, `sudo`, scripts and similar)
are excluded from remote execution and from spy delivery, including namespace and
CMI forms. The same exclusion must remain at the ARC publisher, before Redis.

External events older than two minutes are rejected. A bounded atomic journal
reserves the provider event ID before execution and retains it for ten minutes;
reloads and restarts cannot redispatch the same event. Rate limits are per linked
UUID, shared across both providers. An ambiguous timeout is reported as unknown
and never retried automatically. Audit logs contain provider, actor UUID, event ID,
operation, destination and outcome; they contain neither raw commands nor reasons.

Discord staff channels must be disjoint from public bot channels and every generic
channel-sync mapping. Before every queued send, the live JDA state must be connected,
`@everyone` must explicitly deny View Channel, and no unapproved role/member may
explicitly allow it. Global Discord administrators and the guild owner intrinsically
bypass channel ACLs and are trusted as administrators. Admin log channels allow only
`discord.admin-role-ids`; the command channel also allows `discord.moderator-role-ids`.
The bot member is permitted. One console channel can receive multiple labelled
servers; it must remain separate from the command/chatspy/commandspy channels.
JDA retains `MEMBER_OVERRIDES` (no additional privileged gateway intent is required
by JDA 6.5.0); delivery refuses a session with that cache disabled.

Spy input uses existing `arc.spy.v1` and additive `arc.staff-console.v1` through
core `ValidatedRedisTopic`, strict bounded JSON and a 60-second replay guard. Only
configured backend origins are admitted. Proxy player command observations include
registered proxy-native commands only; backend commands are observed on Paper.
The live tail has an 80-line queue per destination and sends at most eight lines
per two-second batch. Oldest excess lines are discarded instead of replayed as a
large backlog. Bot messages cannot reenter the public bridge, and staff ingress is
consumed before portal/chat/channel-sync processing.

Backend `token-file` is preferred over inline `token`. It is a relative file beneath
the ProxyARC data directory (for example `secrets/staff-spawn.token`), bounded to
4096 bytes; traversal, absolute paths and symlinks are rejected. Provision it with
owner-only permissions. Do not commit tokens. Base URLs are operator-controlled,
have no user info/query/fragment, and redirects are never followed.

All command persistence runs on core `BoundedAsyncExecutor`; modules close workers,
HTTP, Discord batching, listeners and Redis topics. ProxyARC pins every core artifact
to published 2.7.35, the first locally verified tuple providing that executor.

Focused checks:

```bash
./gradlew test --tests 'ru.arc.staff.*' \
  --tests 'ru.arc.telegram.TelegramBotTest' \
  --tests 'ru.arc.core.modules.ProxyArcReloadTest' \
  --tests 'ru.arc.channelsync.ChannelSyncServiceTest' \
  --tests 'ru.arc.discord.DiscordRoleServiceTest'
```

These prove source behavior. Deployment, active bot command registration, current
Discord ACLs, backend connectivity and a real staff command round trip are separate
runtime checks.
