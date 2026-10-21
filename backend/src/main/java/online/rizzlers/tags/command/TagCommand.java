package online.rizzlers.tags.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.tags.config.TagConfig;
import online.rizzlers.tags.database.TagDatabaseManager;
import online.rizzlers.tags.models.RoleInfo;
import online.rizzlers.tags.tag.NametagManager;
import online.rizzlers.tags.tag.TagManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TagCommand implements CommandExecutor, TabCompleter {
    public static final String PERM_ADMIN = "rizzlerstags.admin";
    public static final String PERM_USE = "rizzlerstags.use";

    private final TagConfig config;
    private final TagManager tagManager;
    private final TagDatabaseManager databaseManager;
    private final NametagManager nametagManager;
    private final Runnable reloadAction;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TagCommand(TagConfig config,
                      TagManager tagManager,
                      TagDatabaseManager databaseManager,
                      NametagManager nametagManager,
                      Runnable reloadAction) {
        this.config = config;
        this.tagManager = tagManager;
        this.databaseManager = databaseManager;
        this.nametagManager = nametagManager;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("list".equals(sub)) {
            handleList(sender);
            return true;
        }

        if ("status".equals(sub)) {
            handleStatus(sender, args);
            return true;
        }

        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(miniMessage.deserialize("<red>You do not have permission for administrative tag commands.</red>"));
            return true;
        }

        switch (sub) {
            case "grant" -> handleGrant(sender, args);
            case "revoke" -> handleRevoke(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<gradient:#ff4fd8:#7c4dff><bold>Available Network Ranks & Tags:</bold></gradient>"));
        for (Map.Entry<String, RoleInfo> entry : config.getRoles().entrySet()) {
            RoleInfo r = entry.getValue();
            sender.sendMessage(miniMessage.deserialize(String.format(
                    "<gray>• </gray><white>%s</white> <gray>»</gray> <color:%s>%s</color> <gray>(Priority: </gray><yellow>%d</yellow><gray>)</gray>",
                    r.key(), r.color(), r.tag(), r.priority()
            )));
        }
    }

    private void handleStatus(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(miniMessage.deserialize("<red>You do not have permission to inspect other players' tags.</red>"));
                return;
            }
            target = Bukkit.getPlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>Console must specify a player: /tag status <player></red>"));
            return;
        }

        if (target == null) {
            sender.sendMessage(miniMessage.deserialize("<red>Player not found online.</red>"));
            return;
        }

        Set<String> roles = databaseManager.getPlayerRolesAndRanks(target.getUniqueId());
        RoleInfo active = tagManager.getHighestPriorityTag(roles);

        sender.sendMessage(miniMessage.deserialize(String.format("""
            <gradient:#ff4fd8:#7c4dff><bold>Tag Status for %s:</bold></gradient>
            <gray>• Active Display Tag:</gray> <color:%s><bold>%s</bold></color> <gray>(Priority: </gray><yellow>%d</yellow><gray>)</gray>
            <gray>• Assigned Keys:</gray> <white>%s</white>
            """,
                target.getName(),
                active.color(),
                active.tag(),
                active.priority(),
                String.join(", ", roles)
        )));
    }

    private void handleGrant(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /tag grant <player> <rank></yellow>"));
            return;
        }

        String targetName = args[1];
        String rankKey = args[2].toLowerCase(Locale.ROOT);

        if (!config.getRoles().containsKey(rankKey)) {
            sender.sendMessage(miniMessage.deserialize("<red>Unknown rank key: '" + rankKey + "'. Use /tag list to view available ranks.</red>"));
            return;
        }

        Player onlineTarget = Bukkit.getPlayer(targetName);
        UUID targetUuid = onlineTarget != null ? onlineTarget.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        try {
            databaseManager.grantIngameRank(targetUuid, rankKey, sender.getName());
            tagManager.invalidate(targetUuid);
            if (onlineTarget != null) {
                tagManager.resolvePlayerTag(targetUuid);
                nametagManager.onPlayerSneakToggle(onlineTarget, onlineTarget.isSneaking());
            }
            sender.sendMessage(miniMessage.deserialize("<green>Successfully granted rank '" + rankKey + "' to " + targetName + ".</green>"));
        } catch (SQLException e) {
            sender.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
        }
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /tag revoke <player> <rank></yellow>"));
            return;
        }

        String targetName = args[1];
        String rankKey = args[2].toLowerCase(Locale.ROOT);

        Player onlineTarget = Bukkit.getPlayer(targetName);
        UUID targetUuid = onlineTarget != null ? onlineTarget.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        try {
            boolean ok = databaseManager.revokeIngameRank(targetUuid, rankKey);
            tagManager.invalidate(targetUuid);
            if (onlineTarget != null) {
                tagManager.resolvePlayerTag(targetUuid);
                nametagManager.onPlayerSneakToggle(onlineTarget, onlineTarget.isSneaking());
            }
            if (ok) {
                sender.sendMessage(miniMessage.deserialize("<green>Successfully revoked rank '" + rankKey + "' from " + targetName + ".</green>"));
            } else {
                sender.sendMessage(miniMessage.deserialize("<yellow>Player did not hold rank '" + rankKey + "'.</yellow>"));
            }
        } catch (SQLException e) {
            sender.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
        }
    }

    private void handleReload(CommandSender sender) {
        if (reloadAction != null) reloadAction.run();
        sender.sendMessage(miniMessage.deserialize("<green>RizzlersTags configuration reloaded.</green>"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("""
            <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS Tags Commands:</bold></gradient>
            <gray>• </gray><white>/tag list</white> <gray>— View all available ranks and tags</gray>
            <gray>• </gray><white>/tag status [player]</white> <gray>— View active tag & priority</gray>
            <gray>• </gray><white>/tag grant <player> <rank></white> <gray>— Grant in-game rank</gray>
            <gray>• </gray><white>/tag revoke <player> <rank></white> <gray>— Revoke in-game rank</gray>
            <gray>• </gray><white>/tag reload</white> <gray>— Reload config.yml</gray>
            """));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            list.add("list");
            list.add("status");
            if (sender.hasPermission(PERM_ADMIN)) {
                list.add("grant");
                list.add("revoke");
                list.add("reload");
            }
            return list.stream().filter(s -> s.startsWith(p)).toList();
        }

        if (args.length == 2 && ("grant".equalsIgnoreCase(args[0]) || "revoke".equalsIgnoreCase(args[0]) || "status".equalsIgnoreCase(args[0]))) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(p)).toList();
        }

        if (args.length == 3 && ("grant".equalsIgnoreCase(args[0]) || "revoke".equalsIgnoreCase(args[0]))) {
            String p = args[2].toLowerCase(Locale.ROOT);
            return config.getRoles().keySet().stream().filter(k -> k.startsWith(p)).toList();
        }

        return Collections.emptyList();
    }
}
