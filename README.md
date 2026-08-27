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
