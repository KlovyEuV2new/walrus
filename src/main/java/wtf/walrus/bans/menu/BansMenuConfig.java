package wtf.walrus.bans.menu;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import wtf.walrus.Main;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class BansMenuConfig {

    private final Main plugin;
    private final File file;
    private FileConfiguration config;

    public BansMenuConfig(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans-menu.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try {
                plugin.saveResource("bans-menu.yml", false);
            } catch (Exception ex) {
                createDefault();
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);

        InputStream defStream = plugin.getResource("bans-menu.yml");
        if (defStream != null) {
            FileConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            config.setDefaults(defConfig);
        }
    }

    private void createDefault() {
        config = new YamlConfiguration();

        config.set("bans-gui.title", "&4MLSAC &8> &7Ban Logs");

        config.set("bans-gui.items.record_head.name", "&c{PLAYER} &8[&7#{INDEX}&8]");
        config.set("bans-gui.items.record_head.lore", java.util.Arrays.asList(
                "&7Игрок: &f{PLAYER}",
                "&7Файл: &e{FILE}",
                "&7Дата: &b{DATE}",
                "&7Тиков: &a{TICKS}",
                "",
                "&aЛКМ &8- &7Воспроизвести ротацию",
                "&cПКМ &8- &7Забанить + переместить в suspect"
        ));

        config.set("bans-gui.items.previous_page.material", "ARROW");
        config.set("bans-gui.items.previous_page.name", "&ePage {PAGE}");

        config.set("bans-gui.items.page_info.material", "PAPER");
        config.set("bans-gui.items.page_info.name", "&b{CURRENT}&8/&7{TOTAL}");

        config.set("bans-gui.items.next_page.material", "ARROW");
        config.set("bans-gui.items.next_page.name", "&ePage {PAGE}");

        config.set("bans-gui.items.filler.material", "RED_STAINED_GLASS_PANE");
        config.set("bans-gui.items.filler.name", " ");

        config.set("bans-gui.ban-commands", java.util.Arrays.asList(
                "ban {PLAYER} Читерство (AI AntiCheat)",
                "broadcast &c{PLAYER} &7был заблокирован системой MLSAC"
        ));

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[BansMenu] Could not save bans-menu.yml: " + e.getMessage());
        }
    }

    public void reload() {
        load();
    }

    public FileConfiguration getConfig() {
        return config;
    }
}