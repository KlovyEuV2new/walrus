/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 */

package wtf.walrus.menu;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import wtf.walrus.checks.CheckType;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.Config;
import wtf.walrus.config.HologramConfig;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.ml.managers.VerdictManager;
import wtf.walrus.server.AnalyticsClient;
import wtf.walrus.util.ColorUtil;
import wtf.walrus.Main;
import wtf.walrus.scheduler.SchedulerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SuspectsMenu implements Listener {

    private final JavaPlugin plugin;
    private final Player admin;
    private final Inventory inventory;
    private final AICheck aiCheck;
    private final MiningCheck miningCheck;
    private final AnalyticsClient analyticsClient;
    private final Config pluginConfig;
    private int page = 0;
    private BukkitTask updateTask;
    private static final int ITEMS_PER_PAGE = 45;

    public SuspectsMenu(JavaPlugin plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
        Main main = (Main) plugin;
        this.aiCheck = main.getAiCheck();
        this.miningCheck = main.getMiningCheck();
        this.analyticsClient = main.getAnalyticsClient();
        this.pluginConfig = main.getPluginConfig();
        FileConfiguration config = main.getMenuConfig().getConfig();
        String title = config.getString("gui.title", "&cMLSAC &8> &7Suspects");
        this.inventory = Bukkit.createInventory(null, 54, ColorUtil.colorize(title));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open() {
        buildAndApply(true);
        admin.openInventory(inventory);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> buildAndApply(false), 20L, 20L);
    }

    private void buildAndApply(boolean full) {
        SchedulerManager.getAdapter().runAsync(() -> {
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

            List<SuspectData> suspectDataList = onlinePlayers.stream()
                    .map(p -> {
                        AIPlayerData combatData = aiCheck.getOrCreatePlayerData(p);
                        MiningPlayerData miningData = miningCheck.getOrCreatePlayerData(p);

                        if ((combatData == null || combatData.getProbabilityHistory().isEmpty()) &&
                                (miningData == null || miningData.getProbabilityHistory().isEmpty())) return null;

                        return new SuspectData(
                                p.getUniqueId(),
                                p.getName(),
                                combatData != null ? combatData.getAverageProbability() : 0.0,
                                combatData != null ? new ArrayList<>(combatData.getProbabilityHistory()) : new ArrayList<>(),
                                combatData != null ? combatData.getLastAttackTime() : 0L,
                                miningData != null ? miningData.getAverageProbability() : 0.0,
                                miningData != null ? new ArrayList<>(miningData.getProbabilityHistory()) : new ArrayList<>(),
                                miningData != null ? miningData.getLastAttackTime() : 0L
                        );
                    })
                    .filter(Objects::nonNull)
                    .sorted((d1, d2) -> Double.compare(Math.max(d2.avgProbability, d2.mineAvg), Math.max(d1.avgProbability, d1.mineAvg)))
                    .collect(Collectors.toList());

            final int totalPages = (int) Math.ceil((double) suspectDataList.size() / ITEMS_PER_PAGE);
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;
            if (page < 0) page = 0;

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, suspectDataList.size());
            List<SuspectData> pageData = suspectDataList.subList(start, end);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (SuspectData data : pageData) {
                if (analyticsClient != null) {
                    futures.add(analyticsClient.checkPlayer(data.name).thenAccept(result -> {
                        if (result.isFound()) {
                            data.analyticsDetections = result.getTotalDetections();
                            data.analyticsFound = true;
                        }
                    }));
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                FileConfiguration config = ((Main) plugin).getMenuConfig().getConfig();
                ItemStack[] newContents = new ItemStack[54];

                for (int i = 0; i < pageData.size(); i++) {
                    newContents[i] = createSuspectHeadFromData(pageData.get(i), config);
                }

                if (page > 0) {
                    Material prevMat = Material.valueOf(config.getString("gui.items.previous_page.material", "ARROW"));
                    newContents[45] = createButtonItem(prevMat, config.getString("gui.items.previous_page.name", "&ePage {PAGE}").replace("{PAGE}", String.valueOf(page)));
                }

                Material infoMat = Material.valueOf(config.getString("gui.items.page_info.material", "PAPER"));
                newContents[49] = createButtonItem(infoMat, config.getString("gui.items.page_info.name", "&b{CURRENT}/{TOTAL}")
                        .replace("{CURRENT}", String.valueOf(page + 1))
                        .replace("{TOTAL}", String.valueOf(Math.max(1, totalPages))));

                if (end < suspectDataList.size()) {
                    Material nextMat = Material.valueOf(config.getString("gui.items.next_page.material", "ARROW"));
                    newContents[53] = createButtonItem(nextMat, config.getString("gui.items.next_page.name", "&ePage {PAGE}").replace("{PAGE}", String.valueOf(page + 2)));
                }

                Material fillerMat = Material.valueOf(config.getString("gui.items.filler.material", "GRAY_STAINED_GLASS_PANE"));
                ItemStack filler = createButtonItem(fillerMat, config.getString("gui.items.filler.name", " "));
                for (int i = 45; i < 54; i++) if (newContents[i] == null) newContents[i] = filler;

                SchedulerManager.getAdapter().runSync(() -> {
                    if (full) {
                        inventory.setContents(newContents);
                    } else {
                        ItemStack[] current = inventory.getContents();
                        for (int i = 0; i < 54; i++) {
                            if (!itemsEqual(current[i], newContents[i])) inventory.setItem(i, newContents[i]);
                        }
                    }
                });
            });
        });
    }

    private boolean itemsEqual(ItemStack a, ItemStack b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        ItemMeta ma = a.getItemMeta();
        ItemMeta mb = b.getItemMeta();
        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;
        return Objects.equals(ma.getDisplayName(), mb.getDisplayName()) && Objects.equals(ma.getLore(), mb.getLore());
    }

    private static class SuspectData {
        final UUID uuid;
        final String name;
        final double avgProbability;
        final List<Double> history;
        final double mineAvg;
        final List<Double> mineHistory;
        volatile int analyticsDetections = 0;
        volatile boolean analyticsFound = false;
        volatile long lastAttackTime = 0, lastMineTime;

        SuspectData(UUID uuid, String name, double avgProb, List<Double> history, long lastAttack, double mineAvg, List<Double> mineHistory, long lastMineTime) {
            this.uuid = uuid;
            this.name = name;
            this.avgProbability = avgProb;
            this.history = history;
            this.lastAttackTime = lastAttack;
            this.mineAvg = mineAvg;
            this.mineHistory = mineHistory;
            this.lastMineTime = lastMineTime;
        }
    }

    private ItemStack createSuspectHeadFromData(SuspectData data, FileConfiguration config) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            Player suspect = Bukkit.getPlayer(data.uuid);
            meta.setOwningPlayer(suspect != null ? suspect : Bukkit.getOfflinePlayer(data.uuid));
            meta.setDisplayName(ColorUtil.colorize(config.getString("gui.items.suspect_head.name", "&c{PLAYER}").replace("{PLAYER}", data.name)));

            List<String> loreFormat = config.getStringList("gui.items.suspect_head.lore");
            List<String> lore = new ArrayList<>();

            int hitSec = (int) ((System.currentTimeMillis() - data.lastAttackTime) / 1000.0);
            String hitStr = hitSec < 0 ? "<0" : (hitSec > 1000 ? "1000+" : String.valueOf(hitSec));

            int mineSec = (int) ((System.currentTimeMillis() - data.lastMineTime) / 1000.0);
            String mineStr = mineSec < 0 ? "<0" : (mineSec > 1000 ? "1000+" : String.valueOf(mineSec));

            StringBuilder historyStr = new StringBuilder();
            data.history.stream().skip(Math.max(0, data.history.size() - 5)).forEach(v -> historyStr.append(getColorInfo(v)).append(" "));

            StringBuilder mineHistStr = new StringBuilder();
            data.mineHistory.stream().skip(Math.max(0, data.mineHistory.size() - 5)).forEach(v -> mineHistStr.append(getColorInfo(v)).append(" "));

            VerdictManager verdictManager = Main.instance.getVerdictManager();

            CheckType lastType = verdictManager.getLastVerdict(data.uuid);
            Object lastClass = verdictManager.getLastClass(data.uuid);

            boolean invalidLast = false;
            int lastSec = 0;
            String lastSecStr = "N/A";
            List<Double> lastHistory = new ArrayList<>();
            StringBuilder lastHistStr;
            double lastAvg = 0;
            if (lastClass instanceof AICheck) {
                lastSec = hitSec;
                lastHistStr = historyStr;
                lastAvg = data.avgProbability;
                lastHistory = data.history;
                data.mineHistory.stream().skip(Math.max(0, data.mineHistory.size() - 5)).forEach(v -> lastHistStr.append(getColorInfo(v)).append(" "));
            } else if (lastClass instanceof MiningCheck) {
                lastSec = mineSec;
                lastHistStr = mineHistStr;
                lastAvg = data.mineAvg;
                lastHistory = data.mineHistory;
                data.mineHistory.stream().skip(Math.max(0, data.mineHistory.size() - 5)).forEach(v -> lastHistStr.append(getColorInfo(v)).append(" "));
            } else {
                lastHistStr = new StringBuilder();
                invalidLast = true;
            }
            lastSecStr = lastSec < 0 ? "<0" : (lastSec > 1000 ? "1000+" : String.valueOf(lastSec));

            for (String line : loreFormat) {
                String detectionsStr = data.analyticsFound ? pluginConfig.getDetectionColor(data.analyticsDetections) + data.analyticsDetections : "&7N/A";

                String processed = line
                        .replace("{LAST}", lastType.name())
                        .replace("{AVG_PROB}", getColorInfo(data.avgProbability))
                        .replace("{HISTORY}", historyStr.toString().trim())
                        .replace("{LAST_HIT}", hitStr)
                        .replace("{LAST_MINE}", mineStr)
                        .replace("{DETECTIONS}", detectionsStr)
                        .replace("{MINE_AVG_PROB}", getColorInfo(data.mineAvg))
                        .replace("{HISTORY_SIZE}", String.valueOf(data.history.size()))
                        .replace("{MINE_HISTORY_SIZE}", String.valueOf(data.mineHistory.size()))
                        .replace("{MINE_HISTORY}", mineHistStr.toString().trim())
                        .replace("{LAST_AVG_PROB}", getColorInfo(lastAvg))
                        .replace("{LAST_HISTORY}", lastHistStr)
                        .replace("{LAST_ACTION}", lastSecStr)
                        .replace("{LAST_HISTORY_SIZE}", String.valueOf(lastHistory.size()));

                for (int i = 0; i < 20; i++) {
                    processed = processed.replace("{PROB_" + (i + 1) + "}", i < data.history.size() ? getColorInfo(data.history.get(i)) : "");
                    processed = processed.replace("{MINE_PROB_" + (i + 1) + "}", i < data.mineHistory.size() ? getColorInfo(data.mineHistory.get(i)) : "");
                    if (!invalidLast) {
                        processed = processed.replace("{LAST_PROB_" + (i + 1) + "}", i < lastHistory.size() ? getColorInfo(lastHistory.get(i)) : "");
                    } else processed = processed.replace("{LAST_PROB_" + (i + 1) + "}", "");
                }
                lore.add(ColorUtil.colorize(processed));
            }
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createButtonItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getColorInfo(double val) {
        HologramConfig holo = ((Main) plugin).getHologramConfig();
        String fmt = String.format("%.4f", val);
        if (val < 0.5) return holo.getColorLow() + fmt;
        if (val < 0.6) return holo.getColorMedium() + fmt;
        if (val < 0.8) return holo.getColorHigh() + fmt;
        if (val < 0.9) return holo.getColorCritical() + fmt;
        return holo.getColorCriticalBold() + fmt;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        FileConfiguration config = ((Main) plugin).getMenuConfig().getConfig();
        if (event.getSlot() == 45 && page > 0) { page--; buildAndApply(true); return; }
        if (event.getSlot() == 53) { page++; buildAndApply(true); return; }

        if (item.getItemMeta() instanceof SkullMeta meta) {
            Player target = meta.getOwningPlayer() != null ? meta.getOwningPlayer().getPlayer() : null;
            if (target != null && target.isOnline()) {
                if (event.isLeftClick()) admin.teleport(target);
                else if (event.isRightClick()) { admin.setGameMode(GameMode.SPECTATOR); admin.teleport(target); }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory() == inventory) {
            if (updateTask != null) updateTask.cancel();
            HandlerList.unregisterAll(this);
        }
    }
}