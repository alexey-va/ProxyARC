# AGENTS.md — ProxyARC (Velocity plugin)

**Architecture canon:** [arc-core/AGENTS.md](https://github.com/alexey-va/arc-core/blob/main/AGENTS.md) — read before structural changes.

## Velocity-only

- **Bootstrap:** `VelocityArcRuntime.installScheduling(server, this)` before `ModuleRegistry.initAll()` in `Velocity.kt`
- **Modules:** Logging, Redis, Config, Network, JoinMessages, PlayerList, Discord, Telegram, Assistant, Antibot, … — see `InfrastructureModules.kt`
- **Scheduling:** `Tasks.*` only — never import `VelocityTaskScheduler` in feature code

## Build & deploy

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew build          # → ztarget/ProxyARC.jar
cd ~/mcserver && ./scripts/mc proxyarc --fast
```

## Runtime configs

`~/mcserver/velocity/plugins/ProxyARC/` — `config.yml`, `modules/*.yml`

See [`~/mcserver/velocity/AGENTS.md`](../../velocity/AGENTS.md).

## Dependencies

`arc-core` + `arc-core-velocity` + `arc-core-logging` + `arc-core-redis` via composite build.
