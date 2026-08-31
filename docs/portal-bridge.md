# Portal bridge

`PortalBridgeModule` is a bounded bridge between Velocity and the RusCrafting
portal. Existing Minecraft, Discord and Telegram ingress never waits for portal
HTTP. The reverse path polls a durable portal outbox asynchronously.

It publishes:

- normalized game and community chat messages as they enter their existing
  bridges;
- online-player snapshots used for cabinet presence and playtime;
- complete Discord and Telegram identity snapshots sourced from the existing
  `/verify discord` and `/verify telegram` stores.

An authenticated portal session whose account has a Minecraft UUID may enqueue
one-line messages. ProxyARC validates every pulled record again, routes the game
channel to Minecraft plus Discord and Telegram, and routes the community
channel only to Discord and Telegram. It acknowledges an outbox row after all
required local sinks accepted it. A lost acknowledgement can cause an
at-least-once duplicate after recovery; it cannot silently mark an undelivered
message complete.

The portal does not create a second Minecraft link. A website OAuth/OIDC login
resolves the provider's stable user id against the mirrored ProxyARC snapshot.
If no link exists, the player must complete the normal in-game `/verify` flow.

Configure `modules/portal-bridge.yml` with the public portal base URL and the
same secret as `PORTAL_BRIDGE_TOKEN`. Prefer the `PORTAL_BRIDGE_TOKEN`
environment variable on Velocity and keep the production token outside tracked
configuration. Identity snapshots run every 30 seconds and outbound chat polls
every 2 seconds by default; stale
snapshots are rejected by the portal using their `capturedAt` timestamp.

The publisher has a strict in-flight limit and request timeout. Saturation or a
portal outage drops only the inbound portal copy and logs a bounded warning; it
never interrupts chat, verification, or player connections. Outbound pulls do
not overlap and leave rejected or unavailable deliveries pending for retry.
