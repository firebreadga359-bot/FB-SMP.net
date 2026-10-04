# FB-SMP

FB-SMP is an original Paper + Velocity SMP project targeting Minecraft 26.1.2 and Java 25. The Paper backend is the authority for gameplay and SQLite persistence; Velocity is the public Java entry point. Geyser and Floodgate provide Bedrock joining.

## Requirements

- Java 25 for the server and build toolchain.
- Gradle 8.14.3 or newer (the Gradle wrapper is included).
- A Paper 26.1.2 server jar and Velocity 4.2.x server jar.
- Geyser-Velocity and Floodgate-Velocity plugin jars for Bedrock access.
- ViaVersion and ViaBackwards when broader client-version compatibility is wanted.

The build uses Gradle's Foojay toolchain resolver to provision Java 25 if it is not already installed. The runtime startup script refuses to start if the selected Java executable is not major version 25.

## Build

```sh
./gradlew clean assembleServer
```

The deployable Paper plugin is `fbsmp-paper/build/libs/fbsmp-paper-1.0.0.jar`. The Velocity companion is in `fbsmp-velocity/build/libs/`. The project is split into `fbsmp-common`, `fbsmp-paper`, and `fbsmp-velocity`.

## Install

1. Put `paper.jar` and `velocity.jar` in `server/`.
2. Copy `server/server.properties` to the Paper server directory. Keep Paper on `127.0.0.1:25566` and disable direct public access.
3. Copy `server/paper-global.yml` to Paper's `config/paper-global.yml`. Generate a random Velocity forwarding secret and use the same value in `server/velocity/forwarding.secret` and the Paper `proxies.velocity.secret` setting. Do not commit or share the secret.
4. Copy the built FB-SMP plugin jar into Paper's `plugins/` directory.
5. Install Geyser-Velocity and Floodgate-Velocity in Velocity's `plugins/` directory. Configure Geyser to listen on UDP `0.0.0.0:19132`, use Floodgate authentication, and forward to Velocity at `25565`. Keep Floodgate's generated key private.
6. Install ViaVersion and, if needed, ViaBackwards on the proxy. Check their compatibility with your selected Minecraft release before enabling them.
7. Copy `server/velocity.toml` to the Velocity directory. Keep modern forwarding enabled, and set the same secret in Velocity's forwarding secret file and Paper's config.
8. Start both services with `./start.sh` after setting `JAVA25_HOME` if `java` does not already point to Java 25.

Only Velocity should be exposed publicly: Java TCP `25565` and Bedrock UDP `19132`. Paper backend `127.0.0.1:25566`, admin panel `127.0.0.1:8765`, and RCON `127.0.0.1:25575` must not be opened to the internet. RCON is disabled by default.

## Runtime setup and commands

Paper plugin data is stored under `plugins/FBSMP/`. SQLite migrations are applied on startup. The plugin writes its first admin-panel token to `plugins/FBSMP/admin-token`; protect that file. The admin panel binds to loopback on port `8765`, has expiring sessions, a CSRF token, login throttling, and a loopback/Host check. It provides server-record and economy views plus an on-demand database backup.

Implemented player commands:

- `/bal`, `/balance`, `/baltop`, `/pay`
- `/sethome`, `/home`, `/homes`, `/delhome`
- `/warp`, `/rtpq`, `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`
- `/msg`, `/tell`, `/w`, `/reply`, `/r`, `/ignore`
- `/stats`, `/settings`, `/afk`, `/shop`
- `/fbsmp version`, `/fbsmp calendar`, `/fbsmp backup`, `/fbsmp reload`, `/fbsmp debug`, `/fbsmp warp`

The SQLite schema also defines persistent auction, loan, mortgage, order, moderation, and calendar records. Their full command workflows and inventory GUIs are still under development; do not treat those modules as production-ready yet.

## Configuration

Defaults and examples are under `fbsmp-paper/src/main/resources/`. The plugin copies them into its data folder on first startup. Change gameplay settings there after the first run; use `/fbsmp reload` for the main config.

Database writes use prepared statements and SQLite transactions. The economy is stored as integer minor units (one hundredth of a ₣฿). Player transfers are atomic and create transaction IDs. The custom calendar stores its start time in SQLite, uses 30-day months, and supports the configured 29-day special month.

## Backups and recovery

- In-game: `/fbsmp backup` runs a consistent SQLite `VACUUM INTO` snapshot, then applies the configured retention count.
- Offline CLI: `./backup.sh` uses `sqlite3 .backup` and requires the sqlite3 command to be installed.
- Restore only while both Paper and Velocity are stopped. Preserve a copy of the current database and plugin configs before replacing them.
- Use `./stop.sh` for orderly shutdown. It sends TERM and will not force-kill a process that does not stop in 30 seconds.

## Termux

Install a Java 25 distribution available for the device, set `JAVA25_HOME` to its installation directory, place the Paper/Velocity jars in `server/`, and use `bash start.sh` / `bash stop.sh`. Keep device firewall and router rules limited to the public proxy ports. Android background-process limits can stop servers when Termux is suspended; use an appropriate foreground/wake-lock setup.

## Notes

This source tree does not redistribute Paper, Velocity, Geyser, Floodgate, or ViaVersion binaries. Download each from its official distribution and confirm release compatibility before use. Never commit forwarding secrets, Floodgate keys, RCON passwords, or the generated admin token.