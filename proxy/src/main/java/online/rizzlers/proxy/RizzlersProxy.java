package online.rizzlers.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import online.rizzlers.proxy.access.AccessEvaluator;
import online.rizzlers.proxy.command.PlogCommand;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.discord.JdaDiscordService;
import online.rizzlers.proxy.listener.LoginListener;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "rizzlersproxy",
        name = "RizzlersProxy",
        version = "1.0.0",
        description = "Network-wide gatekeeper, Discord role verification, and audit suite for RIZZLERS.",
        authors = {"RIZZLERS"}
)
public class RizzlersProxy {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ProxyConfig config;
    private ProxyDatabaseManager databaseManager;
    private JdaDiscordService discordService;
    private AccessEvaluator accessEvaluator;

    @Inject
    public RizzlersProxy(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("Initializing RizzlersProxy v1.0.0...");

        File configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try {
                if (!dataDirectory.toFile().exists()) dataDirectory.toFile().mkdirs();
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) Files.copy(in, configFile.toPath());
                }
            } catch (Exception e) {
                logger.error("Could not create default config: {}", e.getMessage());
            }
        }

        this.config = ProxyConfig.loadFromFile(configFile);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("RizzlersProxy");

        try {
            this.databaseManager = new ProxyDatabaseManager(config, dataDirectory.toFile(), jul);
            logger.info("Database persistence initialized (Driver: {}).", config.getDbDriver().toUpperCase());
        } catch (Exception e) {
            logger.error("CRITICAL: Database initialization failed: {}", e.getMessage(), e);
        }

        this.discordService = new JdaDiscordService(config, databaseManager, jul);
        this.accessEvaluator = new AccessEvaluator(config, databaseManager, discordService, jul);

        // Register event listener
        LoginListener listener = new LoginListener(config, accessEvaluator, databaseManager, discordService, jul);
        server.getEventManager().register(this, listener);

        // Register /plog command
        CommandManager cm = server.getCommandManager();
        CommandMeta meta = cm.metaBuilder("plog")
                .aliases("proxylogger", "rproxy")
                .plugin(this)
                .build();
        PlogCommand cmd = new PlogCommand(config, accessEvaluator, databaseManager, discordService, this::reload);
        cm.register(meta, cmd);

        logger.info("RizzlersProxy enabled. Pre-login network access verification is ACTIVE.");
    }

    public void reload() {
        File configFile = new File(dataDirectory.toFile(), "config.yml");
        this.config = ProxyConfig.loadFromFile(configFile);
        logger.info("RizzlersProxy configuration reloaded.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down RizzlersProxy...");
        if (discordService != null) discordService.close();
        if (databaseManager != null) databaseManager.close();
        logger.info("RizzlersProxy shutdown complete.");
    }

    public ProxyConfig getConfig() { return config; }
    public ProxyDatabaseManager getDatabaseManager() { return databaseManager; }
    public JdaDiscordService getDiscordService() { return discordService; }
    public AccessEvaluator getAccessEvaluator() { return accessEvaluator; }
}
