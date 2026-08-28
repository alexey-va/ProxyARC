# Discord administration ops

Discord ops use the shared `/ops` bearer token and three deny-by-default gates: `discord-read-enabled`, `discord-write-enabled`, and `discord-admin-enabled`. Guild and channel allowlists are applied before the adapter reaches JDA.

## Available surfaces

| Method | Route | Capability | Operations |
| --- | --- | --- | --- |
| `GET` | `/ops/discord/guilds` | read | Allowed guild metadata |
| `GET` | `/ops/discord/channels` | read | Allowed channels, threads and permission overrides |
| `GET` | `/ops/discord/roles?guildId=...` | read | Roles and effective permissions |
| `GET` | `/ops/discord/member?guildId=...&userId=...` | read | Member, roles and moderation state |
| `GET` | `/ops/discord/invites?guildId=...` | read | Guild invite inventory |
| `GET` | `/ops/discord/messages`, `/pins`, `/search` | read | Message history, pins and bounded search |
| `POST` | `/ops/discord/messages/actions` | write | Send/edit/delete, reactions and pins |
| `POST` | `/ops/discord/threads/actions` | write | Create threads/forum posts and update thread state |
| `POST` | `/ops/discord/channels/actions` | admin | Create/update/delete all supported channel types and permission overrides |
| `POST` | `/ops/discord/roles/actions` | admin | Create/update/delete and assign/remove roles |
| `POST` | `/ops/discord/members/actions` | admin | Nickname, timeout, voice mute/deafen, kick, ban and unban |
| `POST` | `/ops/discord/guilds/actions` | admin | Guild identity, images and core safety/default settings |
| `POST` | `/ops/discord/invites/actions` | admin | Create and delete invites |

Every mutation requires `DISCORD <SURFACE> <OPERATION> <TARGET>` as an exact confirmation string. Destructive operations are not retried automatically after an unknown transport result.

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
