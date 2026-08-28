# ProxyARC

RusCrafting's Velocity plugin for network routing, join flow, messaging,
Discord/Telegram integration, assistant behavior, and antibot boundaries.

## Build

Requirements: Java 25 and the checked-in Gradle 9.2.1 wrapper.

```bash
./gradlew clean test build
```

The default build resolves immutable `arc-core 2.0.0` artifacts anonymously
from the public RusCrafting Maven repository. A neighboring core checkout is
not required. For coordinated local development, opt in explicitly:

```bash
./gradlew test build -ParcCoreDir=/absolute/path/to/arc-core
```

The production artifact is `ztarget/ProxyARC.jar`.

## Telegram channel

Telegram can bridge Minecraft and Discord chat topics, translate verified mentions and formatting, link the same Minecraft identity to both platforms, and mirror Discord general messages into a public information channel. Channel metadata and posts are managed through deny-by-default ops endpoints with exact chat allowlists and mutation confirmations.

Setup, permissions, configuration and request contracts are documented in [docs/telegram-channel-ops.md](docs/telegram-channel-ops.md).
