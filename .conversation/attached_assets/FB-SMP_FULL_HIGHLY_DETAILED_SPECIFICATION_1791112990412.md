# FB-SMP Full Highly Detailed Specification

This file contains the complete FB-SMP development specification for Replit AI.

Target: Minecraft 26.1.2, Java 25, Paper, Velocity 4.2.x, Geyser, Floodgate, ViaVersion/ViaBackwards.

Core systems: economy ₣฿, scoreboard, custom calendar, first-join spawn/starter kit, /sell, /shop, auctions, /tpa, /rtpq, homes, warps, loans, mortgages, gifts, community /orders, private messaging, /stats, /settings, /afk, moderation, admin commands, private admin panel, RCON, SQLite persistence, backups, security, crash recovery, Termux support, Java+Bedrock networking.

Requirements:
- Original implementation; do not copy proprietary code/assets/branding.
- No placeholders for core features.
- All persistent systems survive restart.
- Economy and item transfers must be atomic.
- GUIs must prevent duplication/item loss and validate all actions server-side.
- Backend Paper should remain localhost-only behind Velocity.
- Java public TCP: 25565; Bedrock UDP: 19132; Paper backend: 127.0.0.1:25566; RCON: 127.0.0.1:25575; admin panel: 127.0.0.1:8765.

Architecture:
1. Gradle multi-module project.
2. Paper survival backend.
3. Velocity proxy.
4. Geyser/Floodgate for Bedrock.
5. ViaVersion/ViaBackwards where compatible.
6. SQLite with schema migrations and prepared statements.
7. Private web admin panel.
8. Termux-compatible startup scripts using Java 25 explicitly.

Startup:
- Verify Java major version 25.
- Create directories.
- Start Paper.
- Wait for Paper readiness.
- Start Velocity.
- Log failures clearly.
- Never accidentally invoke Java 21 when Java 25 is required.

Economy:
- Currency symbol ₣฿.
- /bal, /balance, /baltop, /pay <player> <amount>.
- Positive amounts only; no self-pay; safe numeric type; transaction IDs; transaction logs.
- Prefer integer smallest units or BigDecimal, not floating point.

Calendar:
- Custom FB-SMP calendar.
- 30-day months and a configurable 29-day final/special month.
- Persistent server calendar start timestamp.
- Used by scoreboard, loans, mortgages, orders and logs.

First join:
- Generate a safe random Overworld spawn.
- Avoid lava, void, dangerous fall locations and unsafe blocks.
- Give a one-time chainmail starter kit with food, basic blocks, tools and torches.
- Do not include weapons.
- Persist starter-kit claim state.

/sell:
- GUI inventory.
- Item placement, total calculation and confirmation.
- Return unsold items on close.
- Atomic removal/payment.
- Protect shift-click, drag, double-click, number-key, drop and offhand exploits.
- Configurable sell_prices.yml.

/shop:
- Categories for building, farming, mining, decoration, utility, food and miscellaneous.
- Buy quantities and validate funds/inventory before committing.

Auction house:
- /ausell, /aushop, /ah.
- Persistent listings, pagination, seller, quantity, price and expiration.
- Atomic purchase/cancellation.
- Concurrent-purchase protection.
- Restart-safe state.

Teleport:
- /tpa, /tpahere, /tpaccept, /tpdeny.
- Expiration, cooldowns, optional delay, movement cancellation.
- /rtpq only; remove/avoid unwanted /donutsmp-rtp-rtp.
- Safe random teleport algorithm.

Homes/warps:
- /sethome [name], /home [name], /delhome [name], /homes.
- Persistent safe locations and permission-based limits.
- /warp GUI.
- Admin /fbsmp warp create/delete/set.

Loans:
- /loan <amount>, /loan vu.
- Principal, interest, total, due date and status.
- Configurable limits, interest, repayment period and overdue rules.
- Custom calendar due dates.
- Persistent and atomic.

Mortgages:
- /mort and /mortvu.
- Property/asset selection, down payment, financed amount, interest, installments, due dates and status.
- GUI controls including red decrease and blue increase buttons, confirm/cancel.
- Prevent duplicate ownership and payouts.

Gifts:
- /gift with target selection, confirmation and safe item/currency transfer.

Orders:
- /orders is a real community delivery system.
- Create, view, claim, deliver, complete, cancel and expire orders.
- Example: request 64 iron for ₣฿1500.
- States OPEN, CLAIMED, DELIVERED, COMPLETED, CANCELLED, EXPIRED.
- Lock orders when claimed; verify delivery; pay only after verification.

Messaging:
- /msg, /tell, /w, /reply, /r, /ignore.
- Persistent ignore list and last-message target.
- Do not publish private messages to public chat.

Player systems:
- /stats: playtime, blocks, deaths, economy statistics, auction/order statistics and calendar date.
- /settings: persistent GUI toggles for scoreboard, notifications, sounds and preferences.
- /afk plus automatic AFK after configurable inactivity.

Moderation:
- /ban, /kick, /timeout.
- Permissions, duration parsing, reasons, persistent records and staff logs.

Admin:
- /fbsmp reload, debug, version, player, economy, database, warp management, calendar and backup.
- Permission-protected.

Admin web panel:
- Bind to 127.0.0.1:8765 by default.
- Status, players, performance, economy, transactions, loans, mortgages, auctions, orders, moderation, logs, backups and configuration.
- Authentication, session expiration, CSRF protection and rate limiting.
- Never expose it publicly by default.

RCON:
- If enabled, bind to localhost only.
- Strong secret loaded from environment/ignored config.
- Never commit credentials.

Configuration files:
- economy.yml
- sell_prices.yml
- shop.yml
- auction.yml
- teleport.yml
- homes.yml
- warps.yml
- loans.yml
- mortgages.yml
- orders.yml
- gifts.yml
- calendar.yml
- settings.yml
- moderation.yml
- messages.yml

Security:
- Prepared SQL statements.
- Validate all command input.
- Validate GUI actions server-side.
- Use GUI session IDs.
- Handle disconnects/restarts during transactions.
- Never expose passwords, Floodgate keys or RCON secrets.
- Rate-limit spam-prone commands.

Performance:
- No database queries every tick.
- Cache appropriate data.
- Use asynchronous database operations where safe.
- Main-thread API access only when required.

Backups:
- Database, configs and important plugin data.
- Manual /fbsmp backup.
- Optional scheduled backups and retention.

Testing:
Test startup, Java and Bedrock joining, first join, economy, sell, shop, auction, teleport, RTP, homes, warps, loans, mortgages, gifts, orders, messaging, stats, settings, AFK, moderation, admin commands, panel, backups, restart recovery, GUI exploits, concurrency and invalid inputs.

README must explain installation, Java 25, Gradle build, Paper, Velocity, Geyser, Floodgate, ViaVersion, configuration, commands, permissions, startup/shutdown, backup/restore, Termux, public TCP/UDP networking and troubleshooting.

Final deliverables:
- Full source code.
- Gradle project.
- Compiled plugin JAR.
- Proxy/Paper/Geyser configuration.
- Database migration system.
- Admin panel.
- Startup scripts.
- Backup system.
- README.
- Configuration examples.
- Tests.
- Final ZIP.

Final instruction to Replit AI:
Actually build and test the project. Do not merely describe it. When a compile/runtime error occurs, inspect the real error, fix the root cause, rebuild and retest. Use APIs compatible with Minecraft 26.1.2 and Java 25. Do not leave TODOs for core systems. The finished FB-SMP must be a polished original Java + Bedrock SMP system with persistent economy and all specified systems working together.
