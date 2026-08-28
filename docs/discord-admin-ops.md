# Discord administration ops

Discord ops use the shared `/ops` bearer token and three deny-by-default gates: `discord-read-enabled`, `discord-write-enabled`, and `discord-admin-enabled`. Guild and channel allowlists are applied before the adapter reaches JDA.

## Available surfaces

| Method | Route | Capability | Operations |
| --- | --- | --- | --- |
| `GET` | `/ops/discord/guilds` | read | Allowed guild metadata |
| `GET` | `/ops/discord/capabilities?guildId=...` | read | Effective bot permissions, highest role and implemented operations |
| `GET` | `/ops/discord/channels` | read | Allowed channels, threads and permission overrides |
| `GET` | `/ops/discord/roles?guildId=...` | read | Roles and effective permissions |
| `GET` | `/ops/discord/member?guildId=...&userId=...` | read | Member, roles and moderation state |
| `GET` | `/ops/discord/invites?guildId=...` | read | Guild invite inventory |
| `GET` | `/ops/discord/messages`, `/pins`, `/search` | read | Message history, pins and bounded search |
| `POST` | `/ops/discord/messages/actions` | write | Send/edit/delete/bulk-delete/crosspost, reactions and pins |
| `POST` | `/ops/discord/threads/actions` | write | Create/delete/join threads and forum posts, update state/tags, add/remove members |
| `POST` | `/ops/discord/channels/actions` | admin | Create/copy/update/delete text, announcement, voice, stage, category, forum and media channels; move/unparent/sync permissions and edit overrides |
| `POST` | `/ops/discord/roles/actions` | admin | Create/update/delete/reorder roles, set icons and assign/remove roles |
| `POST` | `/ops/discord/members/actions` | admin | Nickname, timeout, voice mute/deafen/move/disconnect, kick, ban and unban |
| `POST` | `/ops/discord/guilds/actions` | admin | Guild identity/images, AFK/system/community channels, system flags and core safety/default settings |
| `POST` | `/ops/discord/invites/actions` | admin | Create and delete invites |

Every mutation requires `DISCORD <SURFACE> <OPERATION> <TARGET>` as an exact confirmation string. Destructive operations are not retried automatically after an unknown transport result.

## Practical authority matrix

The capability endpoint is the source of truth for the current bot account. An operation is usable only when both layers allow it:

1. ProxyARC exposes the typed operation and its feature gate/allowlist permits the target.
2. Discord grants the bot the required effective permission and role hierarchy position.

With `ADMINISTRATOR` and the bot role above managed member roles, the routes above form the complete practical server-layout, content, role and member administration surface. Discord still reserves guild ownership transfer, billing, boosts, Developer Portal/application settings, bot tokens and OAuth configuration to a human owner or application administrator. Integration-managed roles and targets at or above the bot's highest role also cannot be modified by a bot.

### Channel examples

Create a category (Discord's "section"):

```json
{
  "operation": "create",
  "guildId": "100000000000000001",
  "type": "category",
  "name": "Информация",
  "position": 0,
  "confirmation": "DISCORD CHANNEL CREATE 100000000000000001"
}
```

Move a channel to a category and synchronize its overwrites:

```json
{
  "operation": "update",
  "guildId": "100000000000000001",
  "channelId": "200000000000000002",
  "parentCategoryId": "300000000000000003",
  "syncPermissions": true,
  "position": 1,
  "confirmation": "DISCORD CHANNEL UPDATE 200000000000000002"
}
```

Use `clearParent: true` to move a channel back to the guild root. `copy` accepts the source as `channelId`, preserves its settings and overwrites, and can override the name, parent, position or channel-specific settings.

## Guild identity and icon

```json
{
  "operation": "update",
  "guildId": "100000000000000001",
  "name": "RusCrafting",
  "description": "Официальное сообщество сервера",
  "iconDataBase64": "...",
  "verificationLevel": "medium",
  "defaultNotificationLevel": "only_mentions",
  "explicitContentLevel": "all",
  "boostProgressBarEnabled": true,
  "invitesDisabled": false,
  "reason": "Brand and safety baseline",
  "confirmation": "DISCORD GUILD UPDATE 100000000000000001"
}
```

Use `removeIcon: true` or `removeBanner: true` to remove the corresponding image. Uploads are base64-decoded and bounded before JDA is called; setting and removing the same image in one request is rejected.

## Invites

```json
{
  "operation": "create",
  "guildId": "100000000000000001",
  "channelId": "200000000000000002",
  "maxAgeSeconds": 86400,
  "maxUses": 25,
  "temporary": false,
  "unique": true,
  "confirmation": "DISCORD INVITE CREATE 200000000000000002"
}
```

Delete uses an invite `code` and confirmation `DISCORD INVITE DELETE <code>`. The adapter resolves the invite and verifies that it belongs to the allowlisted guild before deleting it.
