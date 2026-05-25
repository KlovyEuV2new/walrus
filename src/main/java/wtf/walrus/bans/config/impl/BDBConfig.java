package wtf.walrus.bans.config.impl;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import wtf.walrus.bans.config.BDRecord;
import wtf.walrus.bans.config.BansConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class BDBConfig {
    public final BansConfig config;
    public final Set<BDRecord> records = new HashSet<>();
    public final Set<BDRecord> dataRecords = new HashSet<>();

    private boolean enabled = false;

    public final File file;
    public final FileConfiguration fileConfiguration;

    public BDBConfig(BansConfig config) {
        this.config = config;
        this.file = new File(config.manager.plugin.getDataFolder(), "bans.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try {
                config.manager.plugin.saveResource("bans.yml", false);
            } catch (Exception ex) {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        this.fileConfiguration = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public void load() {
        records.clear();
        dataRecords.clear();
        this.enabled = fileConfiguration.getBoolean("enabled", false);

        ConfigurationSection bansSection = fileConfiguration.getConfigurationSection("bans");
        if (bansSection != null) {
            for (String uuidStr : bansSection.getKeys(false)) {
                ConfigurationSection userSection = bansSection.getConfigurationSection(uuidStr);
                if (userSection == null) continue;

                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                for (String entryId : userSection.getKeys(false)) {
                    ConfigurationSection entry = userSection.getConfigurationSection(entryId);
                    if (entry == null) continue;
                    long time = entry.getLong("time");
                    String record = entry.getString("record", entryId + ".csv");
                    records.add(new BDRecord(uuid, time, record));
                }
            }
        }

        ConfigurationSection dataSection = fileConfiguration.getConfigurationSection("data");
        if (dataSection != null) {
            for (String entryId : dataSection.getKeys(false)) {
                ConfigurationSection entry = dataSection.getConfigurationSection(entryId);
                if (entry == null) continue;
                long time = entry.getLong("time");
                String record = entry.getString("record", entryId + ".csv");
                dataRecords.add(new BDRecord(null, time, record));
            }
        }
    }

    public void save() {
        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void save(File file) {
        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int getBansLimit() {
        return fileConfiguration.getInt("limit.bans.files", 100);
    }

    private int getDataLimit() {
        return fileConfiguration.getInt("limit.data.files", 100);
    }

    private void enforceBansLimit() {
        int limit = getBansLimit();
        if (records.size() <= limit) return;

        List<BDRecord> sorted = records.stream()
                .sorted(Comparator.comparingLong(BDRecord::time))
                .collect(Collectors.toList());

        int toRemove = records.size() - limit;
        for (int i = 0; i < toRemove; i++) {
            BDRecord oldest = sorted.get(i);
            deleteFileFromDisk(oldest.record());
            removeBansEntry(oldest.record());
        }
    }

    private void enforceDataLimit() {
        int limit = getDataLimit();
        if (dataRecords.size() <= limit) return;

        List<BDRecord> sorted = dataRecords.stream()
                .sorted(Comparator.comparingLong(BDRecord::time))
                .collect(Collectors.toList());

        int toRemove = dataRecords.size() - limit;
        for (int i = 0; i < toRemove; i++) {
            BDRecord oldest = sorted.get(i);
            deleteFileFromDisk(oldest.record());
            removeDataEntry(oldest.record());
        }
    }

    private void deleteFileFromDisk(String recordName) {
        Path basePath = new File(config.manager.plugin.getDataFolder(), "mls/bdata").toPath();

        try {
            Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(recordName))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            config.manager.plugin.getLogger().warning(
                                    "[BanSystem] Failed to delete: " + path.toAbsolutePath()
                            );
                        }
                    });

        } catch (IOException e) {
            config.manager.plugin.getLogger().severe(
                    "[BanSystem] Error while searching files: " + e.getMessage()
            );
        }
    }

    public void set(UUID uuid, String record, long time) {
        UUID entryId = UUID.randomUUID();
        fileConfiguration.set("bans." + uuid + "." + entryId + ".time", time);
        fileConfiguration.set("bans." + uuid + "." + entryId + ".record", record);
        records.add(new BDRecord(uuid, time, record));
        save();
        enforceBansLimit();
    }

    public void set(String record, long time) {
        UUID entryId = UUID.randomUUID();
        fileConfiguration.set("data." + entryId + ".time", time);
        fileConfiguration.set("data." + entryId + ".record", record);
        dataRecords.add(new BDRecord(null, time, record));
        save();
        enforceDataLimit();
    }

    public void remove(String record) {
        removeBansEntry(record);
        wtf.walrus.bans.menu.BansMenu.removeRecord(record);
    }

    public void delete(String record) {
        remove(record);
        deleteFileFromDisk(record);
    }


    private void removeBansEntry(String record) {
        ConfigurationSection bansSection = fileConfiguration.getConfigurationSection("bans");
        if (bansSection == null) return;
        for (String uuidStr : bansSection.getKeys(false)) {
            ConfigurationSection userSection = bansSection.getConfigurationSection(uuidStr);
            if (userSection == null) continue;
            for (String entryId : userSection.getKeys(false)) {
                ConfigurationSection entry = userSection.getConfigurationSection(entryId);
                if (entry == null) continue;
                if (record.equals(entry.getString("record"))) {
                    fileConfiguration.set("bans." + uuidStr + "." + entryId, null);
                    save();
                    records.removeIf(r -> record.equals(r.record()));
                    wtf.walrus.bans.menu.BansMenu.removeRecord(record);
                    return;
                }
            }
        }
    }

    private void removeDataEntry(String record) {
        ConfigurationSection dataSection = fileConfiguration.getConfigurationSection("data");
        if (dataSection == null) return;
        for (String entryId : dataSection.getKeys(false)) {
            ConfigurationSection entry = dataSection.getConfigurationSection(entryId);
            if (entry == null) continue;
            if (record.equals(entry.getString("record"))) {
                fileConfiguration.set("data." + entryId, null);
                save();
                dataRecords.removeIf(r -> record.equals(r.record()));
                return;
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}