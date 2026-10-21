# RIZZLERS Network Suite — Staging & Deployment Checklist

Use this checklist on a staging proxy and backend server before promoting changes to production.

---

## 1. Pre-Deployment Configuration
- [ ] **Database Connection**: Create database `rizzlers` in MariaDB/MySQL and execute `database/schema.sql`.
- [ ] **Environment Variables**:
  - Velocity Proxy: Set `RIZZLERS_DISCORD_TOKEN`, `RIZZLERS_DB_USER`, `RIZZLERS_DB_PASSWORD`.
  - Backend Servers: Set `RIZZLERS_DB_USER`, `RIZZLERS_DB_PASSWORD`.
- [ ] **Firewall / Proxy Forwarding**:
  - Ensure backend server ports (25566, 25567, etc.) are blocked by firewall to prevent direct public internet connections.
  - Enable Velocity modern forwarding with forwarding secret in `paper-global.yml` on Lobby and Survival.

---

## 2. Proxy Gateway Tests (`RizzlersProxy.jar`)
- [ ] **Unlinked Account**:
  - Connect with an unlinked Minecraft account.
  - Expect: Denied with kick message containing linking instructions.
- [ ] **Account Linking**:
  - Execute `/plog link` in-game.
  - Copy the 6-character code (valid for 5 minutes).
  - In Discord, run `/link code: <6-char-code>`.
  - Expect: Discord confirms successful link to your Minecraft player name and UUID.
- [ ] **Blacklist Role Precedence**:
  - Assign the blacklisted Discord role to your linked Discord user.
  - Attempt to join the proxy.
  - Expect: Denied immediately with blacklist message, even if you also hold a VIP/Staff access role.
- [ ] **Access Role Verification**:
  - Remove blacklist role and ensure you have the verified member access role.
  - Connect to proxy.
  - Expect: Connection allowed and routed to the default server (Lobby).
- [ ] **Rate Limiting & Anti-Replay**:
  - Try using the same link code twice in Discord -> Expect: Rejected as expired/used.
  - Generate more than 5 codes in an hour -> Expect: Blocked with rate-limit warning.

---

## 3. Backend Tags & Nametag Tests (`RizzlersTags.jar`)
- [ ] **Proximity Distance**:
  - On **Lobby**: Check overhead nametag at 10 blocks (visible), 16 blocks (visible), and 17+ blocks (hidden).
  - On **Survival**: Check overhead nametag at 20 blocks (visible), 24 blocks (visible), and 25+ blocks (hidden).
- [ ] **Sneak Visibility**:
  - When target player sneaks, overhead nametag hides immediately from viewers.
  - When target player stands back up, nametag restores cleanly.
- [ ] **Chat Formatting & Security**:
  - Send chat message in-game: displays formatted prefix `<tag> <player>: <message>`.
  - Send message containing MiniMessage tags (e.g. `<gradient:red:blue>hacked</gradient>`):
    - Expect: Tags are safely escaped and printed as literal text without executing.
- [ ] **In-Game Rank Commands**:
  - Grant in-game rank: `/tag grant <player> pvp_master`.
  - Check `/tag status <player>`: Displays `[PVP MASTER]` (priority 25).
  - Revoke rank: `/tag revoke <player> pvp_master`.
  - Check `/tag status <player>`: Restores Discord role tag.
