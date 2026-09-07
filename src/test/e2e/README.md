# Real Velocity acceptance

The `velocity-e2e` CI job builds the actual ProxyARC distribution, starts
Velocity 3.4.0 build 563 with a pinned SHA-256, and exercises its loopback HTTP
listener against disposable MySQL 8.4. Runtime metadata comes from the official
[PaperMC downloads service](https://docs.papermc.io/misc/downloads-service/).

The suite verifies a signed MinecraftRating callback, both durable pending
reward components (1,000 Vault coins and 3 tokens), eight concurrent replays,
rejection of an invalid signature, and replay after restarting the disposable
Velocity process. A native HotMC multipart callback proves that a second source
produces its own event. Exact SQL counts and component amounts accompany HTTP
assertions. This does not grant rewards to a Paper player; ArcVotes owns and
tests that delivery boundary. RTP, chat and Limbo sessions need separate client
journeys and are not covered by this HTTP suite.

Run `./gradlew shadowJar`, then `python3 -u src/test/e2e/run_velocity.py` in the
CI integration environment with `E2E_MYSQL_CONTAINER` pointing at the configured
disposable service. The suite binds Velocity to loopback port 25577 and callbacks
to 25876. Synthetic credentials match only the disposable workflow service;
production configs and environment files are never copied. Logs stay under
`build/velocity-e2e-*`; both test processes are shut down even after failure.
