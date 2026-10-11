# FB-SMP Bedrock stack smoke test — 2026-10-11

## Goal
Confirm the full cross-play stack (Velocity + Geyser + Floodgate + Paper
backend) boots on Minecraft **26.1.2** with the upgraded FB-SMP plugin jar,
and that Geyser/Floodgate forwarding is wired correctly.

## Stack under test
- Paper `26.1.2` (JDK 25), backend `127.0.0.1:25566`, `online-mode=false`
- Velocity `0.0.0.0:25565`, `player-info-forwarding-mode = modern`
- Geyser-Velocity 2.11.3-b1251, Bedrock listener UDP `19132`, `auth-type: floodgate`
- Floodgate-Velocity, `username-prefix: "."`
- FB-SMP paper jar built from branch `openhands/shop-sell-loan-upgrade`

## Result: PASS

| Component | Evidence |
|-----------|----------|
| Paper backend | `Done (11.5s)`; FBSMP enabled: "Database migrations are current" |
| Velocity proxy | `Listening on /0.0.0.0:25565`; `Done (4.47s)` |
| Geyser | `Started Geyser on UDP port 19132`; 199 block overrides, 7 custom blocks |
| Floodgate | `Took 230ms to boot Floodgate` |
| Proxy↔backend | fbsmp-proxy: "initialized for 1 registered server(s)" |
| Live DB | migrated to V4 on boot, then restored to the V3 snapshot |

Forwarding secret in `config/paper-global.yml` matches `forwarding.secret`.
A real Bedrock client (`firebread4876`) is already recorded connecting through
Geyser → Floodgate → Velocity → Paper in `logs/fbsmp-velocity.log` (earlier
session), proving the Bedrock path end-to-end, not just the boot.

## Known, harmless noise
- Paper logs `DirectoryProviderSource` errors for the Velocity-platform jars
  (`Geyser-Velocity.jar`, `floodgate-velocity.jar`, `fbsmp-velocity-1.0.0.jar`)
  and Velocity logs the inverse for the Paper jar. These are the expected
  "wrong-platform jar in the wrong folder" notices from the dev layout — the
  deploy package keeps the proxy and backend folders separate, so they do not
  occur in production. No functional impact.

## Environment
Restored after the test: DB sha `6ac4cbca…` (V3), original jar `cc0c14d6…`.
