package online.rizzlers.proxy.access;

import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.discord.DiscordRoleProvider;
import online.rizzlers.proxy.discord.JdaDiscordService;
import online.rizzlers.proxy.models.LinkedAccount;
import online.rizzlers.proxy.models.RoleRule;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AccessEvaluator {
    private final ProxyConfig config;
    private final ProxyDatabaseManager databaseManager;
    private final DiscordRoleProvider discordRoleProvider;
    private final Logger logger;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<UUID, List<Instant>> rateLimits = new ConcurrentHashMap<>();

    public AccessEvaluator(ProxyConfig config,
                           ProxyDatabaseManager databaseManager,
                           DiscordRoleProvider discordRoleProvider,
                           Logger logger) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.discordRoleProvider = discordRoleProvider;
        this.logger = logger;
    }

    public AccessDecision evaluateAccess(UUID uuid, String username, String accountType) {
        // 1. Check link status
        Optional<LinkedAccount> optLinked;
        try {
            optLinked = databaseManager.getLinkedAccount(uuid);
        } catch (SQLException e) {
            if (logger != null) logger.severe("Database error evaluating access for " + username + ": " + e.getMessage());
            if ("DENY".equalsIgnoreCase(config.getDiscordApiFailurePolicy())) {
                return AccessDecision.deny("DB_ERROR", config.getMissingAccessRoleMessage(), "Database query failed: " + e.getMessage());
            } else {
                return AccessDecision.allow("Allowed on DB error by policy");
            }
        }

        if (optLinked.isEmpty() || !optLinked.get().isLinked()) {
            if (config.isRequireLinkedAccount()) {
                return AccessDecision.deny("UNLINKED", config.getUnlinkedMessage(), "Player is not linked to Discord");
            } else {
                return AccessDecision.allow("Unlinked allowed by configuration");
            }
        }

        LinkedAccount linked = optLinked.get();
        String discordId = linked.discordId();
        String guildId = config.getGuildId();

        // 2. Fetch Discord roles (cache-first, then REST)
        Set<String> roles;
        Optional<Set<String>> cached = databaseManager.getCachedDiscordRoles(discordId, guildId, config.getRoleCacheSeconds());
        if (cached.isPresent()) {
            roles = cached.get();
        } else {
            try {
                roles = discordRoleProvider.getMemberRoles(guildId, discordId);
                databaseManager.cacheDiscordRoles(discordId, guildId, roles);
            } catch (Exception e) {
                if (logger != null) logger.warning("Discord role API failure for " + discordId + ": " + e.getMessage());
                if ("DENY".equalsIgnoreCase(config.getDiscordApiFailurePolicy())) {
                    return AccessDecision.deny("DISCORD_ERROR", config.getMissingAccessRoleMessage(), "Discord API error: " + e.getMessage());
                } else {
                    return AccessDecision.allow("Allowed on Discord error by policy");
                }
            }
        }

        // 3. Blacklist Role Check FIRST (precedence over allow roles)
        Set<String> blacklistRoles = getEffectiveBlacklistRoles(guildId);
        for (String r : roles) {
            if (blacklistRoles.contains(r)) {
                return AccessDecision.deny("BLACKLIST", config.getBlacklistDenialMessage(), "Possesses blacklisted role: " + r);
            }
        }

        // 4. Access Role Check SECOND
        Set<String> allowRoles = getEffectiveAllowRoles(guildId);
        if (!allowRoles.isEmpty()) {
            boolean hasAllow = false;
            for (String r : roles) {
                if (allowRoles.contains(r)) {
                    hasAllow = true;
                    break;
                }
            }
            if (!hasAllow) {
                return AccessDecision.deny("MISSING_ROLE", config.getMissingAccessRoleMessage(), "Missing required access role");
            }
        }

        // 5. Allowed
        databaseManager.updateLastSeen(uuid);
        return AccessDecision.allow("Access verified for " + username + " (Account: " + accountType + ")");
    }

    public Set<String> getEffectiveBlacklistRoles(String guildId) {
        Set<String> roles = new HashSet<>();
        String cfgRole = config.getBlacklistRoleId();
        if (cfgRole != null && !cfgRole.isBlank()) roles.add(cfgRole);

        try {
            List<RoleRule> rules = databaseManager.getRoleRules("ACCESS_DENY");
            for (RoleRule r : rules) {
                if (r.guildId().equalsIgnoreCase(guildId) || r.guildId().isBlank()) {
                    roles.add(r.roleId());
                }
            }
        } catch (SQLException ignored) {}
        return roles;
    }

    public Set<String> getEffectiveAllowRoles(String guildId) {
        Set<String> roles = new HashSet<>(config.getAllowedRoleIds());
        try {
            List<RoleRule> rules = databaseManager.getRoleRules("ACCESS_ALLOW");
            for (RoleRule r : rules) {
                if (r.guildId().equalsIgnoreCase(guildId) || r.guildId().isBlank()) {
                    roles.add(r.roleId());
                }
            }
        } catch (SQLException ignored) {}
        return roles;
    }

    public String generateLinkCode(UUID uuid, String username, String accountType) throws SQLException {
        Instant now = Instant.now();
        Instant hourAgo = now.minusSeconds(3600);
        List<Instant> recent = rateLimits.computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        recent.removeIf(t -> t.isBefore(hourAgo));

        if (recent.size() >= config.getMaxAttemptsPerHour()) {
            throw new IllegalStateException("Link code limit reached (max " + config.getMaxAttemptsPerHour() + "/hour). Please wait.");
        }

        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        String code = sb.toString();
        String hash = JdaDiscordService.sha256(code);
        Instant expiresAt = now.plusSeconds(config.getCodeExpirySeconds());

        databaseManager.createLinkCode(uuid, hash, expiresAt);
        recent.add(now);
        return code;
    }

    public ProxyConfig getConfig() {
        return config;
    }

    public ProxyDatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
