# Telegram channel and Discord chat synchronization

ProxyARC supports fixed Minecraft bridges plus arbitrary Discord ↔ Telegram mappings:

- `bridge.chat`: Telegram discussion/topic ↔ Minecraft global chat ↔ Discord chat;
- `bridge.general`: Telegram discussion/topic ↔ Discord general;
- `channels.information`: optional public information channel mirroring outbound Discord general;
- `modules/channel-sync.yml`: independent Discord channel ↔ Telegram chat/forum-topic mappings.

The legacy `chat-id` plus `topics.chat` / `topics.general` keys remain supported. Explicit `bridge.*` destinations take precedence when they contain valid non-zero ids.

## Initial Telegram setup

Telegram Bot API cannot create a channel. An owner must create the public/private channel in Telegram and add the ProxyARC bot as an administrator. Grant only the permissions used by the deployment:

- post, edit and delete messages for information channels;
- change channel info for metadata ops;
- pin messages when pin/unpin ops are enabled;
- manage topics and invite links only when those admin ops are enabled;
- read discussion messages for Telegram-to-Minecraft/Discord sync.

Use exact signed numeric ids in configuration. Channel and supergroup ids normally begin with `-100`; mutable `@username` identifiers are intentionally rejected by ops.

Example `modules/telegram.yml`:

```yaml
enabled: true
token: "<runtime secret>"
username: "RusCrafting"

bridge:
  chat:
    chat-id: -1001111111111
    thread-id: 10
  general:
    chat-id: -1001111111111
    thread-id: 11

channels:
  information:
    chat-id: -1002222222222
    thread-id: 0
    mirror-general: true
```

`thread-id: 0` means the root chat/channel rather than a forum topic. Duplicate destinations are automatically collapsed.

### Outbound network route

Telegram polling and every Bot API mutation use the shared outbound HTTP proxy from `modules/llm-network.yml`:

```yaml
http-proxy:
  enabled: true
  host: "172.29.172.3"
  port: 8888
```

The same `DefaultBotOptions` instance is registered with the long-polling session and the API sender, so inbound updates and outbound operations cannot accidentally use different routes. On the RusCrafting Velocity host this endpoint is reached through `wg-utils`; no Telegram-specific firewall or WireGuard route is required.

## Arbitrary Discord ↔ Telegram chat sync

Configure one-to-one mappings in `modules/channel-sync.yml`:

```yaml
enabled: true
mappings:
  - id: community
    discord-channel-id: "1073279998359765042"
    telegram:
      chat-id: "-1001111111111"
      thread-id: 42
      username: "ruscrafting_chat"
    direction: both
    sync-edits: true
    sync-deletes: true
    to-discord-format: "**%sender%** » %message%"
    to-telegram-format: "%sender% » %message%"
```

Each Discord channel and each Telegram chat/topic may occur in only one mapping. `direction` accepts `both`, `discord-to-telegram`, or `telegram-to-discord`. The relay preserves replies when the referenced message has already crossed the bridge, forwards Discord attachment URLs as text, and mirrors edits in both directions.

Discord deletions are mirrored to Telegram. Telegram Bot API does not emit deleted-message updates, so a deletion performed inside Telegram cannot be mirrored back to Discord.

Cross-platform message ids are stored in `data/channel-sync-links.json` using atomic writes. This prevents echo loops and duplicate sends after reloads/restarts. Fixed Minecraft chat/general bridges continue to work independently.

## Technical entities and mentions

Both arbitrary mappings and the fixed `chat`/`general` bridges parse platform entities before rendering the target message:

| Source | Target behavior |
| --- | --- |
| Discord `<@user-id>` | Linked Telegram user mention through `tg://user?id=...` |
| Telegram `text_mention` or linked `@username` | Whitelisted Discord `<@user-id>` mention with an actual notification |
| Discord `<#channel-id>` | Linked Telegram `@channel` when the mapping declares `telegram.username`; otherwise a readable `#channel` fallback |
| Telegram mapped channel `@username` | Discord `<#channel-id>` |
| Discord role mention | Readable non-pinging Telegram role label |
| Discord custom emoji, timestamp, slash-command mention | Safe `:emoji:`, ISO timestamp, or `/command` representation |
| Telegram text link and formatting entities | Discord link, bold, italic, underline, strike, spoiler, code, or preformatted block |

Only identities proven through the account-link workflow may produce an actual cross-platform user ping. Unknown mentions, forged raw `<@id>` tokens, `@everyone`, and `@here` are neutralized to text. Discord sends keep the global allowed-mentions parser disabled and whitelist only the translated user ids.

## One Minecraft identity in Discord and Telegram

Discord and Telegram are separate verified edges keyed by the same Minecraft UUID, so one player may have both accounts linked simultaneously. Telegram usernames and display names are metadata only; the authenticated identity is the stable numeric Telegram user id.

Link flow:

1. Join an allowlisted Minecraft backend.
2. Run `/verify telegram` in Minecraft.
3. Open a private chat with the configured Telegram bot.
4. Send `/verify <one-time-code>`.

Useful commands:

```text
# Minecraft
/verify telegram
/verify telegram status
/verify telegram unlink confirm

# Private chat with the Telegram bot
/start
/verify <code>
/verify
/status
/unlink confirm
/help
```

The bot publishes the same commands into Telegram's private-chat command menu.
`/start`, `/status`, and `/verify` without a code report the authenticated
Telegram user's current link. Linked and newly verified users receive an inline
button to `channels.information.url`.

Codes are single-purpose, hashed at rest, expiring and rate-limited. Links, challenges and an audit tail are atomically stored in `data/telegram-identities.json` with owner-only permissions.

