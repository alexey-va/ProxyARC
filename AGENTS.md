# AGENTS.md — ProxyARC (Velocity plugin)

**Architecture canon:** [arc-core/AGENTS.md](https://github.com/alexey-va/arc-core/blob/main/AGENTS.md) — read before structural changes.

## Velocity-only

- **Bootstrap:** `VelocityArcRuntime.installScheduling(server, this)` before `ModuleRegistry.initAll()` in `Velocity.kt`
- **Modules:** Logging, Redis, Config, Network, JoinMessages, PlayerList, Discord, Telegram, Assistant, Antibot, … — see `InfrastructureModules.kt`
- **Scheduling:** `Tasks.*` only — never import `VelocityTaskScheduler` in feature code
- **Signed chat:** chat mode may derive a logical `!`-prefixed message for
  proxy routing and Discord/Telegram bridges, but must not replace or deny the
  Velocity `PlayerChatEvent`; ARC applies the real CMI prefix on Paper.

## Build & deploy

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew build          # → ztarget/ProxyARC.jar
cd ~/mcserver && ./scripts/mc proxyarc --fast
```

## Runtime configs

`~/mcserver/velocity/plugins/proxyarc/` — `modules/*.yml`, `prompts/chat.txt`

See [`~/mcserver/velocity/AGENTS.md`](../../velocity/AGENTS.md).

## Dependencies

The public `ru.ruscrafting.arc:*:2.0.0` release is the default dependency path.
Use `-ParcCoreDir=/absolute/path/to/arc-core` only when a task intentionally
tests coordinated local core changes through explicit composite substitution.
