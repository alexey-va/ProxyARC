# Global chat ops

ProxyARC exposes one authenticated, process-local chat view and send path under
the existing ProxyOps Bearer token. The send route always speaks as `Codex` and
uses the configured global Minecraft chat, Discord game channel, and Telegram
game topic. It does not accept a player name, chat ID, topic ID, or channel ID
from the caller.

## Read recent messages

`GET /ops/chat?limit=200&after=<cursor>&player=<name-or-uuid>` returns messages
in chronological order. `limit` defaults to 200 and is bounded to 1..1000. With
no `after` cursor, the endpoint returns the newest matching messages. A player
filter matches the author name or linked Minecraft UUID, case-insensitively.

Each message includes `cursor`, ISO-8601 `timestamp`, `source` (`minecraft`,
`telegram`, `discord`, or `codex`), `author`, nullable `playerUuid`, `content`,
and `contentTruncated`. The response includes the current `instanceId`,
`nextCursor`, `historyGap`, and nullable `gapReason`. The buffer keeps the most
recent 5,000 records. It has no pre-start history: a first read reports
`instance-start`, while an expired cursor reports `buffer-truncated`. A cursor
from a previous process reports `instance-changed` and returns the newest
current messages.

Example response shape:

```json
{
  "ok": true,
  "instanceId": "<proxy-instance-uuid>",
  "messages": [
    {
      "cursor": "<proxy-instance-uuid>:42",
      "timestamp": "2026-10-09T12:34:56Z",
      "source": "minecraft",
      "author": "PlayerName",
      "playerUuid": "<minecraft-uuid>",
      "content": "Hello",
      "contentTruncated": false
    }
  ],
  "nextCursor": "<proxy-instance-uuid>:42",
  "historyGap": false,
  "gapReason": null
}
```

## Send a Codex message

`POST /ops/chat` accepts plain text only:

```json
{
  "requestId": "<unique-request-uuid>",
  "instanceId": "<instanceId-from-a-recent-GET>",
  "content": "Could you share the exact error you see?"
}
```

Content is limited to 500 Unicode code points and 2,000 UTF-8 bytes. Blank
content, control characters, and line separators are rejected. The fixed
Minecraft line uses the server's global chat marker and the unlinked name
`Codex`; Discord uses the normal bold-name chat pattern; Telegram uses plain
`Codex » message` text in the configured game topic. Telegram's confirmed
receipt includes the actual message ID and topic ID. Minecraft and Discord
report `accepted`, because those APIs queue or broadcast without a delivery
acknowledgement; the Minecraft receipt also includes the connected-player
count observed at dispatch.

`instanceId` is required to prevent a retry after a proxy restart from sending
the message again. Reusing a `requestId` with identical content returns the
original receipt; reusing it with different content returns HTTP 409. The
process keeps up to 256 request receipts and refuses new sends with HTTP 429
once full instead of evicting IDs. If a request times out with an unknown
outcome, retry only with the same `requestId` and `instanceId`.

The inbox records Minecraft global messages that pass the chat and mute checks,
messages received in the configured Telegram game topic, messages received in
the Discord game channel, and Codex sends. It does not poll Telegram updates;
the existing bot remains the sole update consumer.
