/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 */

package wtf.walrus.signalr;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.walrus.ml.MLOut;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.server.AIResponse;
import wtf.walrus.server.IAIClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SignalRClient implements IAIClient {
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final JavaPlugin plugin;
    private final String serverAddress;
    private final String apiKey;
    private final int reportStatsIntervalSeconds;
    private final Logger logger;
    private final IntSupplier onlinePlayersSupplier;
    private final boolean debug;
    private final Gson gson;
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingRequests;

    private WebSocketClient webSocket;
    private String sessionId;
    private volatile boolean connected = false;
    private volatile boolean shuttingDown = false;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private ScheduledTask heartbeatTask;
    private ScheduledTask reportStatsTask;
    private boolean limitExceeded = false;
    private String baseUrl;
    private CompletableFuture<Boolean> connectionFuture;

    private final String sversion, version;

    public SignalRClient(JavaPlugin plugin, String serverAddress, String apiKey,
                         int reportStatsIntervalSeconds, IntSupplier onlinePlayersSupplier, boolean debug) {
        this.plugin = plugin;
        this.serverAddress = serverAddress;
        this.apiKey = apiKey;
        this.reportStatsIntervalSeconds = reportStatsIntervalSeconds;
        this.logger = plugin.getLogger();
        this.onlinePlayersSupplier = onlinePlayersSupplier;
        this.version = plugin.getDescription().getVersion();
        this.sversion = Bukkit.getVersion();
        this.debug = debug;
        this.gson = new GsonBuilder().create();
        this.pendingRequests = new ConcurrentHashMap<>();
    }

    public synchronized CompletableFuture<Boolean> connect() {
        if (shuttingDown) {
            return CompletableFuture.completedFuture(false);
        }
        if (connectionFuture != null && !connectionFuture.isDone()) {
            return connectionFuture;
        }

        this.baseUrl = serverAddress.startsWith("ws") ? serverAddress : "ws://" + serverAddress.replace("http://", "").replace("https://", "");

        connectionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = new URI(baseUrl);
                webSocket = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        logger.info("[WebSocket] Connected to " + baseUrl);
                        Map<String, Object> connectMsg = new HashMap<>();
                        connectMsg.put("type", "connect");
                        connectMsg.put("playerCount", onlinePlayersSupplier.getAsInt());
                        connectMsg.put("version", version);
                        connectMsg.put("sversion", sversion);
                        connectMsg.put("apiKey", apiKey);
                        send(gson.toJson(connectMsg));
                        sendStatsReport();
                    }

                    @Override
                    public void onMessage(String message) {
                        handleMessage(message);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        logger.warning("[WebSocket] Disconnected: " + reason);
                        connected = false;

                        if (heartbeatTask != null) {
                            heartbeatTask.cancel();
                            heartbeatTask = null;
                        }
                        if (reportStatsTask != null) {
                            reportStatsTask.cancel();
                            reportStatsTask = null;
                        }

                        if (!shuttingDown) {
                            scheduleReconnect();
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        logger.warning("[WebSocket] Error: " + ex.getMessage());
                    }
                };

                webSocket.connectBlocking();
                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[WebSocket] Connection failed: " + e.getMessage());
                connected = false;
                return false;
            }
        });

        return connectionFuture;
    }

    private void handleMessage(String message) {
        try {
            Map<String, Object> response = gson.fromJson(message, Map.class);
            String status = (String) response.get("status");

            if ("connected".equals(status)) {
                this.sessionId = (String) response.get("sessionId");
                connected = true;
                reconnectAttempts.set(0);
                sendStatsReport();
                startHeartbeat();
                startReportStats();
                logger.info("[WebSocket] Session created: " + sessionId);
            } else if ("success".equals(status) && response.containsKey("probability")) {
                double probability = ((Number) response.get("probability")).doubleValue();
                String requestId = (String) response.get("requestId");

                CompletableFuture<Map<String, Object>> future = pendingRequests.remove(requestId);
                if (future != null) {
                    future.complete(response);
                }
            } else if ("error".equals(status)) {
                String requestId = (String) response.get("requestId");
                String errorMsg = (String) response.get("message");

                if (requestId != null) {
                    CompletableFuture<Map<String, Object>> future = pendingRequests.remove(requestId);
                    if (future != null) {
                        future.completeExceptionally(new RuntimeException(errorMsg));
                    }
                }
                logger.warning("[WebSocket] Error: " + errorMsg);
            }
//            else if ("ping-out".equals(status)) {
//            }
        } catch (Exception e) {
            logger.warning("[WebSocket] Failed to parse message: " + e.getMessage()
                    + " | Raw message: " + message
                    + " | Exception: " + e.getClass().getName());
            logger.log(Level.WARNING, "[WebSocket] Stack trace:", e);
        }
    }

    private void sendStatsReport() {

        SchedulerManager.getAdapter().runAsync(() -> {
            if (!connected || shuttingDown || webSocket == null) return;

            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "stats");
            msg.put("playerCount", onlinePlayersSupplier.getAsInt());
            msg.put("version", version);
            msg.put("sversion", sversion);
            msg.put("requestId", UUID.randomUUID().toString());

            webSocket.send(gson.toJson(msg));
        });
    }

    private void startReportStats() {
        if (reportStatsTask != null) reportStatsTask.cancel();

        long intervalTicks = reportStatsIntervalSeconds * 20L;
        reportStatsTask = SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (!connected || shuttingDown || webSocket == null) return;

            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "stats");
            msg.put("playerCount", onlinePlayersSupplier.getAsInt());
            msg.put("version", version);
            msg.put("sversion", sversion);
            msg.put("requestId", UUID.randomUUID().toString());

            webSocket.send(gson.toJson(msg));
        }, intervalTicks, intervalTicks);
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }

        heartbeatTask = SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (!connected || shuttingDown || webSocket == null) return;

            Map<String, Object> heartbeat = new HashMap<>();
            heartbeat.put("type", "ping");
            heartbeat.put("requestId", UUID.randomUUID().toString());

            webSocket.send(gson.toJson(heartbeat));
        }, 20L, 20L);
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RETRY_ATTEMPTS) {
            logger.severe("[WebSocket] Max retry attempts reached, giving up");
            return;
        }

        long delayMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
        long delayTicks = delayMs / 50;

        logger.info("[WebSocket] Retrying connection in " + delayMs + "ms (attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS + ")");

        SchedulerManager.getAdapter().runAsyncDelayed(() -> {
            connect().thenAccept(success -> {
                //if (!success) scheduleReconnect();
            });
        }, delayTicks);
    }

    public CompletableFuture<Void> disconnect() {
        shuttingDown = true;

        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (reportStatsTask != null) {
            reportStatsTask.cancel();
        }

        for (CompletableFuture<Map<String, Object>> future : pendingRequests.values()) {
            future.completeExceptionally(new RuntimeException("Disconnected"));
        }
        pendingRequests.clear();

        if (webSocket != null) {
            webSocket.close();
        }

        connected = false;
        logger.info("[WebSocket] Disconnected");
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Boolean> connectWithRetry() {
        return connect();
    }

    @Override
    public io.reactivex.rxjava3.core.Observable<AIResponse> predict(byte[] playerData, String playerUuid, String playerName, List<String> disabledModels, List<String> oaModels) {
        if (!isConnected()) {
            return io.reactivex.rxjava3.core.Observable.error(new IllegalStateException("Not connected"));
        }
        if (limitExceeded) {
            return io.reactivex.rxjava3.core.Observable.error(new IllegalStateException("Online limit exceeded"));
        }

        return io.reactivex.rxjava3.core.Observable.create(emitter -> {
            try {
                long a = System.currentTimeMillis();

                String dataBase64 = Base64.getEncoder().encodeToString(playerData);
                String requestId = UUID.randomUUID().toString();

                CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
                pendingRequests.put(requestId, future);

                Map<String, Object> predictMsg = new HashMap<>();
                predictMsg.put("type", "predict");
                predictMsg.put("disabled", disabledModels);
                predictMsg.put("only-alerts", oaModels);
                predictMsg.put("data", dataBase64);
                predictMsg.put("playerUuid", playerUuid);
                predictMsg.put("playerName", playerName);
                predictMsg.put("requestId", requestId);

                webSocket.send(gson.toJson(predictMsg));

                future.orTimeout(5, TimeUnit.SECONDS)
                        .thenAccept(resp -> {
                            long b = System.currentTimeMillis();
                            logger.info(b - a + " ms");

                            double probability = ((Number) resp.get("probability")).doubleValue();
                            String verdict = (String) resp.get("verdict");
                            String resolvedModelId = (String) resp.get("model");

                            double probabilityb = ((Number) resp.get("probabilityb")).doubleValue();
                            String modelb = (String) resp.get("modelb");

                            emitter.onNext(new AIResponse(new MLOut(probability, new String[]{"unknown"}), verdict, resolvedModelId,
                                    new MLOut(probabilityb, new String[]{"unknown"}), modelb));
                            emitter.onComplete();
                            pendingRequests.remove(requestId);
                        })
                        .exceptionally(ex -> {
                            emitter.onError(ex);
                            pendingRequests.remove(requestId);
                            return null;
                        });

            } catch (Exception e) {
                emitter.onError(e);
            }
        });
    }

    @Override
    public boolean isConnected() {
        return connected && webSocket != null && webSocket.isOpen();
    }

    @Override
    public boolean isLimitExceeded() {
        return limitExceeded;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getServerAddress() {
        return serverAddress;
    }

    public String getSversion() {
        return sversion;
    }

    public String getVersion() {
        return version;
    }
}