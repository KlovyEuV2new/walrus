/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.checks.listener.CheckManagerListener;
import wtf.walrus.commands.CommandHandler;
import wtf.walrus.compat.VersionAdapter;
import wtf.walrus.config.*;
import wtf.walrus.datacollector.DataCollectorFactory;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.listeners.*;
import wtf.walrus.ml.Model;
import wtf.walrus.ml.client.LocalAIClientProvider;
import wtf.walrus.ml.managers.TrainingDataManager;
import wtf.walrus.ml.managers.VerdictManager;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.punishment.PunishmentManager;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.server.AIClientProvider;
import wtf.walrus.server.AnalyticsClient;
import wtf.walrus.session.ISessionManager;
import wtf.walrus.session.SessionManager;
import wtf.walrus.violation.ViolationManager;
import wtf.walrus.util.FeatureCalculator;
import wtf.walrus.util.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class Main extends JavaPlugin {

    public static Main instance = null;
    private Config config;
    private MenuConfig menuConfig;
    private MessagesConfig messagesConfig;
    private HologramConfig hologramConfig;
    private ISessionManager sessionManager;
    private FeatureCalculator featureCalculator;
    private TickListener tickListener;
    private HitListener hitListener;
    private BlockListener digListener;
    private RotationListener rotationListener;
    private PlayerListener playerListener;
    private TeleportListener teleportListener;
    private CommandHandler commandHandler;
    private AIClientProvider aiClientProvider;
    private AlertManager alertManager;
    private ViolationManager violationManager;
    private NametagManager nametagManager;
    public AICheck aiCheck;
    public MiningCheck miningCheck;
    private UpdateChecker updateChecker;
    private AnalyticsClient analyticsClient;
    private VerdictManager verdictManager;

    public MiningCheck getMiningCheck() {
        return miningCheck;
    }

    public TickListener getTickListener() {
        return tickListener;
    }

    private LocalAIClientProvider localAIClientProvider;

    private CheckManagerListener checkManagerListener;

    private PunishmentsConfig punishmentsConfig;
    private PunishmentManager punishmentManager;

    private PunishListener bListener;

    @Override
    public void onLoad() {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isLoaded()) {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings()
                    .reEncodeByDefault(false)
                    .checkForUpdates(false)
                    .bStats(false)
                    .debug(false);
            PacketEvents.getAPI().load();
        }
        VersionAdapter.init(getLogger());
    }

    @Override
    public void onEnable() {
        instance = this;

        TrainingDataManager.putDefaultModel(this, getDataFolder());

        try {
            SchedulerManager.reset();
            SchedulerManager.initialize(this);
            getLogger().info("SchedulerManager initialized for " + SchedulerManager.getServerType());
        } catch (Throwable e) {
            getLogger().severe("Failed to initialize SchedulerManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            if (!PacketEvents.getAPI().isInitialized()) {
                PacketEvents.getAPI().init();
            }
        } catch (Exception e) {
            getLogger().severe("Failed to initialize PacketEvents: " + e.getMessage());
            e.printStackTrace();
        }

        VersionAdapter.get().logCompatibilityInfo();

        saveDefaultConfig();
        this.config = new Config(this, getLogger());
        this.menuConfig = new MenuConfig(this);
        this.menuConfig.load();
        this.messagesConfig = new MessagesConfig(this);
        this.messagesConfig.load();
        this.hologramConfig = new HologramConfig(this);
        this.hologramConfig.load();

        File mlsDataDir = new File(getDataFolder(), "mls/data");
        if (!mlsDataDir.exists()) {
            mlsDataDir.mkdirs();
        }

        this.featureCalculator = new FeatureCalculator();
        this.sessionManager = DataCollectorFactory.createSessionManager(this);

        this.localAIClientProvider = new LocalAIClientProvider(getDataFolder(), getLogger());
        try {
            this.localAIClientProvider.initialize();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        this.aiClientProvider = new AIClientProvider(this, config);

        if (config.isAiEnabled()) {
            if (config.isLocalModeEnabled()) {
                this.aiClientProvider.setLocalClient(localAIClientProvider.getClient());
                getLogger().info("AI Mode: LOCAL (Model inside plugin)");
            } else {
                String address = config.getServerAddress();
                if (address == null || address.isEmpty()) address = "localhost:8080";

                String[] parts = address.split(":");
                String rHost = parts[0];
                int rPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 8080;

                getLogger().info("AI Mode: REMOTE (WebSocket VPS at " + rHost + ":" + rPort + ")");
            }

            try {
                this.aiClientProvider.initialize();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } else {
            getLogger().info("AI detection: DISABLED in config");
        }

        this.alertManager = new AlertManager(this, config);
        this.punishmentsConfig = new PunishmentsConfig(this);
        this.punishmentManager = new PunishmentManager(this, punishmentsConfig);
        this.violationManager = new ViolationManager(this, config, alertManager);
        this.aiCheck = new AICheck(this, config, aiClientProvider, alertManager, violationManager);
        this.miningCheck = new MiningCheck(this, config, aiClientProvider, alertManager, violationManager);
        for (Model model : localAIClientProvider.getModels()) {
            try {
                model.reload(aiCheck);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        this.violationManager.setAICheck(aiCheck);
        this.violationManager.setMiningCheck(miningCheck);
        this.verdictManager = new VerdictManager(aiCheck, miningCheck);

        this.nametagManager = new NametagManager(this, aiCheck, miningCheck);
        this.nametagManager.start();

        this.tickListener = new TickListener(this, sessionManager, aiCheck, miningCheck, nametagManager);
        this.hitListener = new HitListener(sessionManager, aiCheck);
        this.digListener = new BlockListener(sessionManager, miningCheck);
        this.rotationListener = new RotationListener(sessionManager, aiCheck, miningCheck);

        this.analyticsClient = config.isLocalModeEnabled() ? null : new AnalyticsClient(config.getServerAddress(), getLogger());

        this.playerListener = new PlayerListener(
                this, aiCheck, miningCheck, alertManager, violationManager,
                sessionManager instanceof SessionManager ? (SessionManager) sessionManager : null,
                tickListener, nametagManager, analyticsClient);
        this.teleportListener = new TeleportListener(aiCheck, miningCheck);

        this.tickListener.setHitListener(hitListener);
        this.tickListener.setDigListener(digListener);
        this.playerListener.setHitListener(hitListener);
        this.hitListener.cacheOnlinePlayers();
        this.tickListener.start();
        this.checkManagerListener = new CheckManagerListener();
        this.bListener = new PunishListener(this);

        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            this.tickListener.startPlayerTask(p);
        }

        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(teleportListener, this);
        PacketEvents.getAPI().getEventManager().registerListener(hitListener);
        PacketEvents.getAPI().getEventManager().registerListener(digListener);
        PacketEvents.getAPI().getEventManager().registerListener(rotationListener);

        this.commandHandler = new CommandHandler(sessionManager, alertManager, aiCheck, miningCheck, this);
        PluginCommand command = getCommand("walrus");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null) new WalrusPlayer(user, user.getUUID());
        }

        getLogger().info("MLSAC enabled successfully!");
    }

    @Override
    public void onDisable() {
        commandHandler.handleStopAll(Bukkit.getConsoleSender());
        if (localAIClientProvider != null) {
            getLogger().info("[LocalML] Saving local model state...");
            localAIClientProvider.saveModels();
        }

        if (tickListener != null) tickListener.stop();
        if (nametagManager != null) nametagManager.stop();

        if (sessionManager != null) {
            getLogger().info("Stopping all active sessions...");
            sessionManager.stopAllSessions();
        }

        if (aiCheck != null) aiCheck.clearAll();
        if (miningCheck != null) miningCheck.clearAll();
        if (commandHandler != null) commandHandler.cleanup();

        if (aiClientProvider != null) {
            getLogger().info("Shutting down AI client...");
            try {
                aiClientProvider.shutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e.getMessage() != null) getLogger().warning("Error shutting down AI client: " + e.getMessage());
            }
        }

        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            PacketEvents.getAPI().terminate();
        }
        SchedulerManager.reset();
        getLogger().info("MLSAC disabled successfully!");
    }

    public void reloadPluginConfig() {
        SchedulerManager.getAdapter().runSync(() -> {
            try {
                reloadConfig();
                this.config = new Config(this, getLogger());
                if (menuConfig != null) menuConfig.reload();
                if (messagesConfig != null) messagesConfig.reload();
                if (hologramConfig != null) hologramConfig.reload();

                if (punishmentsConfig != null) {
                    punishmentsConfig.reload();
                    punishmentManager.setPunishmentsConfig(punishmentsConfig);
                }

                if (nametagManager != null) {
                    nametagManager.stop();
                    nametagManager.start();
                }

                alertManager.setConfig(config);
                violationManager.setConfig(config);
                aiCheck.setConfig(config);
                miningCheck.setConfig(config);
                for (Model model : localAIClientProvider.getModels()) {
                    try {
                        model.reload(aiCheck);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                for (WalrusPlayer player : WalrusPlayer.getPlayers()) {
                    for (Check check : player.checkManager.getChecks().values()) {
                        check.loadConfig();
                        check.onReload(getConfig());
                    }
                }

                if (aiClientProvider != null) {
                    aiClientProvider.setConfig(config);

                    if (config.isAiEnabled()) {
                        if (config.isLocalModeEnabled()) {
                            if (localAIClientProvider == null) {
                                localAIClientProvider = new LocalAIClientProvider(getDataFolder(), getLogger());
                                try {
                                    localAIClientProvider.initialize();
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                            aiClientProvider.setLocalClient(localAIClientProvider.getClient());
                            getLogger().info("Switched to LOCAL mode.");
                        } else {
                            String address = config.getServerAddress();
                            String[] parts = address.split(":");
                            String rHost = parts[0];
                            int rPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 8080;

                            getLogger().info("Switched to REMOTE mode (" + rHost + ").");
                        }
                    }
                }
                getLogger().info("Configuration reloaded!");
            } catch (Exception e) {
                getLogger().severe("Failed to reload configuration: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public Config getPluginConfig() { return config; }
    public MenuConfig getMenuConfig() { return menuConfig; }
    public MessagesConfig getMessagesConfig() { return messagesConfig; }
    public HologramConfig getHologramConfig() { return hologramConfig; }
    public ISessionManager getSessionManager() { return sessionManager; }
    public FeatureCalculator getFeatureCalculator() { return featureCalculator; }
    public AICheck getAiCheck() { return aiCheck; }
    public AlertManager getAlertManager() { return alertManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public AIClientProvider getAiClientProvider() { return aiClientProvider; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public AnalyticsClient getAnalyticsClient() { return analyticsClient; }
    public NametagManager getNametagManager() { return nametagManager; }
    public LocalAIClientProvider getLocalAIClientProvider() { return localAIClientProvider; }
    public PunishmentsConfig getPunishmentsConfig() { return punishmentsConfig; }
    public PunishmentManager getPunishmentManager()  { return punishmentManager; }

    public void debug(String message) {
        if (config != null && config.isDebug()) {
            getLogger().info("[Debug] " + message);
        }
    }

    public CheckManagerListener getCheckManagerListener() {
        return checkManagerListener;
    }

    public PunishListener getbListener() {
        return bListener;
    }

    public BlockListener getDigListener() {
        return digListener;
    }

    public VerdictManager getVerdictManager() {
        return verdictManager;
    }
}