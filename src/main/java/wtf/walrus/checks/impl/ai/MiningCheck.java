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

package wtf.walrus.checks.impl.ai;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.walrus.Main;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.CheckType;
import wtf.walrus.compat.WorldGuardCompat;
import wtf.walrus.config.Config;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.data.TickData;
import wtf.walrus.ml.client.LocalAIClient;
import wtf.walrus.scheduler.SchedulerAdapter;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.server.AIClientProvider;
import wtf.walrus.server.AIResponse;
import wtf.walrus.server.FlatBufferSerializer;
import wtf.walrus.server.IAIClient;
import wtf.walrus.violation.ViolationManager;
import wtf.walrus.util.GeyserUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class MiningCheck {

    private final Main             plugin;
    private final AIClientProvider clientProvider;
    private final AlertManager     alertManager;
    private final ViolationManager violationManager;
    private final Logger           logger;
    private final SchedulerAdapter schedulerAdapter;
    private final Map<UUID, MiningPlayerData> playerData;

    private Config            config;
    private WorldGuardCompat  worldGuardCompat;
    private int               sequence;
    private int               step;

    public MiningCheck(Main plugin, Config config,
                       AIClientProvider clientProvider,
                       AlertManager alertManager,
                       ViolationManager violationManager) {
        this.plugin           = plugin;
        this.config           = config;
        this.clientProvider   = clientProvider;
        this.alertManager     = alertManager;
        this.violationManager = violationManager;
        this.logger           = plugin.getLogger();
        this.schedulerAdapter = SchedulerManager.getAdapter();
        this.playerData       = new ConcurrentHashMap<>();
        this.sequence         = config.getAiSequence();
        this.step             = config.getAiStep();
        this.worldGuardCompat = new WorldGuardCompat(
                plugin.getLogger(),
                config.isWorldGuardEnabled(),
                config.getWorldGuardDisabledRegions());
    }

    public void setConfig(Config config) {
        this.config           = config;
        this.sequence         = config.getAiSequence();
        this.step             = config.getAiStep();
        this.worldGuardCompat = new WorldGuardCompat(
                plugin.getLogger(),
                config.isWorldGuardEnabled(),
                config.getWorldGuardDisabledRegions());
    }

    public void onDig(Player player) {
        if (!config.isAiEnabled() || !config.isMiningAiEnabled()) return;
        if (worldGuardCompat.shouldBypassAICheck(player)) {
            plugin.debug("[AI] Skipping attack for " + player.getName() + " - in disabled WorldGuard region");
            return;
        }
        if (!player.isValid()) return;

        schedulerAdapter.runEntitySync(player, () -> {
            MiningPlayerData data = getOrCreatePlayerData(player);
            if (data.isBedrock()) {
                plugin.debug("[AI] Skipping attack for " + player.getName() + " - Bedrock player");
                return;
            }
            if (!data.isInCombat()) {
                data.clearBuffer();
                data.getAimProcessor().reset();
                plugin.debug("[AI] New combat for " + player.getName() + ", cleared old data");
            }
            data.onAttack();
            plugin.debug("[AI] Attack registered for " + player.getName() +
                    ", buffer=" + data.getBufferSize() + "/" + sequence);
        });
    }

    public void onTeleport(Player player) {
        if (!config.isAiEnabled() || !config.isMiningAiEnabled()) return;
        if (!player.isValid()) return;

        schedulerAdapter.runEntitySync(player, () -> {
            MiningPlayerData data = playerData.get(player.getUniqueId());
            if (data != null) {
                data.onTeleport();
                plugin.debug("[AI] Teleport for " + player.getName() + ", resetting data");
            }
        });
    }

    public void onTick(Player player) {
        if (!config.isAiEnabled() || !config.isMiningAiEnabled()) return;
        if (!isClientAvailable()) return;
        if (!player.isValid()) return;

        schedulerAdapter.runEntitySync(player, () -> {
            MiningPlayerData data = getOrCreatePlayerData(player);
            if (data.isBedrock()) return;

            data.incrementTicksSinceAttack();

            if (data.getTicksSinceAttack() > sequence) {
                if (!data.isPendingRequest() && data.getBufferSize() >= sequence) {
                    plugin.debug("[AI] Combat ended for " + player.getName() +
                            ", sending final buffer (" + data.getBufferSize() + " ticks)");
                    data.setPendingRequest(true);
                    sendDataToAI(player, data);
                }
                if (!data.isPendingRequest()
                        && data.getTicksSinceAttack() > sequence * 2
                        && data.getBufferSize() > 0) {
                    data.clearBuffer();
                }
                data.resetStepCounter();
            }
        });
    }

    public void onRotationPacket(Player player, float yaw, float pitch) {
        if (!config.isAiEnabled() || !config.isMiningAiEnabled()) return;
        if (!isClientAvailable()) return;
        if (!player.isValid()) return;

        schedulerAdapter.runEntitySync(player, () -> {
            MiningPlayerData data = playerData.get(player.getUniqueId());
            if (data == null) return;
            if (!data.isInCombat()) return;
            if (worldGuardCompat.shouldBypassAICheck(player)) {
                plugin.debug("[AI] Skipping rotation for " + player.getName() + " - WorldGuard region");
                return;
            }
            if (data.isBedrock()) return;

            data.processTick(yaw, pitch);
            data.incrementStepCounter();

            if (data.shouldSendData(step, sequence)) {
                data.setPendingRequest(true);
                sendDataToAI(player, data);
                data.resetStepCounter();
            }
        });
    }

    public void handlePlayerQuit(Player player) {
        IAIClient client = clientProvider != null ? clientProvider.get() : null;
        if (client instanceof LocalAIClient) {
            ((LocalAIClient) client).clearPlayer(player.getUniqueId().toString());
        }
    }

    private void sendDataToAI(Player player, MiningPlayerData data) {
        try {
            List<TickData> ticks = data.getTickBuffer();
            if (ticks.size() < sequence) {
                plugin.debug("[AI] Not enough ticks for " + player.getName() +
                        ": " + ticks.size() + "/" + sequence);
                data.setPendingRequest(false);
                return;
            }

            IAIClient client = clientProvider.get();
            if (client == null) {
                logger.warning("stop sended prediction 0");
                plugin.debug("[AI] Client unavailable for " + player.getName());
                data.setPendingRequest(false);
                return;
            }

            logTickBuffer(player, ticks);

            final UUID   playerUuid = player.getUniqueId();
            final String playerName = player.getName();

            if (client instanceof LocalAIClient) {
                AIResponse response = ((LocalAIClient) client)
                        .predictDirect(playerUuid.toString(), ticks);

                plugin.debug("[AI] Local prediction for " + playerName +
                        ": prob=" + String.format("%.3f", response.getProbability()) +
                        " verdict=" + response.getModel());

                processResponse(playerUuid, playerName, data, response);

            } else {
                byte[] serialized = FlatBufferSerializer.serialize(ticks);
                client.predict(serialized, playerUuid.toString(), playerName)
                        .subscribe(
                                response -> schedulerAdapter.runSync(
                                        () -> processResponse(playerUuid, playerName, data, response)),
                                error -> handleError(playerName, data, error)
                        );
            }
        } catch (Exception e) {
            logger.warning("[AI] Unexpected error in sendDataToAI: " + e.getMessage());
            e.printStackTrace();
            data.setPendingRequest(false);
        }
    }

    private void processResponse(UUID playerUuid, String playerName,
                                 MiningPlayerData data, AIResponse response) {
        schedulerAdapter.runSync(() -> {
            data.setPendingRequest(false);
            data.clearBuffer();

            if (response.getError() != null
                    && response.getError().contains("INVALID_SEQUENCE")) {
                handleInvalidSequence(response.getError());
                return;
            }

            double probability = response.getProbability();
            String modelName   = response.getModel();
            boolean isOnlyAlert = config.isOnlyAlertForModel(modelName);

            plugin.debug("[AI] Response for " + playerName +
                    ": prob=" + String.format("%.3f", probability) +
                    " model=" + modelName +
                    " onlyAlert=" + isOnlyAlert);

            if (!isOnlyAlert) {
                data.updateBuffer(
                        probability,
                        config.getAiBufferMultiplier(),
                        config.getAiBufferDecrease(),
                        config.getAiAlertThreshold());
            } else {
                plugin.debug("[AI] Only-alert mode for model " + modelName
                        + ", skipping buffer update");
            }

            if (alertManager.shouldAlert(probability)) {
                alertManager.sendAlert(playerName, probability, data.getBuffer(), modelName, CheckType.BLOCK);
            }

            if (!isOnlyAlert && data.shouldFlag(config.getAiBufferFlag())) {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null && player.isOnline()) {
                    violationManager.handleFlag(player, probability, data.getBuffer(), CheckType.BLOCK);
                } else {
                    logger.warning("[AI] Player " + playerName
                            + " went offline before punishment");
                }
                data.resetBuffer(config.getAiBufferResetOnFlag());
            }

            plugin.getVerdictManager().setVerdict(playerUuid, CheckType.BLOCK);
        });
    }

    private boolean isClientAvailable() {
        return clientProvider != null && clientProvider.isAvailable();
    }

    private void handleInvalidSequence(String error) {
        try {
            String[] parts = error.split(":");
            if (parts.length >= 2) {
                int newSequence = Integer.parseInt(parts[1].trim());
                if (newSequence > 0 && newSequence != this.sequence) {
                    logger.info("[AI] Updating sequence " + this.sequence + " → " + newSequence);
                    this.sequence = newSequence;
                    playerData.values().forEach(MiningPlayerData::clearBuffer);
                }
            }
        } catch (NumberFormatException e) {
            logger.warning("[AI] Failed to parse new sequence from error: " + error);
        }
    }

    private void handleError(String playerName, MiningPlayerData data, Throwable error) {
        if (data != null) data.setPendingRequest(false);
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        logger.warning("[AI] Error for " + playerName + ": " + cause.getMessage());
    }

    public MiningPlayerData getOrCreatePlayerData(Player player) {
        return playerData.computeIfAbsent(player.getUniqueId(), uuid -> {
            MiningPlayerData d = new MiningPlayerData(uuid, sequence);
            if (GeyserUtil.isBedrockPlayer(uuid)) {
                d.setBedrock(true);
                logger.info("[AI] Bedrock player detected: " + player.getName() + " — bypassing checks");
            }
            return d;
        });
    }

    private void logTickBuffer(Player player, List<TickData> ticks) {
        if (!config.isDebug()) return;
        plugin.debug("[AI] === TICK BUFFER START for " + player.getName() + " ===");
        for (int i = 0; i < ticks.size(); i++) {
            TickData t = ticks.get(i);
            plugin.debug(String.format(
                    "[AI] Tick[%d]: dYaw=%.4f dPitch=%.4f aYaw=%.4f aPitch=%.4f"
                            + " jYaw=%.4f jPitch=%.4f gcdYaw=%.4f gcdPitch=%.4f",
                    i, t.deltaYaw, t.deltaPitch, t.accelYaw, t.accelPitch,
                    t.jerkYaw, t.jerkPitch, t.gcdErrorYaw, t.gcdErrorPitch));
        }
        plugin.debug("[AI] === TICK BUFFER END ===");
    }

    public MiningPlayerData  getPlayerData(UUID playerId)  { return playerData.get(playerId); }
    public int           getSequence()                  { return sequence; }
    public int           getStep()                      { return step; }
    public WorldGuardCompat getWorldGuardCompat()       { return worldGuardCompat; }

    public void clearAll() {
        playerData.clear();
    }
}