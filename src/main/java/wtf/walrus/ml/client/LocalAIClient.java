/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 * Licensed under GPL-3.0
 */
package wtf.walrus.ml.client;

import io.reactivex.rxjava3.core.Observable;
import wtf.walrus.data.TickData;
import wtf.walrus.ml.FlatBufferDeserializer;
import wtf.walrus.ml.Model;
import wtf.walrus.server.AIResponse;
import wtf.walrus.server.IAIClient;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class LocalAIClient implements IAIClient {

    public enum Verdict {
        CLEAN,
        SUSPICIOUS,
        FLAG,
        BAN
    }

    private static final long CLEANUP_TTL_MS = 60_000L;

    private static class PlayerState {
        long lastSeen = System.currentTimeMillis();
    }

    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final List<Model> models;
    private volatile boolean connected = true;

    public LocalAIClient(List<Model> models) {
        this.models = models;
    }

    public AIResponse predictDirect(String playerId, List<TickData> ticks) {
        AIResponse best = null;

        for (Model model : models) {
            if (!model.isTrained()) continue;

            PlayerState state = playerStates.computeIfAbsent(playerId, k -> new PlayerState());
            state.lastSeen = System.currentTimeMillis();

            double prob   = model.predict(ticks);
            double thresh = model.getOptimalThreshold();
            Verdict verdict = computeVerdict(prob, thresh);
            AIResponse response = new AIResponse(prob, verdict.name(), model.getName());

            if (best == null || prob > best.getProbability()) {
                best = response;
            }
        }

        if (best == null) return new AIResponse(0.0, null, "no_trained_model");
        return best;
    }

    public void clearPlayer(String playerId) {
        playerStates.remove(playerId);
    }

    public void cleanupStaleStates() {
        long now = System.currentTimeMillis();
        playerStates.entrySet().removeIf(e -> now - e.getValue().lastSeen > CLEANUP_TTL_MS);
    }

    private Verdict computeVerdict(double prob, double thresh) {
        if (prob < thresh * 0.70)  return Verdict.CLEAN;
        if (prob < thresh * 0.92)  return Verdict.SUSPICIOUS;
        if (prob < thresh * 1.05)  return Verdict.FLAG;
        return Verdict.BAN;
    }

    @Override
    public Observable<AIResponse> predict(byte[] playerData, String playerUuid, String playerName) {
        return Observable.fromCallable(() -> {
            try {
                ByteBuffer buf = ByteBuffer.wrap(playerData);
                List<TickData> ticks = FlatBufferDeserializer.deserialize(buf);
                return predictDirect(playerUuid, ticks);
            } catch (Exception e) {
                return new AIResponse(0.0, null, "local_error");
            }
        });
    }

    @Override public CompletableFuture<Boolean> connect()          { connected = true;  return CompletableFuture.completedFuture(true); }
    @Override public CompletableFuture<Boolean> connectWithRetry() { return connect(); }
    @Override public CompletableFuture<Void>    disconnect()       { connected = false; return CompletableFuture.completedFuture(null); }
    @Override public boolean isConnected()     { return connected; }
    @Override public boolean isLimitExceeded() { return false; }
    @Override public String  getSessionId()    { return "local"; }
    @Override public String  getServerAddress(){ return "local"; }
}