/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.server;

import org.bukkit.Bukkit;
import wtf.walrus.Main;
import wtf.walrus.config.Config;
import wtf.walrus.ml.client.LocalAIClient;
import wtf.walrus.signalr.SignalRClient;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class AIClientProvider {
    private final Main plugin;
    private final Logger logger;
    private IAIClient currentClient;
    private Config config;
    private volatile boolean connecting = false;
    private volatile String clientType = "none";

    public AIClientProvider(Main plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    /**
     * Initialize the AI client.
     * If local-mode is enabled, the LocalAIClient is injected via setLocalClient()
     * BEFORE this is called — so we skip SignalR init.
     */
    public CompletableFuture<Boolean> initialize() {
        if (!config.isAiEnabled()) {
            plugin.debug("[AI] AI is disabled, skipping client initialization");
            return CompletableFuture.completedFuture(false);
        }

        if (config.isLocalModeEnabled()) {
            if (currentClient != null && currentClient instanceof LocalAIClient) {
                plugin.debug("[AI] Local mode active, skipping SignalR init");
                return CompletableFuture.completedFuture(true);
            }
            logger.warning("[AI] Local mode enabled but LocalAIClient not injected yet!");
            return CompletableFuture.completedFuture(false);
        }

        return shutdown().thenCompose(v -> {
            String serverAddress = config.getServerAddress();
            String apiKey = config.getAiApiKey();

            if (serverAddress == null || serverAddress.isEmpty()) {
                logger.warning("[AI] Server address is not configured!");
                return CompletableFuture.completedFuture(false);
            }
            if (apiKey == null || apiKey.isEmpty()) {
                logger.warning("[AI] API key is not configured!");
                return CompletableFuture.completedFuture(false);
            }

            connecting = true;
            return initializeSignalR(serverAddress, apiKey);
        });
    }

    private CompletableFuture<Boolean> initializeSignalR(String serverAddress, String apiKey) {
        SignalRClient signalRClient = new SignalRClient(
                plugin,
                serverAddress,
                apiKey,
                config.getReportStatsIntervalSeconds(),
                () -> Bukkit.getOnlinePlayers().size(),
                config.isDebug());
        this.currentClient = signalRClient;
        this.clientType = "SignalR";
        logger.info("[SignalR] Connecting to " + serverAddress + "...");
        return signalRClient.connectWithRetry()
                .thenApply(success -> {
                    connecting = false;
                    if (success) {
                        logger.info("[SignalR] Successfully connected to InferenceServer");
                    } else {
                        logger.warning("[SignalR] Failed to connect to InferenceServer after retries");
                    }
                    return success;
                })
                .exceptionally(e -> {
                    connecting = false;
                    logger.severe("[SignalR] Connection error: " + e.getMessage());
                    return false;
                });
    }

    /**
     * Inject a local AI client (used when local-mode is enabled).
     * Call this BEFORE initialize().
     */
    public void setLocalClient(LocalAIClient localClient) {
        this.currentClient = localClient;
        this.clientType = "Local";
        this.connecting = false;
        logger.info("[AI] LocalAIClient injected as active client");
    }

    public CompletableFuture<Void> shutdown() {
        // Don't disconnect local client on shutdown — it has no network state
        if (currentClient != null && currentClient instanceof LocalAIClient) {
            currentClient = null;
            clientType = "none";
            return CompletableFuture.completedFuture(null);
        }

        if (currentClient != null) {
            logger.info("[AI] Shutting down " + clientType + " client...");
            return currentClient.disconnect()
                    .thenRun(() -> {
                        currentClient = null;
                        clientType = "none";
                        logger.info("[AI] Client shutdown complete");
                    });
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Boolean> reload() {
        // In local mode, just re-initialize without disconnecting SignalR
        if (config.isLocalModeEnabled()) {
            return CompletableFuture.completedFuture(currentClient != null);
        }
        return shutdown().thenCompose(v -> initialize());
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public IAIClient get() {
        return currentClient;
    }

    public boolean isAvailable() {
        return currentClient != null && currentClient.isConnected();
    }

    public boolean isEnabled() {
        return config.isAiEnabled();
    }

    public boolean isConnecting() {
        return connecting;
    }

    public boolean isLimitExceeded() {
        return currentClient != null && currentClient.isLimitExceeded();
    }

    public String getClientType() {
        return clientType;
    }

    public boolean isLocalMode() {
        return "Local".equals(clientType);
    }
}