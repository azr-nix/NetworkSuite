# RIZZLERS Network Suite

A unified network security, Discord identity verification, proximity nametag, and chat prefix system designed for the **RIZZLERS** Minecraft network running **Velocity 4.3** and **Purpur/Paper 26.3** on **Java 25**.

---

## Architecture & Split Modules

The suite is divided into two specialized runtime plugins:

1. **`RizzlersProxy.jar`** *(installed on Velocity proxy only)*
   - Network-wide pre-login gatekeeper (`LoginEvent` with async continuation semantics).
   - Strict role decision precedence: **Blacklist Deny (Priority 1) -> Required Access Role (Priority 2) -> Allow (Priority 3)**.
   - Dual-side proof of control linking via 6-character hashed codes and Discord `/link` slash command.
   - Discord audit embed logging for joins, quits, denies, linking, and admin commands.
   - Role cache in shared database for backend synchronization.
2. **`RizzlersTags.jar`** *(installed on backend servers: Lobby & Survival)*
   - Synchronizes Discord roles and in-game ranks from the shared database.
   - In-game rank management commands (`/tag grant`, `/tag revoke`, `/tag list`).
   - Per-server proximity nametag distance: **16 blocks on Lobby, 24 blocks on Survival**.
   - Sneak hiding: Overhead nametag hides while sneaking, restores when standing.
   - Secure chat formatting: Strictly escapes user chat input to prevent MiniMessage format injection.
   - Direct connection protection to ensure backends are only joined through the proxy.

---

## Installation & Deployment (Pelican / Plugged.host)

### 1. Velocity Proxy Installation (`RizzlersProxy.jar`)
1. Download `RizzlersProxy.jar` from GitHub Releases or workflow artifacts.
2. Stop the Velocity proxy server.
3. Upload `RizzlersProxy.jar` into the proxy's `plugins/` folder.
4. Set environment variables in the Pelican panel:
   - `RIZZLERS_DISCORD_TOKEN`: Discord Bot Token.
   - `RIZZLERS_DB_USER`: Database username.
   - `RIZZLERS_DB_PASSWORD`: Database password.
5. Configure `plugins/RizzlersProxy/config.yml` with your Discord guild and role IDs.
6. Start the proxy.

### 2. Backend Servers Installation (`RizzlersTags.jar`)
1. Download `RizzlersTags.jar`.
2. Stop the backend server (**Lobby** or **Main Survival**).
3. Upload `RizzlersTags.jar` into `plugins/`.
4. Configure `plugins/RizzlersTags/config.yml`:
   - Set `server-name: lobby` (viewing-distance-blocks: 16) on Lobby.
   - Set `server-name: survival` (viewing-distance-blocks: 24) on Survival.
5. Set `RIZZLERS_DB_USER` and `RIZZLERS_DB_PASSWORD` in the server's environment variables.
6. Start the server.

---

## Rollback Instructions
- **Proxy**: Remove `plugins/RizzlersProxy.jar` and restart Velocity.
- **Backend**: Remove `plugins/RizzlersTags.jar` and restart the backend server.