When a linked player changes Minecraft name, both Discord and Telegram identity records are refreshed on server connection. Discord can update the actual guild nickname through its existing role reconciliation. Telegram does not expose an API for changing an ordinary member's profile name, so Telegram messages and cross-platform mentions use the canonical Minecraft name inside the bridge instead.

## Ops authorization

Telegram ops use the existing `/ops` Bearer token plus separate deny-by-default capability gates and allowlists:

```yaml
telegram-read-enabled: true
telegram-write-enabled: true
telegram-admin-enabled: true
telegram-allowed-chat-ids: ["-1001111111111", "-1002222222222"]
telegram-write-chat-ids: ["-1001111111111", "-1002222222222"]
telegram-admin-chat-ids: ["-1001111111111", "-1002222222222"]
```

Available routes:

| Method | Route | Capability | Purpose |
| --- | --- | --- | --- |
| `GET` | `/ops/telegram/chats` | read | Read metadata for every explicitly allowed chat id |
| `GET` | `/ops/telegram/chat?chatId=...` | read | Read one allowed chat/channel |
| `GET` | `/ops/telegram/administrators?chatId=...` | read | Read administrators and their effective rights |
| `GET` | `/ops/telegram/member?chatId=...&userId=...` | read | Read one member and restrictions |
| `POST` | `/ops/telegram/messages/actions` | write | Send rich posts, edit, delete, pin or unpin |
| `POST` | `/ops/telegram/chats/actions` | admin | Metadata, photo, permissions, pins and sticker-set administration |
| `POST` | `/ops/telegram/topics/actions` | admin | Full forum-topic and General-topic administration |
| `POST` | `/ops/telegram/invites/actions` | admin | Create, edit or revoke invite links |
| `POST` | `/ops/telegram/members/actions` | admin | Ban, unban, restrict, promote, title and join-request actions |

Every mutation requires an exact confirmation string. A plain post:

```json
{
  "operation": "send",
  "chatId": "-1002222222222",
  "text": "Обновление сервера уже доступно",
  "disableNotification": false,
  "confirmation": "TELEGRAM MESSAGE SEND -1002222222222"
}
```

Rich posts support `parseMode` (`none`, `html`, `markdown`, `markdown_v2`), URL-button rows, content protection, link-preview control, and one photo/document supplied as a Telegram file id/URL or bounded base64 data:

```json
{
  "operation": "send",
  "chatId": "-1002222222222",
  "text": "<b>Новый сезон уже открыт</b>",
  "parseMode": "html",
  "buttons": [[{"text": "Играть", "url": "https://ruscrafting.ru"}]],
  "attachment": {
    "type": "photo",
    "fileName": "season.png",
    "dataBase64": "..."
  },
  "confirmation": "TELEGRAM MESSAGE SEND -1002222222222"
}
```

Edit channel metadata:

```json
{
  "operation": "update",
  "chatId": "-1002222222222",
  "title": "RusCrafting — новости",
  "description": "Обновления, события и важная информация сервера",
  "confirmation": "TELEGRAM CHAT UPDATE -1002222222222"
}
```

Updating both title and description issues two Bot API calls and is not transactional; after an upstream Telegram error, read the channel metadata before retrying.

Set a channel or forum-group photo without a browser:

```json
{
  "operation": "set_photo",
  "chatId": "-1002222222222",
  "photo": {
    "type": "photo",
    "fileName": "ruscrafting.png",
    "dataBase64": "..."
  },
  "confirmation": "TELEGRAM CHAT SET_PHOTO -1002222222222"
}
```

`set_photo` requires an uploaded base64 image because Telegram's `setChatPhoto` method requires multipart upload. `delete_photo`, `unpin_all`, `set_sticker_set`, and `delete_sticker_set` use the same chat route and exact operation-specific confirmation.

Create a forum topic:

```json
{
  "operation": "create",
  "chatId": "-1001111111111",
  "name": "Обновления",
  "iconColor": 7322096,
  "confirmation": "TELEGRAM TOPIC CREATE -1001111111111"
}
```

The broadcast channel itself cannot contain forum topics. Topic operations target the linked/bridged forum supergroup. Besides `create`, `update`, `close`, `reopen`, `delete`, and `unpin_all`, the route supports `general_update`, `general_close`, `general_reopen`, `general_hide`, `general_unhide`, and `general_unpin_all` for Telegram's special General topic.

Set default member permissions:

```json
{
  "operation": "set_permissions",
  "chatId": "-1001111111111",
  "permissions": {
    "canSendMessages": true,
    "canSendPhotos": true,
    "canManageTopics": false
  },
  "useIndependentPermissions": true,
  "confirmation": "TELEGRAM CHAT SET_PERMISSIONS -1001111111111"
}
```

Message confirmations:

```text
TELEGRAM MESSAGE SEND <chatId>
TELEGRAM MESSAGE EDIT <messageId>
TELEGRAM MESSAGE DELETE <messageId>
TELEGRAM MESSAGE PIN <messageId>
TELEGRAM MESSAGE UNPIN <messageId>
```

Invite creation uses `TELEGRAM INVITE CREATE <chatId>`; revocation uses `TELEGRAM INVITE REVOKE <chatId>`. Topic updates and destructive actions use the topic id as confirmation target, for example `TELEGRAM TOPIC DELETE 42`.

Member moderation uses `TELEGRAM MEMBER <OPERATION> <userId>`. Supported operations are `ban`, `unban`, `restrict`, `promote`, `set_admin_title`, `approve_join_request`, and `decline_join_request`. `restrict` accepts the same permission object as `set_permissions`; `promote` accepts an `administratorRights` object with explicit nullable Bot API rights, so omitted rights are not silently invented.

After changing destinations, mappings, token, or ops settings, `/proxyarc reload` safely restarts the Telegram session, reloads channel mappings, and restarts the ops HTTP server. Discord/JDA remains connected.
