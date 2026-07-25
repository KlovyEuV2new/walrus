package wtf.walrus.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.walrus.data.DataType;
import wtf.walrus.session.SavedSession;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DataConfig {

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private final File configFile;

    private List<SavedSession> sessions = new ArrayList<>();

    public DataConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "sessions.yml");

        load();
        sessions = get();
    }

    public void clear() {
        sessions.clear();
        config.set("sessions", null);
        save();
    }

    public void set(List<SavedSession> sessions) {
        this.sessions = new ArrayList<>(sessions);

        config.set("sessions", null);

        for (int i = 0; i < sessions.size(); i++) {
            SavedSession session = sessions.get(i);
            String path = "sessions." + i;

            config.set(path + ".uuid", session.uuid().toString());
            config.set(path + ".label", session.label().name());
            config.set(path + ".comment", session.comment());
            config.set(path + ".type", session.type().name());
        }

        save();
    }

    public List<SavedSession> get() {
        List<SavedSession> result = new ArrayList<>();

        if (!config.isConfigurationSection("sessions")) {
            return result;
        }

        for (String key : config.getConfigurationSection("sessions").getKeys(false)) {
            String path = "sessions." + key;

            try {
                result.add(new SavedSession(
                        UUID.fromString(config.getString(path + ".uuid")),
                        Label.valueOf(config.getString(path + ".label")),
                        config.getString(path + ".comment", ""),
                        DataType.valueOf(config.getString(path + ".type"))
                ));
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    public void load() {
        if (!configFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                configFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public FileConfiguration getConfig() {
        if (config == null) {
            load();
        }
        return config;
    }

    public void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save sessions.yml!");
        }
    }

    public void reload() {
        load();
        sessions = get();
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public List<SavedSession> getSessions() {
        return sessions;
    }
}