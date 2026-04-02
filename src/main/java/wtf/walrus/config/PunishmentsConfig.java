/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class PunishmentsConfig {

    private final JavaPlugin plugin;
    private final Logger logger;
    private File file;
    private FileConfiguration config;

    private final Map<String, List<PunishmentEntry>> checkPunishments = new HashMap<>();

    public PunishmentsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        load();
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "punishments.yml");

        if (!file.exists()) {
            plugin.saveResource("punishments.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource("punishments.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        reload();
    }

    public void reload() {
        if (file != null) {
            config = YamlConfiguration.loadConfiguration(file);
        }
        checkPunishments.clear();
        parse();
    }

    private void parse() {
        for (String groupKey : config.getKeys(false)) {
            ConfigurationSection group = config.getConfigurationSection(groupKey);
            if (group == null) continue;

            List<String> checks = group.getStringList("checks");
            List<String> rawPunishments = group.getStringList("punishments");

            List<PunishmentEntry> entries = parsePunishments(rawPunishments, groupKey);

            for (String checkName : checks) {
                if (checkPunishments.containsKey(checkName)) {
                    logger.warning("[PunishmentsConfig] Check '" + checkName
                            + "' already registered in another group! Skipping duplicate in '" + groupKey + "'.");
                    continue;
                }
                checkPunishments.put(checkName, entries);
            }
        }
        logger.info("[PunishmentsConfig] Loaded punishments for " + checkPunishments.size() + " check(s).");
    }

    private List<PunishmentEntry> parsePunishments(List<String> rawList, String groupKey) {
        List<PunishmentEntry> entries = new ArrayList<>();
        for (String raw : rawList) {
            int colonIdx = raw.indexOf(':');
            if (colonIdx < 1) {
                logger.warning("[PunishmentsConfig] Invalid punishment format in group '" + groupKey + "': " + raw);
                continue;
            }
            String vlPart = raw.substring(0, colonIdx).trim();
            String command = raw.substring(colonIdx + 1).trim();
            try {
                int vl = Integer.parseInt(vlPart);
                entries.add(new PunishmentEntry(vl, command));
            } catch (NumberFormatException e) {
                logger.warning("[PunishmentsConfig] Invalid VL '" + vlPart + "' in group '" + groupKey + "'.");
            }
        }
        entries.sort(Comparator.comparingInt(PunishmentEntry::getVl));
        return entries;
    }

    public String getPunishmentCommand(String checkName, int vl) {
        List<PunishmentEntry> entries = checkPunishments.get(checkName);
        if (entries == null) return null;

        // Ищем точное совпадение VL
        for (PunishmentEntry entry : entries) {
            if (entry.getVl() == vl) {
                return entry.getCommand();
            }
        }
        return null;
    }

    public List<PunishmentEntry> getPunishments(String checkName) {
        return checkPunishments.getOrDefault(checkName, Collections.emptyList());
    }

    public boolean hasCheck(String checkName) {
        return checkPunishments.containsKey(checkName);
    }

    public Map<String, List<PunishmentEntry>> getAllPunishments() {
        return Collections.unmodifiableMap(checkPunishments);
    }

    public static class PunishmentEntry {
        private final int vl;
        private final String command;

        public PunishmentEntry(int vl, String command) {
            this.vl = vl;
            this.command = command;
        }

        public int getVl() { return vl; }
        public String getCommand() { return command; }

        @Override
        public String toString() {
            return vl + ":" + command;
        }
    }
}