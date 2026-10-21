package online.rizzlers.tags;

import online.rizzlers.tags.command.TagCommand;
import online.rizzlers.tags.config.TagConfig;
import online.rizzlers.tags.database.TagDatabaseManager;
import online.rizzlers.tags.listener.ChatListener;
import online.rizzlers.tags.listener.SecurityListener;
import online.rizzlers.tags.tag.NametagManager;
import online.rizzlers.tags.tag.TagManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class RizzlersTags extends JavaPlugin {
    private TagConfig config;
    private TagDatabaseManager databaseManager;
    private TagManager tagManager;
    private NametagManager nametagManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();

        try {
            this.databaseManager = new TagDatabaseManager(config, getDataFolder(), getLogger());
            getLogger().info("Connected to shared RIZZLERS database (Driver: " + config.getDbDriver().toUpperCase() + ").");
        } catch (Exception e) {
            getLogger().severe("CRITICAL: Failed to connect to shared database: " + e.getMessage());
        }

        this.tagManager = new TagManager(config, databaseManager);
        this.nametagManager = new NametagManager(config, tagManager, getLogger());

        // Register listeners
        getServer().getPluginManager().registerEvents(new ChatListener(tagManager, nametagManager, this), this);
        getServer().getPluginManager().registerEvents(new SecurityListener(getLogger(), true), this);

        // Register command
        PluginCommand cmd = getCommand("tag");
        if (cmd != null) {
            TagCommand executor = new TagCommand(config, tagManager, databaseManager, nametagManager, this::reload);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Schedule periodic proximity nametag update task
        Bukkit.getScheduler().runTaskTimer(this, nametagManager::updateAll,
                config.getUpdateIntervalTicks(), config.getUpdateIntervalTicks());

        getLogger().info("RizzlersTags enabled for server '" + config.getServerName()
                + "' (Viewing distance: " + config.getViewingDistanceBlocks() + " blocks).");
    }

    public void reload() {
        reloadConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        this.config = TagConfig.loadFromFile(configFile);
        if (tagManager != null) {
            // refresh active tags
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                tagManager.invalidate(p.getUniqueId());
            }
        }
        getLogger().info("RizzlersTags configuration reloaded.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("RizzlersTags disabled.");
    }

    public TagConfig getTagConfig() { return config; }
    public TagDatabaseManager getDatabaseManager() { return databaseManager; }
    public TagManager getTagManager() { return tagManager; }
    public NametagManager getNametagManager() { return nametagManager; }
}
