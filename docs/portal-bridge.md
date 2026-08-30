# Portal bridge

`PortalBridgeModule` is a one-way, bounded publisher from Velocity to the
RusCrafting portal. Minecraft, Discord and Telegram delivery never waits for
portal HTTP.

It publishes:

- normalized game and community chat messages as they enter their existing
  bridges;
- online-player snapshots used for cabinet presence and playtime;
- complete Discord and Telegram identity snapshots sourced from the existing
  `/verify discord` and `/verify telegram` stores.

The portal does not create a second Minecraft link. A website OAuth/OIDC login
resolves the provider's stable user id against the mirrored ProxyARC snapshot.
If no link exists, the player must complete the normal in-game `/verify` flow.

Configure `modules/portal-bridge.yml` with the public portal base URL and the
same secret as `PORTAL_BRIDGE_TOKEN`. Prefer the `PORTAL_BRIDGE_TOKEN`
environment variable on Velocity and keep the production token outside tracked
configuration. Identity snapshots run every 30 seconds by default; stale
snapshots are rejected by the portal using their `capturedAt` timestamp.

The publisher has a strict in-flight limit and request timeout. Saturation or a
portal outage drops only the portal copy and logs a bounded warning; it never
interrupts chat, verification, or player connections.
