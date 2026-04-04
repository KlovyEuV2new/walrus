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

package wtf.walrus.listeners;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.walrus.Main;
import wtf.walrus.Permissions;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.Config;
import wtf.walrus.config.MessagesConfig;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.server.AnalyticsClient;
import wtf.walrus.session.SessionManager;
import wtf.walrus.util.ColorUtil;
import wtf.walrus.violation.ViolationManager;

public class PlayerListener implements Listener {
    private final JavaPlugin plugin;
    private final AICheck aiCheck;
    private final MiningCheck miningCheck;
    private final AlertManager alertManager;
    private final ViolationManager violationManager;
    private final SessionManager sessionManager;
    private final TickListener tickListener;
    private final wtf.walrus.hologram.NametagManager nametagManager;
    private final AnalyticsClient analyticsClient;
    private HitListener hitListener;

    public PlayerListener(JavaPlugin plugin, AICheck aiCheck, MiningCheck miningCheck, AlertManager alertManager,
                          ViolationManager violationManager, SessionManager sessionManager,
                          TickListener tickListener, wtf.walrus.hologram.NametagManager nametagManager,
                          AnalyticsClient analyticsClient) {
        this.plugin = plugin;
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
        this.alertManager = alertManager;
        this.violationManager = violationManager;
        this.sessionManager = sessionManager;
        this.tickListener = tickListener;
        this.nametagManager = nametagManager;
        this.analyticsClient = analyticsClient;
    }

    public void setHitListener(HitListener hitListener) {
        this.hitListener = hitListener;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (hitListener != null) {
            hitListener.cacheEntity(player);
        }
        if (tickListener != null) {
            tickListener.startPlayerTask(player);
        }

        try {
            SchedulerManager.getAdapter().runSyncDelayed(() -> {
                if (player.isOnline()) {
                    if (player.hasPermission(Permissions.ALERTS) || player.hasPermission(Permissions.ADMIN)) {
                        if (player.hasPermission(Permissions.ALERTS_ON_JOIN)) alertManager.enableAlerts(player);

                        if (plugin instanceof Main) {
                            Main main = (Main) plugin;
                            if (main.getUpdateChecker() != null && main.getUpdateChecker().isUpdateAvailable()) {
                                player.sendMessage(
                                        ChatColor.GOLD + "=================================================");
                                player.sendMessage(ChatColor.YELLOW + "A NEW MLSAC UPDATE IS AVAILABLE: "
                                        + ChatColor.WHITE + main.getUpdateChecker().getLatestVersion());
                                player.sendMessage(ChatColor.YELLOW + "Get it from GitHub: " + ChatColor.AQUA
                                        + "https://github.com/MLSAC/client-side/releases");
                                player.sendMessage(
                                        ChatColor.GOLD + "=================================================");
                            }
                        }
                    }
                }
            }, 20L);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to schedule player join task: " + e.getMessage());
        }

        if (analyticsClient != null && plugin instanceof Main) {
            Main main = (Main) plugin;
            Config config = main.getPluginConfig();
            if (config.isAnalyticsEnabled()) {
                analyticsClient.checkPlayer(player.getName()).thenAccept(result -> {
                    if (result.isFound() && result.getTotalDetections() >= config.getAnalyticsMinDetections()) {
                        MessagesConfig messagesConfig = main.getMessagesConfig();
                        String colorCode = config.getDetectionColor(result.getTotalDetections());
                        String detectionsColored = colorCode + result.getTotalDetections();
                        String template = messagesConfig.getMessage("analytics-join-alert");
                        String raw = messagesConfig.getPrefix() + template
                                .replace("{PLAYER}", player.getName())
                                .replace("{DETECTIONS_COLORED}", detectionsColored)
                                .replace("{DETECTIONS}", String.valueOf(result.getTotalDetections()));
                        String message = ColorUtil.colorize(raw);

                        SchedulerManager.getAdapter().runSync(() -> {
                            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                                if (online.hasPermission(Permissions.ALERTS)
                                        || online.hasPermission(Permissions.ADMIN)) {
                                    online.sendMessage(message);
                                }
                            }
                            if (config.isAiConsoleAlerts()) {
                                plugin.getLogger().info(ColorUtil.stripColors(raw));
                            }
                        });
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    private void handlePlayerLeave(Player player) {
        if (hitListener != null) {
            hitListener.uncachePlayer(player);
        }
        if (tickListener != null) {
            tickListener.stopPlayerTask(player);
        }
        if (aiCheck != null) {
            aiCheck.handlePlayerQuit(player);
        }
        if (miningCheck != null) {
            miningCheck.handlePlayerQuit(player);
        }
        if (alertManager != null) {
            alertManager.handlePlayerQuit(player);
        }
        if (violationManager != null) {
            violationManager.handlePlayerQuit(player);
        }
        if (sessionManager != null) {
            sessionManager.removeAimProcessor(player.getUniqueId());
        }
        if (nametagManager != null) {
            nametagManager.handlePlayerQuit(player);
        }
    }
}