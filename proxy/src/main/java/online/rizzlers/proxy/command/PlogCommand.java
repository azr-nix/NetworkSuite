package online.rizzlers.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.proxy.access.AccessEvaluator;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.discord.JdaDiscordService;
import online.rizzlers.proxy.models.AuditEvent;
import online.rizzlers.proxy.models.LinkedAccount;
import online.rizzlers.proxy.models.RoleRule;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class PlogCommand implements SimpleCommand {
    public static final String PERM_ADMIN = "rizzlersnetwork.admin";
    public static final String PERM_LINK = "rizzlersnetwork.link";

    private final ProxyConfig config;
    private final AccessEvaluator accessEvaluator;
    private final ProxyDatabaseManager databaseManager;
    private final JdaDiscordService discordService;
    private final Runnable reloadAction;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PlogCommand(ProxyConfig config,
                       AccessEvaluator accessEvaluator,
                       ProxyDatabaseManager databaseManager,
                       JdaDiscordService discordService,
                       Runnable reloadAction) {
        this.config = config;
        this.accessEvaluator = accessEvaluator;
        this.databaseManager = databaseManager;
        this.discordService = discordService;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("link".equals(sub)) {
            if (args.length >= 3 && "status".equalsIgnoreCase(args[1]) && source.hasPermission(PERM_ADMIN)) {
                handleLinkStatus(source, args[2]);
                return;
            }
            handleLink(source);
            return;
        }

        if ("unlink".equals(sub)) {
            handleUnlink(source, args);
            return;
        }

        if (!source.hasPermission(PERM_ADMIN)) {
            source.sendMessage(miniMessage.deserialize("<red>You do not have permission for administrative commands.</red>"));
            return;
        }

        switch (sub) {
            case "status" -> handleStatus(source);
            case "reload" -> handleReload(source);
            case "log" -> handleLog(source, args);
            case "whitelist" -> handleWhitelist(source, args);
            case "blacklist" -> handleBlacklist(source, args);
            default -> sendHelp(source);
        }
    }

    private void handleLink(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(miniMessage.deserialize("<red>Only players can generate a link code.</red>"));
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String accountType = player.isOnlineMode() ? "ONLINE" : "OFFLINE";

        try {
            String code = accessEvaluator.generateLinkCode(uuid, username, accountType);
            Component msg = miniMessage.deserialize(String.format("""
                <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS ACCOUNT LINKING</bold></gradient>
                <gray>Your temporary 1-time link code is: </gray><yellow><bold>%s</bold></yellow>
                <gray>Run </gray><click:suggest_command:'/link %s'><hover:show_text:'Click to copy'><white>/link %s</white></hover></click><gray> in Discord.</gray>
                <dark_gray>Valid for 5 minutes. Account type: %s.</dark_gray>
                """, code, code, code, accountType));
            player.sendMessage(msg);
        } catch (IllegalStateException e) {
            player.sendMessage(miniMessage.deserialize("<red>" + e.getMessage() + "</red>"));
        } catch (Exception e) {
            player.sendMessage(miniMessage.deserialize("<red>Failed to generate link code: " + e.getMessage() + "</red>"));
        }
    }

    private void handleLinkStatus(CommandSource source, String target) {
        try {
            Optional<LinkedAccount> opt;
            if (target.contains("-") && target.length() == 36) {
                opt = databaseManager.getLinkedAccount(UUID.fromString(target));
            } else {
                opt = databaseManager.getLinkedAccountByDiscordId(target);
            }

            if (opt.isPresent()) {
                LinkedAccount acc = opt.get();
                source.sendMessage(miniMessage.deserialize(String.format("""
                    <gradient:#ff4fd8:#7c4dff><bold>Link Status: %s</bold></gradient>
                    <gray>• UUID:</gray> <white>%s</white>
                    <gray>• Discord ID:</gray> <white>%s</white>
                    <gray>• Account Type:</gray> <white>%s</white>
                    <gray>• Status:</gray> <green>%s</green>
                    """, acc.minecraftUsername(), acc.minecraftUuid(), acc.discordId(), acc.accountType(), acc.linkStatus())));
            } else {
                source.sendMessage(miniMessage.deserialize("<yellow>No link record found for target: " + target + "</yellow>"));
            }
        } catch (Exception e) {
            source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
        }
    }

    private void handleUnlink(CommandSource source, String[] args) {
        if (args.length > 1 && source.hasPermission(PERM_ADMIN)) {
            String target = args[1];
            try {
                boolean ok;
                if (target.contains("-") && target.length() == 36) {
                    ok = databaseManager.unlinkAccount(UUID.fromString(target));
                } else {
                    ok = databaseManager.unlinkAccountByDiscordId(target);
                }
                if (ok) {
                    source.sendMessage(miniMessage.deserialize("<green>Successfully unlinked target: " + target + "</green>"));
                    databaseManager.recordAudit(AuditEvent.create(null, null, target, config.getGuildId(), "ADMIN_UNLINK", "Unlinked by admin", "PROXY"));
                } else {
                    source.sendMessage(miniMessage.deserialize("<red>Target not found or already unlinked.</red>"));
                }
            } catch (Exception e) {
                source.sendMessage(miniMessage.deserialize("<red>Error: " + e.getMessage() + "</red>"));
            }
            return;
        }

        if (source instanceof Player player) {
            try {
                boolean ok = databaseManager.unlinkAccount(player.getUniqueId());
                if (ok) player.sendMessage(miniMessage.deserialize("<green>Your Discord link has been disconnected.</green>"));
                else player.sendMessage(miniMessage.deserialize("<yellow>You have no active link.</yellow>"));
            } catch (Exception e) {
                player.sendMessage(miniMessage.deserialize("<red>Error unlinking: " + e.getMessage() + "</red>"));
            }
        }
    }

    private void handleStatus(CommandSource source) {
        int count = databaseManager.countLinkedAccounts();
        boolean discordReady = discordService != null && discordService.isReady();

        source.sendMessage(miniMessage.deserialize(String.format("""
            <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS Proxy Status</bold></gradient>
            <gray>• Database:</gray> <white>%s</white> <gray>(Linked accounts: </gray><green>%d</green><gray>)</gray>
            <gray>• Discord Bot:</gray> %s
            <gray>• Guild ID:</gray> <white>%s</white>
            <gray>• Log Channel:</gray> <white>%s</white>
            <gray>• Blacklist Role:</gray> <white>%s</white>
            """,
                config.getDbDriver().toUpperCase(),
                count,
                discordReady ? "<green>Connected</green>" : "<red>Degraded / Offline</red>",
                config.getGuildId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getGuildId(),
                config.getLogChannelId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getLogChannelId(),
                config.getBlacklistRoleId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getBlacklistRoleId()
        )));
    }

    private void handleReload(CommandSource source) {
        if (reloadAction != null) reloadAction.run();
        source.sendMessage(miniMessage.deserialize("<green>RizzlersProxy configuration reloaded successfully.</green>"));
        databaseManager.recordAudit(AuditEvent.create(null, null, null, config.getGuildId(), "ADMIN_RELOAD", "Config reloaded", "PROXY"));
    }

    private void handleLog(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog log <guild_id> <channel_id></yellow>"));
            return;
        }
        config.setGuildId(args[1].trim());
        config.setLogChannelId(args[2].trim());
        source.sendMessage(miniMessage.deserialize("<green>Discord log destination set to Guild " + args[1] + ", Channel " + args[2] + "</green>"));
    }

    private void handleWhitelist(CommandSource source, String[] args) {
        if (args.length >= 2 && "list".equalsIgnoreCase(args[1])) {
            try {
                List<RoleRule> rules = databaseManager.getRoleRules("ACCESS_ALLOW");
                source.sendMessage(miniMessage.deserialize("<gradient:#ff4fd8:#7c4dff><bold>Configured Access Whitelist Roles:</bold></gradient>"));
                for (String r : config.getAllowedRoleIds()) {
                    source.sendMessage(miniMessage.deserialize("<gray>• Config Role: </gray><yellow>" + r + "</yellow>"));
                }
                for (RoleRule r : rules) {
                    source.sendMessage(miniMessage.deserialize("<gray>• DB Rule: </gray><yellow>" + r.roleId() + "</yellow> <gray>(Guild: " + r.guildId() + ")</gray>"));
                }
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
            }
            return;
        }

        if (args.length >= 4) {
            String action = args[1].toLowerCase(Locale.ROOT);
            String guildId = args[2];
            String roleId = args[3];

            try {
                if ("add".equals(action)) {
                    databaseManager.addRoleRule(guildId, roleId, "ACCESS_ALLOW", "ACCESS", null, 0);
                    config.addAllowedRoleId(roleId);
                    source.sendMessage(miniMessage.deserialize("<green>Added access whitelist role " + roleId + " for guild " + guildId + "</green>"));
                } else if ("remove".equals(action)) {
                    databaseManager.removeRoleRule(guildId, roleId, "ACCESS_ALLOW");
                    config.removeAllowedRoleId(roleId);
                    source.sendMessage(miniMessage.deserialize("<green>Removed access whitelist role " + roleId + "</green>"));
                } else {
                    source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog whitelist <add|remove|list> [guild] [role]</yellow>"));
                }
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
            }
            return;
        }

        source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog whitelist <add|remove|list> [guild] [role]</yellow>"));
    }

    private void handleBlacklist(CommandSource source, String[] args) {
        if (args.length >= 4 && "set".equalsIgnoreCase(args[1])) {
            String guildId = args[2];
            String roleId = args[3];
            try {
                databaseManager.addRoleRule(guildId, roleId, "ACCESS_DENY", "BLACKLIST", null, 1000);
                config.setBlacklistRoleId(roleId);
                source.sendMessage(miniMessage.deserialize("<green>Set blacklist role to " + roleId + " for guild " + guildId + "</green>"));
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
            }
            return;
        }
        source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog blacklist set <guild> <role></yellow>"));
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(miniMessage.deserialize("""
            <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS Proxy Commands</bold></gradient>
            <gray>• </gray><white>/plog link</white> <gray>— Generate temporary link code</gray>
            <gray>• </gray><white>/plog unlink</white> <gray>— Disconnect Discord link</gray>
            <gray>• </gray><white>/plog status</white> <gray>— View proxy gatekeeper status</gray>
            <gray>• </gray><white>/plog reload</white> <gray>— Reload config.yml</gray>
            <gray>• </gray><white>/plog log <guild> <channel></white> <gray>— Set Discord audit channel</gray>
            <gray>• </gray><white>/plog whitelist <add|remove|list></white> <gray>— Manage allow roles</gray>
            <gray>• </gray><white>/plog blacklist set <guild> <role></white> <gray>— Set blacklist role</gray>
            <gray>• </gray><white>/plog link status <player></white> <gray>— Check account link status</gray>
            """));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();

        if (args.length <= 1) {
            String p = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> list = new ArrayList<>();
            if (source.hasPermission(PERM_ADMIN)) {
                list.addAll(List.of("status", "reload", "log", "whitelist", "blacklist", "link", "unlink"));
            } else {
                list.addAll(List.of("link", "unlink"));
            }
            return list.stream().filter(s -> s.startsWith(p)).toList();
        }

        if (args.length == 2 && "whitelist".equalsIgnoreCase(args[0])) {
            return List.of("add", "remove", "list").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }

        if (args.length == 2 && "blacklist".equalsIgnoreCase(args[0])) {
            return List.of("set").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }

        return Collections.emptyList();
    }
}
