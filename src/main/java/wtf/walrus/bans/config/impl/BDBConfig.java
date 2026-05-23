package wtf.walrus.bans.config.impl;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import wtf.walrus.bans.config.BDRecord;
import wtf.walrus.bans.config.BansConfig;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BDBConfig {
    public final BansConfig config;
    public final Set<BDRecord> records = new HashSet<>();

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
        this.enabled = fileConfiguration.getBoolean("enabled", false);
        ConfigurationSection section = fileConfiguration.getConfigurationSection("bans");
        if (section != null) {
            for (String n : section.getKeys(false)) {
                ConfigurationSection user = section.getConfigurationSection(n);
                if (user != null) {
                    long time = user.getLong("time");
                    String record = user.getString("record", n +".csv");
                    BDRecord bdRecord = new BDRecord(UUID.fromString(n), time, record);
                    this.records.add(bdRecord);
                }
            }
        }
    }

    public void save(File file) {
        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void set(UUID uuid, String record, long time) {
        fileConfiguration.set("bans." + uuid + "." + UUID.randomUUID() + ".time", time);
        fileConfiguration.set("bans." + uuid + "." + UUID.randomUUID() + ".record", record);
        save(file);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
