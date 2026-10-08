# FB-SMP Development Skill

Work on the existing FB-SMP Minecraft server.

- Minecraft: 26.1.2
- Java: 25
- Velocity proxy
- Paper survival
- Geyser + Floodgate
- Java + Bedrock crossplay
- Currency: ₣฿

IMPORTANT: Inspect the existing project before changing anything. Preserve working server files, worlds, plugins, configurations, startup scripts, and player data.

Use Java for Minecraft-native Paper/Velocity plugins. Use Python for OpenAI/API backend work where appropriate.

The OpenAI API key is stored in the environment variable OPENAI_API_KEY. Never hard-code, print, expose, or commit the key.

Do not unnecessarily rebuild the server. Make the smallest safe changes and test them.

Never expose secrets or give players access to shell commands, server console, private files, or API keys.

Keep Minecraft server performance safe: do API work asynchronously and never block the main server thread.

Preserve Java 25 compatibility and Java/Bedrock support.

Before finishing, report changed files, dependencies, configuration changes, startup changes, tests performed, and remaining errors.
