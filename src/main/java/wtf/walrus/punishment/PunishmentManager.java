/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.punishment;

import org.bukkit.Bukkit;
import wtf.walrus.Main;
import wtf.walrus.config.PunishmentsConfig;
import wtf.walrus.scheduler.SchedulerManager;

public class PunishmentManager {

    private final Main plugin;
    private PunishmentsConfig punishmentsConfig;

    public PunishmentManager(Main plugin, PunishmentsConfig punishmentsConfig) {
        this.plugin = plugin;
        this.punishmentsConfig = punishmentsConfig;
    }

    public void setPunishmentsConfig(PunishmentsConfig config) {
        this.punishmentsConfig = config;
    }

    public void handleViolation(String playerName, String checkName, int vl, double buffer) {
        String command = punishmentsConfig.getPunishmentCommand(checkName, vl);
        if (command == null) return;

        String resolved = command
                .replace("{player}", playerName)
                .replace("{check}", checkName)
                .replace("{vl}", String.valueOf(vl))
                .replace("{buffer}", String.format("%.2f", buffer));

        SchedulerManager.getAdapter().runSync(() -> {
            plugin.getLogger().info("[PunishmentManager] Executing: " + resolved);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        });
    }
}