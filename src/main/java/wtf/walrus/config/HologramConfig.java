/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 */

package wtf.walrus.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class HologramConfig {

    // ── Defaults ──────────────────────────────────────────────────────────────
    public static final boolean DEFAULT_ENABLED          = true;
    public static final String  DEFAULT_FORMAT           = "&f{HISTORY}{NL}&fAVG: {AVG_COLORED}";
    public static final double  DEFAULT_HEIGHT_OFFSET    = 0.2;
    public static final int     DEFAULT_UPDATE_INTERVAL  = 1;

    public static final String  DEFAULT_COLOR_LOW            = "&a";
    public static final String  DEFAULT_COLOR_MEDIUM         = "&6";
    public static final String  DEFAULT_COLOR_HIGH           = "&c";
    public static final String  DEFAULT_COLOR_CRITICAL       = "&4";
    public static final String  DEFAULT_COLOR_CRITICAL_BOLD  = "&4&l";

    // ── Fields ────────────────────────────────────────────────────────────────
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;

    private boolean enabled;
    private String  format;
    private double  heightOffset;
    private int     updateInterval;

    private String colorLow;
    private String colorMedium;
    private String colorHigh;
    private String colorCritical;
    private String colorCriticalBold;

    public HologramConfig(JavaPlugin plugin) {
        this.plugin     = plugin;
        this.configFile = new File(plugin.getDataFolder(), "holograms.yml");
        loadDefaults();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void loadDefaults() {
        this.enabled          = DEFAULT_ENABLED;
        this.format           = DEFAULT_FORMAT;
        this.heightOffset     = DEFAULT_HEIGHT_OFFSET;
        this.updateInterval   = DEFAULT_UPDATE_INTERVAL;
        this.colorLow         = DEFAULT_COLOR_LOW;
        this.colorMedium      = DEFAULT_COLOR_MEDIUM;
        this.colorHigh        = DEFAULT_COLOR_HIGH;
        this.colorCritical    = DEFAULT_COLOR_CRITICAL;
        this.colorCriticalBold = DEFAULT_COLOR_CRITICAL_BOLD;
    }

    private void loadValues() {
        this.enabled         = config.getBoolean("nametags.enabled",              DEFAULT_ENABLED);
        this.format          = config.getString("nametags.format",                DEFAULT_FORMAT);
        this.heightOffset    = config.getDouble("nametags.height_offset",         DEFAULT_HEIGHT_OFFSET);
        this.updateInterval  = config.getInt("nametags.update_interval_ticks",    DEFAULT_UPDATE_INTERVAL);

        // * colors from nametags.colors section
        this.colorLow         = config.getString("nametags.colors.low",           DEFAULT_COLOR_LOW);
        this.colorMedium      = config.getString("nametags.colors.medium",        DEFAULT_COLOR_MEDIUM);
        this.colorHigh        = config.getString("nametags.colors.high",          DEFAULT_COLOR_HIGH);
        this.colorCritical    = config.getString("nametags.colors.critical",      DEFAULT_COLOR_CRITICAL);
        this.colorCriticalBold = config.getString("nametags.colors.critical_bold", DEFAULT_COLOR_CRITICAL_BOLD);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("holograms.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        loadValues();
    }

    public FileConfiguration getConfig() {
        if (config == null) {
            load();
        }
        return config;
    }

    public void save() {
        if (config == null || configFile == null)
            return;
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save holograms.yml!");
        }
    }

    public void reload() {
        load();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isEnabled()          { return enabled; }
    public String  getFormat()          { return format; }
    public double  getHeightOffset()    { return heightOffset; }
    public int     getUpdateInterval()  { return updateInterval; }

    public String getColorLow()         { return colorLow; }
    public String getColorMedium()      { return colorMedium; }
    public String getColorHigh()        { return colorHigh; }
    public String getColorCritical()    { return colorCritical; }
    public String getColorCriticalBold(){ return colorCriticalBold; }
}