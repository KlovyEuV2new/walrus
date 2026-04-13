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
 * You should have reaceived a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file contains code derived from:
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package wtf.walrus.alert;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.walrus.Main;
import wtf.walrus.Permissions;
import wtf.walrus.checks.CheckType;
import wtf.walrus.config.Config;
import wtf.walrus.config.MessagesConfig;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.ml.impl.LocalModel;
import wtf.walrus.scheduler.SchedulerAdapter;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.util.ColorUtil;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

public class AlertManager {
    private final Logger logger;
    private final Set<UUID> playersWithAlerts;
    private final SchedulerAdapter scheduler;
    private Config config;
    private MessagesConfig messagesConfig;

    public AlertManager(Main plugin, Config config) {
        this.config = config;
        this.messagesConfig = plugin.getMessagesConfig();
        this.logger = plugin.getLogger();
        this.playersWithAlerts = new CopyOnWriteArraySet<>();
        this.scheduler = SchedulerManager.getAdapter();
    }

    private String getPrefix() {
        return ColorUtil.colorize(messagesConfig.getPrefix());
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public boolean toggleAlerts(Player player) {
        UUID uuid = player.getUniqueId();
        if (playersWithAlerts.contains(uuid)) {
            playersWithAlerts.remove(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("alerts-disabled"));
            player.sendMessage(getPrefix() + msg);
            return false;
        } else {
            playersWithAlerts.add(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("alerts-enabled"));
            player.sendMessage(getPrefix() + msg);
            return true;
        }
    }

    public void enableAlerts(Player player) {
        playersWithAlerts.add(player.getUniqueId());
    }

    public void disableAlerts(Player player) {
        playersWithAlerts.remove(player.getUniqueId());
    }

    public boolean hasAlertsEnabled(Player player) {
        return playersWithAlerts.contains(player.getUniqueId());
    }

    private boolean canReceiveAlerts(Player player) {
        return player.hasPermission(Permissions.ALERTS) || player.hasPermission(Permissions.ADMIN);
    }

    public void sendAlert(String suspectName, double probability, double buffer) {
        sendAlert(suspectName, probability, buffer, null, new String[]{"unknown"}, CheckType.UNKNOWN);
    }

    public void sendAlert(String suspectName, int vl, double buffer, String checkName, String verbose) {
        String message = formatDefaultAlertMessage(suspectName, vl, buffer, checkName, verbose);
        scheduler.runSync(() -> {
            for (UUID uuid : playersWithAlerts) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && canReceiveAlerts(player)) {
                    player.sendMessage(message);
                }
            }
            if (config.isAiConsoleAlerts()) {
                logger.info(ColorUtil.stripColors(message));
            }
        });
    }

    public void sendAlert(String suspectName, double probability, double buffer, String modelName, String[] bestNames, CheckType checkType) {
        String message = formatAlertMessage(suspectName, probability, buffer, modelName, bestNames, checkType);
        scheduler.runSync(() -> {
            for (UUID uuid : playersWithAlerts) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && canReceiveAlerts(player)) {
                    player.sendMessage(message);
                }
            }
            if (config.isAiConsoleAlerts()) {
                logger.info(ColorUtil.stripColors(message));
            }
        });
    }

    public void sendAlert(String suspectName, double probability, double buffer, int vl) {
        sendAlert(suspectName, probability, buffer, vl, null, new String[]{"unknown"}, CheckType.UNKNOWN);
    }

    public void sendAlert(String suspectName, double probability, double buffer, int vl, String modelName, String[] bestNames, CheckType checkType) {
        String message = formatAlertMessage(suspectName, probability, buffer, vl, modelName, bestNames, checkType);
        scheduler.runSync(() -> {
            for (UUID uuid : playersWithAlerts) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && canReceiveAlerts(player)) {
                    player.sendMessage(message);
                }
            }
            if (config.isAiConsoleAlerts()) {
                logger.info(ColorUtil.stripColors(message));
            }
        });
    }

    private String formatDefaultAlertMessage(String suspectName, int vl, double buffer, String checkName, String verbose) {
        String template = messagesConfig.getMessage("default-alert-format", suspectName, vl, buffer);
        String checkDisplay = checkName != null ? checkName : "unknown";
        template = template.replace("{CHECK}", checkDisplay).replace("<check>", checkDisplay)
                .replace("{VERBOSE}", verbose).replace("<verbose>", verbose);
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, String modelName, CheckType checkType) {
        String pc = NametagManager.getColorInfo(probability);
        String template = messagesConfig.getMessage("alert-format", suspectName, probability, pc, buffer, 0);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String checkTypeDisplay = checkType != null ? checkType.name() : CheckType.UNKNOWN.name();
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay)
                .replace("{TYPE}", checkTypeDisplay).replace("<type>", checkTypeDisplay);
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, String modelName, String[] bestNames, CheckType checkType) {
        String pc = NametagManager.getColorInfo(probability);
        String template = messagesConfig.getMessage("alert-format", suspectName, probability, pc, buffer, 0);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String checkTypeDisplay = checkType != null ? checkType.name() : CheckType.UNKNOWN.name();
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay)
                .replace("{TYPE}", checkTypeDisplay).replace("<type>", checkTypeDisplay);
        int IN = LocalModel.IN;
        for (int i = 0; i < IN; i++) {
            String value = (i < bestNames.length) ? bestNames[i] : "unknown";
            template = template.replace("{BEST_" + i + "}", value)
                    .replace("<best_" + i + ">", value);
        }
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, int vl, String modelName, CheckType checkType) {
        String pc = NametagManager.getColorInfo(probability);
        String template = messagesConfig.getMessage("alert-format-vl", suspectName, probability, pc, buffer, vl);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String checkTypeDisplay = checkType != null ? checkType.name() : CheckType.UNKNOWN.name();
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay)
                .replace("{TYPE}", checkTypeDisplay).replace("<type>", checkTypeDisplay);
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, int vl, String modelName, String[] bestNames, CheckType checkType) {
        String pc = NametagManager.getColorInfo(probability);
        String template = messagesConfig.getMessage("alert-format-vl", suspectName, probability, pc, buffer, vl);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String checkTypeDisplay = checkType != null ? checkType.name() : CheckType.UNKNOWN.name();
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay)
                .replace("{TYPE}", checkTypeDisplay).replace("<type>", checkTypeDisplay);
        int IN = LocalModel.IN;
        for (int i = 0; i < IN; i++) {
            String value = (i < bestNames.length) ? bestNames[i] : "unknown";
            template = template.replace("{BEST_" + i + "}", value)
                    .replace("<best_" + i + ">", value);
        }
        return getPrefix() + ColorUtil.colorize(template);
    }

    public void handlePlayerQuit(Player player) {
        playersWithAlerts.remove(player.getUniqueId());
    }

    public boolean shouldAlert(double probability) {
        return probability >= config.getAiAlertThreshold();
    }

    public double getAlertThreshold() {
        return config.getAiAlertThreshold();
    }
}