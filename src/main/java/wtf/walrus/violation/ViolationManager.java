/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file contains code derived from:
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package wtf.walrus.violation;

import org.bukkit.entity.Player;
import wtf.walrus.Main;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.CheckType;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.Config;
import wtf.walrus.config.PunishmentEntry;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.DamageVerdict;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.penalty.ActionType;
import wtf.walrus.penalty.PenaltyContext;
import wtf.walrus.penalty.PenaltyExecutor;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ViolationManager {
    private static final int MAX_KICK_HISTORY = 10;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long PUNISHMENT_COOLDOWN_MS = 5000;

    private final Main plugin;
    private final AlertManager alertManager;
    private final Logger logger;
    private final Map<UUID, Integer> violationLevels;
    private final Map<UUID, Set<Integer>> usedProbPunishments;
    private final LinkedList<KickRecord> kickHistory;
    private final PenaltyExecutor penaltyExecutor;
    private final Map<UUID, Long> lastPunishmentTime;
    private Config config;
    private AICheck aiCheck;
    private MiningCheck miningCheck;
    private ScheduledTask decayTask;

    public static class KickRecord {
        private final String playerName;
        private final double probability;
        private final double buffer;
        private final int vl;
        private final LocalDateTime time;
        private final String command;

        public KickRecord(String playerName, double probability, double buffer, int vl, String command) {
            this.playerName = playerName;
            this.probability = probability;
            this.buffer = buffer;
            this.vl = vl;
            this.time = LocalDateTime.now();
            this.command = command;
        }

        public String getPlayerName() { return playerName; }
        public double getProbability() { return probability; }
        public double getBuffer() { return buffer; }
        public int getVl() { return vl; }
        public LocalDateTime getTime() { return time; }
        public String getCommand() { return command; }
        public String getFormattedTime() { return time.format(TIME_FORMATTER); }
    }

    public ViolationManager(Main plugin, Config config, AlertManager alertManager) {
        this.plugin = plugin;
        this.config = config;
        this.alertManager = alertManager;
        this.logger = plugin.getLogger();
        this.violationLevels = new ConcurrentHashMap<>();
        this.usedProbPunishments = new ConcurrentHashMap<>();
        this.kickHistory = new LinkedList<>();
        this.penaltyExecutor = new PenaltyExecutor(plugin);
        this.lastPunishmentTime = new ConcurrentHashMap<>();
        updatePenaltyExecutorConfig();
        startDecayTask();
    }

    public void setAICheck(AICheck aiCheck) {
        this.aiCheck = aiCheck;
    }

    public void setMiningCheck(MiningCheck miningCheck) {
        this.miningCheck = miningCheck;
    }

    private void startDecayTask() {
        stopDecayTask();
        if (!config.isVlDecayEnabled()) {
            return;
        }
        int intervalTicks = config.getVlDecayIntervalSeconds() * 20;
        decayTask = SchedulerManager.getAdapter().runSyncRepeating(this::processDecay, intervalTicks, intervalTicks);
        plugin.debug("[VL] Decay task started with interval " + config.getVlDecayIntervalSeconds() + "s");
    }

    private void stopDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    private void processDecay() {
        if (aiCheck == null) {
            return;
        }
        int decayAmount = config.getVlDecayAmount();
        for (Map.Entry<UUID, Integer> entry : violationLevels.entrySet()) {
            UUID playerId = entry.getKey();
            AIPlayerData playerData = aiCheck.getPlayerData(playerId);
            if (playerData != null && playerData.isInCombat()) {
                continue;
            }
            int oldVl = entry.getValue();
            int newVl = oldVl - decayAmount;
            if (newVl <= 0) {
                violationLevels.remove(playerId);
                usedProbPunishments.remove(playerId);
            } else {
                violationLevels.put(playerId, newVl);
                Set<Integer> used = usedProbPunishments.get(playerId);
                if (used != null) {
                    used.removeIf(usedVl -> usedVl > newVl);
                }
            }
        }
    }

    private void updatePenaltyExecutorConfig() {
        penaltyExecutor.setAlertPrefix(plugin.getMessagesConfig().getPrefix());
        penaltyExecutor.setConsoleAlerts(config.isAiConsoleAlerts());
        penaltyExecutor.setAnimationEnabled(config.isAnimationEnabled());
    }

    public void setConfig(Config config) {
        this.config = config;
        updatePenaltyExecutorConfig();
        startDecayTask();
    }

    public void handleFlag(Player player, double probability, double buffer, CheckType checkType) {
        if (plugin.getSessionManager().getSession(player) != null) return;
        if (probability < config.getAiPunishmentMinProbability()) {
            return;
        }
        AIPlayerData data = plugin.getAiCheck().getOrCreatePlayerData(player);
        MiningPlayerData miningData = plugin.getMiningCheck().getOrCreatePlayerData(player);
        if (checkType.equals(CheckType.AIM) && config.isDamageVerdict()) {
            data.setDamageVerdict(new DamageVerdict(probability, System.currentTimeMillis()));
            data.ticksSinceVerdict = 0;
        }
        if (checkType.equals(CheckType.BLOCK) && config.isDigVerdict()) {
            miningData.setDamageVerdict(new DamageVerdict(probability, System.currentTimeMillis()));
            miningData.ticksSinceVerdict = 0;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        int newVl = incrementViolationLevel(uuid);
        alertManager.sendAlert(player.getName(), probability, buffer, newVl);
        plugin.debug("[AI] " + player.getName() + " flagged - VL: " + newVl +
                ", Prob: " + String.format("%.2f", probability) +
                ", Buffer: " + String.format("%.1f", buffer));

        String command = getApplicablePunishmentCommand(uuid, newVl, probability);
        if (command != null) {
            ActionType actionType = ActionType.fromCommand(command);
            if (actionType.isPunishment()) {
                Long previousTime = lastPunishmentTime.get(uuid);
                if (previousTime != null && (now - previousTime) < PUNISHMENT_COOLDOWN_MS) {
                    plugin.debug("[AI] " + player.getName() + " punishment on cooldown, skipping " + actionType);
                    return;
                }
                lastPunishmentTime.put(uuid, now);
            }
            executeCommand(command, player, probability, buffer, newVl);
        }
    }

    public int incrementViolationLevel(UUID playerId) {
        return violationLevels.merge(playerId, 1, Integer::sum);
    }

    public int getViolationLevel(UUID playerId) {
        return violationLevels.getOrDefault(playerId, 0);
    }

    public void resetViolationLevel(UUID playerId) {
        violationLevels.remove(playerId);
        usedProbPunishments.remove(playerId);
    }

    public String getApplicablePunishmentCommand(UUID playerId, int vl, double prob) {
        Map<Integer, String> commands = config.getPunishmentCommands();

        if (commands.isEmpty()) {
            return null;
        }

        int bestThreshold = -1;

        for (int threshold : commands.keySet()) {
            if (vl < threshold) continue;

            PunishmentEntry entry = PunishmentEntry.parse(commands.get(threshold));
            boolean isProbBased = entry.getMinProb() > 0.0;

            if (prob < entry.getMinProb()) {
                continue;
            }

            if (config.isOneUseProbPunishment() && isProbBased) {
                Set<Integer> used = usedProbPunishments.get(playerId);
                if (used != null && used.contains(threshold)) {
                    continue;
                }
            }

            if (threshold > bestThreshold) {
                bestThreshold = threshold;
            }
        }

        if (bestThreshold == -1) {
            return null;
        }

        PunishmentEntry entry = PunishmentEntry.parse(commands.get(bestThreshold));
        boolean isProbBased = entry.getMinProb() > 0.0;

        if (isProbBased) {
            usedProbPunishments
                    .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                    .add(bestThreshold);
        }

        return entry.getCommand();
    }

    public void executeMaxPunishment(Player player) {
        Map<Integer, String> commands = config.getPunishmentCommands();
        if (commands.isEmpty()) {
            return;
        }
        int maxThreshold = -1;
        for (int threshold : commands.keySet()) {
            if (threshold > maxThreshold) {
                maxThreshold = threshold;
            }
        }
        if (maxThreshold != -1) {
            String command = commands.get(maxThreshold);
            executeCommand(command, player, 1.0, 100.0, maxThreshold);
        }
    }

    public void executeCommand(String command, Player player, double probability, double buffer, int vl) {
        PenaltyContext context = PenaltyContext.builder()
                .playerName(player.getName())
                .violationLevel(vl)
                .probability(probability)
                .buffer(buffer)
                .build();
        addKickRecord(new KickRecord(player.getName(), probability, buffer, vl, command));
        penaltyExecutor.execute(command, context);
    }

    private synchronized void addKickRecord(KickRecord record) {
        kickHistory.addFirst(record);
        while (kickHistory.size() > MAX_KICK_HISTORY) {
            kickHistory.removeLast();
        }
    }

    public synchronized List<KickRecord> getKickHistory() {
        return Collections.unmodifiableList(new ArrayList<>(kickHistory));
    }

    public PenaltyExecutor getPenaltyExecutor() {
        return penaltyExecutor;
    }

    public void handlePlayerQuit(Player player) {
        lastPunishmentTime.remove(player.getUniqueId());
    }

    public void decreaseViolationLevel(UUID playerId, int amount) {
        violationLevels.computeIfPresent(playerId, (k, v) -> {
            int newVl = v - amount;
            if (newVl <= 0) {
                usedProbPunishments.remove(playerId);
                return null;
            }
            Set<Integer> used = usedProbPunishments.get(playerId);
            if (used != null) {
                used.removeIf(usedVl -> usedVl > newVl);
            }
            return newVl;
        });
    }

    public void clearAll() {
        violationLevels.clear();
        usedProbPunishments.clear();
        lastPunishmentTime.clear();
        synchronized (this) {
            kickHistory.clear();
        }
    }

    public void shutdown() {
        stopDecayTask();
        clearAll();
        penaltyExecutor.shutdown();
    }
}