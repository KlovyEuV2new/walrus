/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.bans.menu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import litebans.api.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import wtf.walrus.Main;
import wtf.walrus.bans.BansManager;
import wtf.walrus.bans.config.BDRecord;
import wtf.walrus.config.Label;
import wtf.walrus.data.TickData;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.rotationloader.RotationSession;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.util.ColorUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class BansMenu implements Listener {

    private static final class MenuConfig {
        String title;
        String headName;
        List<String> headLore;
        String prevName;
        Material prevMat;
        String nextName;
        Material nextMat;
        String infoName;
        Material infoMat;
        Material fillerMat;
        String fillerName;
        List<String> banCommands;
        List<String> unbanCommands;

        void reload(Main plugin) {
            var cfg = plugin.getBansMenuConfig().getConfig();

            title       = cfg.getString("bans-gui.title", "&4MLSAC &8> &7Ban Logs");
            headName    = cfg.getString("bans-gui.items.record_head.name", "&c{PLAYER}");
            headLore    = cfg.getStringList("bans-gui.items.record_head.lore");
            if (headLore.isEmpty()) headLore = Arrays.asList(
                    "&7Игрок: &f{PLAYER}",
                    "&7Файл: &e{FILE}",
                    "&7Дата: &b{DATE}",
                    "&7Тиков: &a{TICKS}",
                    "",
                    "&aЛКМ &8- &7Воспроизвести ротацию",
                    "&cПКМ &8- &7Забанить и переместить"
            );
            prevMat     = parseMaterial(cfg.getString("bans-gui.items.previous_page.material", "ARROW"));
            prevName    = cfg.getString("bans-gui.items.previous_page.name", "&ePage {PAGE}");
            nextMat     = parseMaterial(cfg.getString("bans-gui.items.next_page.material", "ARROW"));
            nextName    = cfg.getString("bans-gui.items.next_page.name", "&ePage {PAGE}");
            infoMat     = parseMaterial(cfg.getString("bans-gui.items.page_info.material", "PAPER"));
            infoName    = cfg.getString("bans-gui.items.page_info.name", "&b{CURRENT}/{TOTAL}");
            fillerMat   = parseMaterial(cfg.getString("bans-gui.items.filler.material", "RED_STAINED_GLASS_PANE"));
            fillerName  = cfg.getString("bans-gui.items.filler.name", " ");
            banCommands   = cfg.getStringList("bans-gui.ban-commands");
            unbanCommands = cfg.getStringList("bans-gui.unban-commands");
        }

        private static Material parseMaterial(String name) {
            try { return Material.valueOf(name); }
            catch (Exception e) { return Material.STONE; }
        }
    }

    private static final int ITEMS_PER_PAGE = 45;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private static final Map<String, CachedRecord> recordCache  = new ConcurrentHashMap<>();
    private static final List<CachedRecord>        sortedRecords = new ArrayList<>();
    private static final Set<BansMenu>             openMenus    = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final MenuConfig menuConfig = new MenuConfig();

    private final Main      plugin;
    private final Player    admin;
    private final Inventory inventory;
    private int         page = 0;
    private BukkitTask  updateTask;
    private BukkitTask  banRefreshTask;

    public static void reloadConfig(Main plugin) {
        menuConfig.reload(plugin);
    }

    public static void onRecordAdded(BDRecord record, String ownerName) {
        SchedulerManager.getAdapter().runAsync(() -> {
            try {
                String key = record.record();
                if (recordCache.containsKey(key)) return;
                OfflinePlayer player = Bukkit.getOfflinePlayer(ownerName);

                Entry ban = Main.instance.getBan(player);
                CachedRecord cached = new CachedRecord(record, ownerName, player.getUniqueId(), ban != null, ban);
                recordCache.put(key, cached);

                synchronized (sortedRecords) {
                    sortedRecords.add(0, cached);
                }

                for (BansMenu menu : openMenus) {
                    menu.loadTickCountAndRefresh(cached);
                }
            } catch (Exception ignored) {}
        });
    }

    public static void removeRecord(String fileName) {
        recordCache.remove(fileName);
        synchronized (sortedRecords) {
            sortedRecords.removeIf(r -> r.fileName.equals(fileName));
        }
        for (BansMenu menu : openMenus) {
            menu.buildAndApply(true);
        }
    }

    private static final class CachedRecord {
        final BDRecord bdRecord;
        final String   ownerName;
        final UUID ownerId;
        boolean        banned;
        Entry          ban;
        final long     time;
        final String   fileName;
        volatile boolean loaded   = false;
        volatile int     tickCount = 0;

        CachedRecord(BDRecord bdRecord, String ownerName, UUID ownerId, boolean banned, Entry ban) {
            this.bdRecord  = bdRecord;
            this.banned    = banned;
            this.ban       = ban;
            this.ownerName = ownerName;
            this.ownerId = ownerId;
            this.time      = bdRecord.time();
            this.fileName  = bdRecord.record();
        }
    }

    public BansMenu(Main plugin, Player admin) {
        this.plugin = plugin;
        this.admin  = admin;

        this.inventory = Bukkit.createInventory(null, 54, ColorUtil.colorize(menuConfig.title));

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open() {
        openMenus.add(this);
        loadRecordsAsync(() -> {
            buildAndApply(true);
            SchedulerManager.getAdapter().runSync(() -> admin.openInventory(inventory));
        });
        updateTask     = Bukkit.getScheduler().runTaskTimer(plugin, this::checkNewRecords, 100L, 100L);
        banRefreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshBanInfo, 300L, 300L);
    }

    private void refreshBanInfo() {
        synchronized (sortedRecords) {
            for (CachedRecord cached : sortedRecords) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(cached.bdRecord.uuid());
                Entry ban = Main.instance.getBan(player);
                cached.banned = ban != null;
                cached.ban    = ban;
            }
        }
        buildAndApply(false);
    }

    private void loadRecordsAsync(Runnable onDone) {
        SchedulerManager.getAdapter().runAsync(() -> {
            Set<BDRecord> allRecords = plugin.getBansManager().config.bdbConfig.records;

            List<CachedRecord> fresh = new ArrayList<>();
            for (BDRecord record : allRecords) {
                CachedRecord cached = recordCache.computeIfAbsent(record.record(), k -> {
                    String ownerName = getPlayerName(record.uuid());
                    OfflinePlayer player = Bukkit.getOfflinePlayer(ownerName);
                    Entry ban = Main.instance.getBan(player);
                    return new CachedRecord(record, ownerName, player.getUniqueId(), ban != null, ban);
                });
                fresh.add(cached);
            }

            fresh.sort((a, b) -> Long.compare(b.time, a.time));
            synchronized (sortedRecords) {
                for (CachedRecord r : fresh) {
                    boolean alreadyPresent = sortedRecords.stream().anyMatch(s -> s.fileName.equals(r.fileName));
                    if (!alreadyPresent) sortedRecords.add(r);
                }
                sortedRecords.sort((a, b) -> Long.compare(b.time, a.time));
            }

            for (CachedRecord cached : fresh) {
                if (!cached.loaded) loadTickCount(cached);
            }

            if (onDone != null) onDone.run();
        });
    }

    private void loadTickCount(CachedRecord cached) {
        SchedulerManager.getAdapter().runAsync(() -> {
            try {
                List<TickData> ticks = plugin.getBansManager().loadAndClose(plugin, null, cached.fileName);
                cached.tickCount = ticks != null ? ticks.size() : 0;
                cached.loaded    = true;
                if (cached.tickCount == 0) {
                    recordCache.remove(cached.fileName);
                    synchronized (sortedRecords) { sortedRecords.remove(cached); }
                    for (BansMenu menu : openMenus) menu.buildAndApply(true);
                }
            } catch (IOException e) {
                cached.loaded = true;
            }
        });
    }

    private void loadTickCountAndRefresh(CachedRecord cached) {
        SchedulerManager.getAdapter().runAsync(() -> {
            try {
                List<TickData> ticks = plugin.getBansManager().loadAndClose(plugin, null, cached.fileName);
                cached.tickCount = ticks != null ? ticks.size() : 0;
                cached.loaded    = true;
                if (cached.tickCount == 0) {
                    recordCache.remove(cached.fileName);
                    synchronized (sortedRecords) { sortedRecords.remove(cached); }
                }
            } catch (IOException e) {
                cached.loaded = true;
            }
            buildAndApply(false);
        });
    }

    private void checkNewRecords() {
        SchedulerManager.getAdapter().runAsync(() -> {
            plugin.getBansManager().config.bdbConfig.load();
            Set<BDRecord> allRecords = plugin.getBansManager().config.bdbConfig.records;
            boolean hasNew = false;
            for (BDRecord record : allRecords) {
                String key = record.record();
                if (!recordCache.containsKey(key)) {
                    String ownerName = getPlayerName(record.uuid());
                    OfflinePlayer player = Bukkit.getOfflinePlayer(ownerName);
                    Entry ban = Main.instance.getBan(player);
                    CachedRecord cached = new CachedRecord(record, ownerName, player.getUniqueId(), ban != null, ban);
                    recordCache.put(key, cached);
                    loadTickCount(cached);
                    synchronized (sortedRecords) { sortedRecords.add(0, cached); }
                    hasNew = true;
                }
            }
            if (hasNew) buildAndApply(false);
        });
    }

    private String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    private void buildAndApply(boolean full) {
        SchedulerManager.getAdapter().runAsync(() -> {
            List<CachedRecord> records;
            synchronized (sortedRecords) { records = new ArrayList<>(sortedRecords); }

            final int totalPages = (int) Math.ceil((double) records.size() / ITEMS_PER_PAGE);
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;
            if (page < 0) page = 0;

            int start = page * ITEMS_PER_PAGE;
            int end   = Math.min(start + ITEMS_PER_PAGE, records.size());
            List<CachedRecord> pageData = records.subList(start, end);

            ItemStack[] newContents = new ItemStack[54];

            for (int i = 0; i < pageData.size(); i++) {
                newContents[i] = createRecordHead(pageData.get(i), start + i);
            }

            if (page > 0) {
                newContents[45] = createButton(menuConfig.prevMat,
                        menuConfig.prevName.replace("{PAGE}", String.valueOf(page)));
            }

            newContents[49] = createButton(menuConfig.infoMat,
                    menuConfig.infoName
                            .replace("{CURRENT}", String.valueOf(page + 1))
                            .replace("{TOTAL}",   String.valueOf(Math.max(1, totalPages))));

            if (end < records.size()) {
                newContents[53] = createButton(menuConfig.nextMat,
                        menuConfig.nextName.replace("{PAGE}", String.valueOf(page + 2)));
            }

            ItemStack filler = createButton(menuConfig.fillerMat, menuConfig.fillerName);
            for (int i = 45; i < 54; i++) {
                if (newContents[i] == null) newContents[i] = filler;
            }

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
    }

    private ItemStack createRecordHead(CachedRecord cached, int globalIndex) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        Player ownerPlayer = Bukkit.getPlayer(cached.bdRecord.uuid());
        meta.setOwningPlayer(ownerPlayer != null
                ? ownerPlayer
                : Bukkit.getOfflinePlayer(cached.bdRecord.uuid()));

        meta.setDisplayName(ColorUtil.colorize(applyPlaceholders(menuConfig.headName, cached, globalIndex, null)));

        String dateStr = DATE_FORMAT.format(new Date(cached.time));
        String tickStr = cached.loaded ? String.valueOf(cached.tickCount) : "&7Загрузка...";

        List<String> lore = menuConfig.headLore.stream()
                .map(line -> applyPlaceholders(line, cached, globalIndex, dateStr)
                        .replace("{TICKS}", tickStr))
                .map(ColorUtil::colorize)
                .collect(Collectors.toList());

        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private String applyPlaceholders(String template,
                                     CachedRecord cached,
                                     int globalIndex,
                                     String dateStr) {

        String banDuration = cached.ban != null
                ? cached.ban.getRemainingDurationString(System.currentTimeMillis())
                : "-";

        String banReason = cached.ban != null
                ? cached.ban.getReason()
                : "-";

        String banType = cached.ban != null
                ? cached.ban.getType()
                : "-";

        String banModer = (cached.ban != null && cached.ban.getExecutorName() != null)
                ? cached.ban.getExecutorName()
                : "-";

        String banId = cached.ban != null
                ? String.valueOf(cached.ban.getId())
                : "-";

        StringBuilder sb = new StringBuilder(template.length() + 128);

        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);

            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end != -1) {
                    String key = template.substring(i, end + 1);

                    switch (key) {

                        case "{PLAYER}":
                            sb.append(cached.ownerName);
                            break;

                        case "{BANNED}":
                            sb.append(cached.banned ? "&c" : "");
                            break;

                        case "{BAN_DURATION}":
                            sb.append(banDuration);
                            break;

                        case "{BAN_REASON}":
                            sb.append(banReason);
                            break;

                        case "{BAN_TYPE}":
                            sb.append(banType);
                            break;

                        case "{BAN_MODER}":
                            sb.append(banModer);
                            break;

                        case "{BAN_ID}":
                            sb.append(banId);
                            break;

                        case "{FILE}":
                            sb.append(cached.fileName);
                            break;

                        case "{DATE}":
                            sb.append(dateStr != null ? dateStr : "");
                            break;

                        case "{UUID}":
                            sb.append(cached.bdRecord.uuid());
                            break;

                        case "{INDEX}":
                            sb.append(globalIndex + 1);
                            break;

                        default:
                            sb.append(key);
                            break;
                    }

                    i = end;
                    continue;
                }
            }

            sb.append(c);
        }

        return sb.toString();
    }

    private ItemStack createButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean itemsEqual(ItemStack a, ItemStack b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        ItemMeta ma = a.getItemMeta(), mb = b.getItemMeta();
        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;
        return Objects.equals(ma.getDisplayName(), mb.getDisplayName())
                && Objects.equals(ma.getLore(), mb.getLore());
    }

    private CachedRecord getRecordBySlot(int slot) {
        int globalIndex = page * ITEMS_PER_PAGE + slot;
        if (globalIndex < 0 || globalIndex >= sortedRecords.size()) return null;
        return sortedRecords.get(globalIndex);
    }

    private void playRotation(Player player, CachedRecord cached, boolean crits) {
        SchedulerManager.getAdapter().runAsync(() -> {
            try {
                List<TickData> ticks = plugin.getBansManager().loadAndClose(plugin, null, cached.fileName);
                if (ticks == null || ticks.isEmpty()) {
                    SchedulerManager.getAdapter().runSync(() ->
                            player.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                                    .getPrefix() + "&cФайл не найден или пустой: " + cached.fileName)));
                    plugin.getBansManager().config.bdbConfig.remove(cached.fileName);
                    return;
                }

                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user == null) return;

                WalrusPlayer wp = WalrusPlayer.get(player.getUniqueId());
                if (wp == null) return;

                SchedulerManager.getAdapter().runSync(() -> {
                    player.closeInventory();
                    RotationSession session = new RotationSession(
                            cached.ownerName, cached.fileName, player.getUniqueId(),
                            ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE),
                            ticks, crits);
                    Location loc = new Location(wp.position.x, wp.position.y, wp.position.z, wp.yaw, wp.pitch);
                    session.load(user, loc);
                    player.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig().getPrefix() + plugin.getMessagesConfig()
                            .getMessage("bans-play-hint", "{FILE}", cached.fileName)));
                });
            } catch (IOException e) {
                SchedulerManager.getAdapter().runSync(() ->
                        player.sendMessage(ColorUtil.colorize("&cОшибка загрузки файла: " + e.getMessage())));
            }
        });
    }

    private void move(Player admin, CachedRecord cached, String folder, Label label) {
        SchedulerManager.getAdapter().runAsync(() -> {
            try {
                File bdataFolder = new File(plugin.getDataFolder(), "mls/bdata");

                File sourceFile = new File(bdataFolder, cached.fileName);

                if (!sourceFile.exists()) {
                    sourceFile = new File(
                            new File(plugin.getDataFolder(), "mls/data"),
                            cached.fileName
                    );
                }

                if (sourceFile.exists()) {
                    rewriteDatasetLabel(sourceFile, label);

                    File suspectFolder = new File(bdataFolder, folder);
                    if (!suspectFolder.exists()) {
                        suspectFolder.mkdirs();
                    }

                    File destFile = new File(suspectFolder, label.name().toUpperCase()+"_" +cached.fileName);

                    BansManager bansManager = Main.instance.getBansManager();
                    List<TickData> ticks = bansManager.loadFromFile(sourceFile, Label.CHEAT.getId());
                    if (!ticks.isEmpty()) {
                        List<BansManager.ComparedWindow> windows = bansManager.compare(ticks, folder);

                        List<TickData> medium = new ArrayList<>();
                        List<TickData> hard = new ArrayList<>();
                        Set<TickData> seenMedium = new HashSet<>();
                        Set<TickData> seenHard = new HashSet<>();

                        windows.forEach(w -> {
                            double score = w.score();
                            if (score > 0.95) {
                                w.liveTicks().stream().filter(seenHard::add).forEach(hard::add);
                            } else if (score > 0.75) {
                                w.liveTicks().stream().filter(seenMedium::add).forEach(medium::add);
                            }
                        });

                        if (!medium.isEmpty())
                            bansManager.saveAndClose(Main.instance, folder + "_medium", cached.ownerName, cached.ownerId, label, "MEDIUM_SUSPECT", medium, false, true);
                        if (!hard.isEmpty())
                            bansManager.saveAndClose(Main.instance, folder + "_hard", cached.ownerName, cached.ownerId, label, "HARD_SUSPECT", hard, false, true);
                    }

                    if (!sourceFile.renameTo(destFile)) {
                        plugin.getLogger().warning(
                                "[BansMenu] Failed to move " + cached.fileName
                        );
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[BansMenu] Error moving file: " + e.getMessage()
                );
            }

            recordCache.remove(cached.fileName);

            synchronized (sortedRecords) {
                sortedRecords.remove(cached);
            }

            plugin.getBansManager().config.bdbConfig.remove(cached.fileName);

            String msg = ColorUtil.colorize(plugin.getMessagesConfig().getPrefix() +
                    plugin.getMessagesConfig().getMessage(
                            "bans-ban-success",
                            "{PLAYER}", cached.ownerName,
                            "{FILE}", cached.fileName
                    )
            );

            SchedulerManager.getAdapter().runSync(() -> admin.sendMessage(msg));
            buildAndApply(true);
        });
    }

    private void rewriteDatasetLabel(File file, Label label) {
        try {
            List<String> lines = Files.readAllLines(file.toPath());

            if (lines.isEmpty()) {
                return;
            }

            List<String> rewritten = new ArrayList<>();
            rewritten.add(lines.get(0));

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);

                if (line.isEmpty()) {
                    continue;
                }
                String[] split = line.split(",");

                if (split.length < 9) {
                    continue;
                }

                split[0] = String.valueOf(label.getId());
                rewritten.add(String.join(",", split));
            }

            Files.write(file.toPath(), rewritten);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[BansMenu] Failed to rewrite dataset label: " + e.getMessage()
            );
        }
    }

    private void ban(Player admin, CachedRecord cached) {
        SchedulerManager.getAdapter().runSync(() -> {
            for (String cmd : menuConfig.banCommands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), applyCommandPlaceholders(cmd, admin, cached));
            }
        });
    }

    private void unban(Player admin, CachedRecord cached) {
        SchedulerManager.getAdapter().runSync(() -> {
            for (String cmd : menuConfig.unbanCommands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), applyCommandPlaceholders(cmd, admin, cached));
            }
        });
    }

    private String applyCommandPlaceholders(String cmd, Player admin, CachedRecord cached) {
        return cmd
                .replace("{PLAYER}", cached.ownerName)
                .replace("{MODER}",  admin.getName())
                .replace("{UUID}",   cached.bdRecord.uuid().toString())
                .replace("{FILE}",   cached.fileName);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player clicker)) return;

        int slot = event.getSlot();

        if (slot == 45 && page > 0) { page--; buildAndApply(true); return; }
        if (slot == 53)             { page++; buildAndApply(true); return; }
        if (slot >= 45) return;

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!(item.getItemMeta() instanceof SkullMeta)) return;

        CachedRecord cached = getRecordBySlot(slot);
        if (cached == null) return;

        if (event.isRightClick() && !event.isShiftClick()) {
            playRotation(clicker, cached, false);
        } else if (event.isShiftClick() && event.isRightClick()) {
            playRotation(clicker, cached, true);
        } else if (event.isLeftClick() && !event.isShiftClick()) {
            ban(clicker, cached);
            move(clicker, cached, "suspect", Label.CHEAT);
        } else if (event.isShiftClick() && event.isLeftClick()) {
            plugin.getBansManager().config.bdbConfig.delete(cached.fileName);
        } else if (event.getClick() == ClickType.DROP) {
            move(clicker, cached, "suspect", Label.CHEAT);
        } else if (event.getClick() == ClickType.CONTROL_DROP) {
            move(clicker, cached, "legit", Label.LEGIT);
        } else if (event.getClick() == ClickType.MIDDLE) {
            if (cached.banned) {
                unban(clicker, cached);
                plugin.getBansManager().config.bdbConfig.delete(cached.fileName);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory() != inventory) return;
        openMenus.remove(this);
        if (updateTask    != null) updateTask.cancel();
        if (banRefreshTask != null) banRefreshTask.cancel();
        HandlerList.unregisterAll(this);
    }
}