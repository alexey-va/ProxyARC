# ProxyARC

RusCrafting's Velocity plugin for network routing, join flow, messaging,
Discord/Telegram integration, and assistant behavior. Connection filtering is
owned by the network filter plugin, outside ProxyARC.

## Build

Requirements: Java 25 and the checked-in Gradle 9.2.1 wrapper.

```bash
./gradlew clean test build
```

The default build resolves immutable `arc-core 2.2.0` artifacts anonymously
from the public RusCrafting Maven repository. A neighboring core checkout is
not required. For coordinated local development, opt in explicitly:

```bash
./gradlew test build -ParcCoreDir=/absolute/path/to/arc-core
```

The production artifact is `ztarget/ProxyARC.jar`.

## Vote callback ingress

`modules/votes.yml` owns a separate loopback-only HTTP listener for four exact
monitoring callback routes. The listener is disabled by default, has bounded
workers, request queues and bodies, authenticates every provider contract, and
persists an idempotent event to shared MySQL before returning success. Provider
and MySQL secrets are read from `plugins/proxyarc/.env`; the tracked YAML never
contains them.

The public TLS proxy may forward only these paths to the dedicated listener:

- `/callbacks/minecraft-rating`
- `/callbacks/hotmc`
- `/callbacks/monitoring-minecraft`
- `/callbacks/gamemonitoring`

The callback module does not expose or register metrics. ProxyARC's older,
explicit metrics module is independent from vote ingress.

## Join and leave announcements

`modules/join-messages.yml` is the network source of truth for default
announcements and the selectable phrase catalog. Each phrase owns its stable
id, MiniMessage text, GUI material/custom model data, optional permission, and
rank label. ProxyARC publishes the validated catalog to Redis; Paper ARC keeps
only GUI chrome locally and fails closed when the catalog is unavailable.
Every accepted join and leave is announced in Minecraft except on backends in
`delivery.minecraft-excluded-servers`. Discord and Telegram
receive the same announcement only when the player has
`arc.join-message.external`; the permission is captured when the session starts
so its matching leave event follows the same delivery policy.

## Telegram channel

Telegram can bridge Minecraft and Discord chat topics, translate verified mentions and formatting, link the same Minecraft identity to both platforms, and mirror Discord general messages into a public information channel. Channel metadata and posts are managed through deny-by-default ops endpoints with exact chat allowlists and mutation confirmations.

## Website portal bridge

The optional portal bridge mirrors both fixed chats, online presence, and the
existing Discord/Telegram identity links to the website without creating a
second link command. It also drains authenticated website messages from the
portal outbox into Minecraft, Discord, and Telegram.
See [`docs/portal-bridge.md`](docs/portal-bridge.md).

Setup, permissions, configuration and request contracts are documented in [docs/telegram-channel-ops.md](docs/telegram-channel-ops.md).
