/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import wtf.walrus.Main;
import wtf.walrus.Permissions;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.Config;
import wtf.walrus.config.Label;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.DataSession;
import wtf.walrus.data.DataType;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.ml.client.LocalAIClientProvider;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.session.ISessionManager;
import wtf.walrus.util.ColorUtil;
import wtf.walrus.util.DatasetUploader;
import wtf.walrus.violation.ViolationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final ISessionManager sessionManager;
    private final AlertManager alertManager;
    private final AICheck aiCheck;
    private final MiningCheck miningCheck;
    private final Main plugin;
    private final Map<UUID, UUID> probTracking = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> probTasks = new ConcurrentHashMap<>();

    public CommandHandler(ISessionManager sessionManager, AlertManager alertManager,
                          AICheck aiCheck, MiningCheck miningCheck, Main plugin) {
        this.sessionManager = sessionManager;
        this.alertManager = alertManager;
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
        this.plugin = plugin;
    }

    private Config getConfig() { return plugin.getPluginConfig(); }

    private String getPrefix() {
        return ColorUtil.colorize(plugin.getMessagesConfig().getPrefix());
    }

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key));
    }

    private String msg(String key, String... replacements) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key, replacements));
    }

    // ── Command router ────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start":        return handleStart(sender, args);
            case "stop":         return handleStop(sender, args);
            case "trash":        return handleTrash(sender, args);
            case "alerts":       return handleAlerts(sender);
            case "prob":         return handleProb(sender, args);
            case "reload":       return handleReload(sender);
            case "datastatus":   return handleDataStatus(sender);
            case "kicklist":     return handleKickList(sender);
            case "suspects":     return handleSuspects(sender);
            case "punish":       return handlePunish(sender, args);
            case "profile":      return handleProfile(sender, args);
            case "train":        return handleTrain(sender, args);
            case "localstatus":  return handleLocalStatus(sender);
            case "upload":       return handleUpload(sender);
            default:
                sender.sendMessage(getPrefix() + msg("unknown-command", "{ARGS}", args[0]));
                sendUsage(sender);
                return true;
        }
    }

    // ── /mlsac upload ─────────────────────────────────────────────────────────

    private boolean handleUpload(CommandSender sender) {
        if (!sender.hasPermission(Permissions.UPLOAD)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }

        LocalAIClientProvider localProvider = plugin.getLocalAIClientProvider();
        if (localProvider == null) {
            sender.sendMessage(getPrefix() + msg("upload-no-local"));
            return true;
        }

        File dataDir = localProvider.getDataDir();
        File[] csvFiles = dataDir.listFiles((d, n) -> n.endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            sender.sendMessage(getPrefix() + msg("upload-no-files",
                    "{PATH}", dataDir.getAbsolutePath()));
            return true;
        }

        sender.sendMessage(getPrefix() + msg("upload-started",
                "{COUNT}", String.valueOf(csvFiles.length)));

        SchedulerManager.getAdapter().runAsync(() -> {
            File zipFile = null;
            try {
                zipFile = DatasetUploader.zipDataDir(dataDir);
                final long sizeMb = zipFile.length() / 1024 / 1024;
                final String zipName = zipFile.getName();

                String downloadUrl = DatasetUploader.uploadToSite(zipFile);
                final String url = downloadUrl;

                SchedulerManager.getAdapter().runSync(() -> {
                    sender.sendMessage(getPrefix() + msg("upload-success",
                            "{FILE}", zipName,
                            "{SIZE}", String.valueOf(sizeMb),
                            "{URL}", url));
                    plugin.getLogger().info("[MLSAC Upload] Uploaded by "
                            + sender.getName() + " -> " + url);
                });

            } catch (Exception e) {
                final String err = e.getMessage();
                SchedulerManager.getAdapter().runSync(() ->
                        sender.sendMessage(getPrefix() + msg("upload-failed",
                                "{ERROR}", err))
                );
                plugin.getLogger().warning("[MLSAC Upload] Upload failed: " + err);
            } finally {
                if (zipFile != null && zipFile.exists()) zipFile.delete();
            }
        });

        return true;
    }

    // ── /mlsac train [epochs] ─────────────────────────────────────────────────

    private boolean handleTrain(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }

        LocalAIClientProvider localProvider = plugin.getLocalAIClientProvider();
        if (localProvider == null) {
            sender.sendMessage(getPrefix() + ColorUtil.colorize(
                    "&cLocal mode is not enabled! Set &fdetection.local-mode: true &cin config.yml"));
            return true;
        }

        int epochs = 150;
        if (args.length >= 2) {
            try {
                epochs = Integer.parseInt(args[1]);
                epochs = Math.max(1, Math.min(epochs, 1000));
            } catch (NumberFormatException e) {
                sender.sendMessage(getPrefix() + ColorUtil.colorize("&cInvalid epoch count, using 150"));
            }
        }

        final int finalEpochs = epochs;
        sender.sendMessage(getPrefix() + ColorUtil.colorize(
                "&eStarting local model training with &f" + finalEpochs + " &eepochs..."));
        sender.sendMessage(ColorUtil.colorize("&7Training data: " + localProvider.getDataDir().getAbsolutePath()));

        SchedulerManager.getAdapter().runAsync(() -> {
            String result = localProvider.trainAndSave(finalEpochs);
            SchedulerManager.getAdapter().runSync(() ->
                    sender.sendMessage(getPrefix() + ColorUtil.colorize("&a" + result))
            );
        });
        return true;
    }

    // ── /mlsac localstatus ────────────────────────────────────────────────────

    private boolean handleLocalStatus(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }

        LocalAIClientProvider localProvider = plugin.getLocalAIClientProvider();
        if (localProvider == null) {
            sender.sendMessage(getPrefix() + ColorUtil.colorize(
                    "&cLocal mode is not enabled. Set &fdetection.local-mode: true &cin config.yml"));
            return true;
        }

        File dataDir = localProvider.getDataDir();
        File[] csvFiles = dataDir.listFiles((d, n) -> n.endsWith(".csv"));
        int fileCount = csvFiles != null ? csvFiles.length : 0;
        long cheatFiles = csvFiles == null ? 0 :
                Arrays.stream(csvFiles).filter(f -> f.getName().startsWith("CHEAT")).count();
        long legitFiles = csvFiles == null ? 0 :
                Arrays.stream(csvFiles).filter(f -> f.getName().startsWith("LEGIT")).count();

        boolean clientReady = localProvider.getClient() != null
                && localProvider.getClient().isConnected();

        sender.sendMessage(ColorUtil.colorize("&6&l=== MLSAC Local ML Status ==="));
        sender.sendMessage(ColorUtil.colorize("&7Client status: &f" + (clientReady ? "&aReady" : "&cOffline")));

        for (wtf.walrus.ml.Model m : localProvider.getModels()) {
            File f = new File(localProvider.getMlsDir(), m.getName() + ".bin");
            sender.sendMessage(ColorUtil.colorize(
                    "&7Model &f" + m.getName() + ".bin&7: "
                            + (f.exists() ? "&aExists" : "&cNot found")
                            + " &8| trained: " + (m.isTrained() ? "&aYes" : "&cNo")
                            + " &8| threshold: &f" + String.format("%.2f", m.getOptimalThreshold())));
        }

        sender.sendMessage(ColorUtil.colorize("&7Training data: &f" + fileCount + " &7CSV files"));
        sender.sendMessage(ColorUtil.colorize("  &7CHEAT: &c" + cheatFiles + "  &7LEGIT: &a" + legitFiles));
        sender.sendMessage(ColorUtil.colorize("&7Data folder:   &f" + dataDir.getAbsolutePath()));
        sender.sendMessage(ColorUtil.colorize("&eCommands:"));
        sender.sendMessage(ColorUtil.colorize("  &f/walrus train [epochs]    &7- Train on collected data"));
        sender.sendMessage(ColorUtil.colorize("  &f/walrus start <p> CHEAT  &7- Record cheat data"));
        sender.sendMessage(ColorUtil.colorize("  &f/walrus start <p> LEGIT  &7- Record legit data"));
        return true;
    }

    // ── /mlsac suspects ───────────────────────────────────────────────────────

    private boolean handleSuspects(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getPrefix() + msg("players-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(Permissions.ALERTS) && !player.hasPermission(Permissions.ADMIN)) {
            player.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        new wtf.walrus.menu.SuspectsMenu(plugin, player).open();
        return true;
    }

    // ── /mlsac alerts ─────────────────────────────────────────────────────────

    private boolean handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getPrefix() + msg("players-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(Permissions.ALERTS) && !player.hasPermission(Permissions.ADMIN)) {
            player.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        alertManager.toggleAlerts(player);
        return true;
    }

    // ── /mlsac prob <player> ──────────────────────────────────────────────────

    private boolean handleProb(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getPrefix() + msg("players-only"));
            return true;
        }
        Player admin = (Player) sender;
        if (!admin.hasPermission(Permissions.PROB) && !admin.hasPermission(Permissions.ADMIN)) {
            admin.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (probTracking.containsKey(admin.getUniqueId())) {
            stopTracking(admin);
            admin.sendMessage(getPrefix() + msg("tracking-stopped"));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(getPrefix() + msg("prob-usage"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            admin.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return true;
        }
        startTracking(admin, target);
        admin.sendMessage(getPrefix() + msg("tracking-started", "{PLAYER}", target.getName()));
        return true;
    }

    private void startTracking(Player admin, Player target) {
        UUID adminId = admin.getUniqueId();
        UUID targetId = target.getUniqueId();
        stopTracking(admin);
        probTracking.put(adminId, targetId);
        ScheduledTask task = SchedulerManager.getAdapter().runSyncRepeating(() -> {
            Player adminPlayer = Bukkit.getPlayer(adminId);
            Player targetPlayer = Bukkit.getPlayer(targetId);
            if (adminPlayer == null || !adminPlayer.isOnline()) {
                stopTracking(adminId);
                return;
            }
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                adminPlayer.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        TextComponent.fromLegacyText(msg("player-offline"))
                );
                stopTracking(adminId);
                return;
            }
            AIPlayerData data = aiCheck.getOrCreatePlayerData(target);
            MiningPlayerData miningData = miningCheck.getOrCreatePlayerData(target);
            String message;
            if (data == null && miningData == null) {
                message = ColorUtil.colorize("&7" + targetPlayer.getName() + ": &eNo data");
            } else {
                double prob = 0.0;
                double mineProb = 0.0;
                if (data != null) {
                    prob = data.getLastProbability();
                }
                if (miningData != null) {
                    mineProb = miningData.getLastProbability();
                }
                String probC = NametagManager.getColorInfo(prob);
                String mineProbC = NametagManager.getColorInfo(prob);
                double buffer = 0.0;
                double mineBuffer = 0.0;
                if (data != null) buffer = data.getBuffer();
                if (miningData != null) mineBuffer = miningData.getBuffer();
                int vl = plugin.getViolationManager().getViolationLevel(targetId);
                message = ColorUtil.colorize(plugin.getMessagesConfig().getMessage(
                        "actionbar-format", targetPlayer.getName(), prob, probC, mineProb, mineProbC, buffer, mineBuffer, vl));
            }
            adminPlayer.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(message)
            );
        }, 0L, 10L);
        probTasks.put(adminId, task);
    }

    private void stopTracking(Player admin) { stopTracking(admin.getUniqueId()); }

    private void stopTracking(UUID adminId) {
        probTracking.remove(adminId);
        ScheduledTask task = probTasks.remove(adminId);
        if (task != null) task.cancel();
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    // ── /mlsac reload ─────────────────────────────────────────────────────────

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(Permissions.RELOAD) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(getPrefix() + msg("config-reloaded"));
        return true;
    }

    // ── /mlsac kicklist ───────────────────────────────────────────────────────

    private boolean handleKickList(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        List<ViolationManager.KickRecord> kicks = plugin.getViolationManager().getKickHistory();
        if (kicks.isEmpty()) {
            sender.sendMessage(getPrefix() + ColorUtil.colorize("&7No kicks from AI anti-cheat yet."));
            return true;
        }
        sender.sendMessage(getPrefix() + ColorUtil.colorize("&6Last kicks from AI anti-cheat:"));
        sender.sendMessage(ColorUtil.colorize("&7─────────────────────────────────"));
        int index = 1;
        for (ViolationManager.KickRecord kick : kicks) {
            sender.sendMessage(ColorUtil.colorize(String.format(
                    "&e%d. &f%s &7[&c%s&7] &8- &bProb: &f%.2f &8| &bBuf: &f%.1f &8| &bVL: &f%d",
                    index++,
                    kick.getPlayerName(),
                    kick.getFormattedTime(),
                    kick.getProbability(),
                    kick.getBuffer(),
                    kick.getVl())));
        }
        sender.sendMessage(ColorUtil.colorize("&7─────────────────────────────────"));
        return true;
    }

    // ── /mlsac punish <player> ────────────────────────────────────────────────

    private boolean handlePunish(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(getPrefix() + msg("usage-punish"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return true;
        }
        plugin.getViolationManager().executeMaxPunishment(target);
        if (plugin.getPluginConfig().getPunishmentCommands().isEmpty()) {
            sender.sendMessage(getPrefix() + msg("punish-no-action"));
        } else {
            sender.sendMessage(getPrefix() + msg("punish-success",
                    "{PLAYER}", target.getName(), "{ACTION}", "Max VL"));
        }
        return true;
    }

    // ── /mlsac profile <player> ───────────────────────────────────────────────

    private boolean handleProfile(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN) && !sender.hasPermission(Permissions.ALERTS)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(getPrefix() + msg("usage-profile"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return true;
        }

        AIPlayerData data = aiCheck.getPlayerData(target.getUniqueId());
        String sens = "N/A";
        int detections = 0;
        if (data != null) {
            int s = data.getAimProcessor().getSensitivity();
            if (s != -1) sens = String.valueOf(s);
            detections = data.getHighProbabilityDetections();
        }

        ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        String clientVer = version != null ? version.toString() : "Unknown";

        sender.sendMessage(ColorUtil.colorize(msg("profile-header", "{PLAYER}", target.getName())));
        List<String> info = plugin.getMessagesConfig().getMessageList("profile-info");
        if (info == null || info.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize("&7Sens: &f" + sens + "%"));
            sender.sendMessage(ColorUtil.colorize("&7Client: &f" + clientVer));
            sender.sendMessage(ColorUtil.colorize("&7Detections (>0.8): &f" + detections));
        } else {
            for (String line : info) {
                sender.sendMessage(ColorUtil.colorize(line
                        .replace("{SENS}", sens)
                        .replace("{CLIENT}", clientVer)
                        .replace("{DETECTIONS}", String.valueOf(detections))));
            }
        }
        return true;
    }

    // ── /mlsac datastatus ─────────────────────────────────────────────────────

    private boolean handleDataStatus(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        int activeSessions = sessionManager.getActiveSessionCount();
        sender.sendMessage(getPrefix() + msg("data-status-header"));
        sender.sendMessage(msg("active-sessions", "{COUNT}", String.valueOf(activeSessions)));
        if (activeSessions > 0) {
            sender.sendMessage(ColorUtil.colorize("&7Players collecting data:"));
            for (DataSession session : sessionManager.getActiveSessions()) {
                Player player = Bukkit.getPlayer(session.getUuid());
                String playerName = player != null ? player.getName() : session.getPlayerName();
                sender.sendMessage(ColorUtil.colorize("&b  " + playerName
                        + "&7 [&e" + session.getLabel().name() + "&7]"
                        + (session.getComment().isEmpty() ? "" : " \"" + session.getComment() + "\"")));
                sender.sendMessage(ColorUtil.colorize("&7    Ticks: &a" + session.getTickCount()
                        + "&7 | In combat: " + (session.isInCombat() ? "&aYes" : "&cNo")));
            }
        } else {
            sender.sendMessage(msg("no-active-sessions"));
            sender.sendMessage(msg("start-hint"));
        }
        return true;
    }

    // ── /mlsac start <player> <label> [comment] ───────────────────────────────

    private boolean handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN) && !sender.hasPermission(Permissions.COLLECT)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(getPrefix() + msg("usage-start"));
            return true;
        }
        Label sessionLabel = Label.fromString(args[2]);
        if (sessionLabel == null) {
            sender.sendMessage(getPrefix() + msg("invalid-label", "{LABEL}", args[2]));
            sender.sendMessage(getPrefix() + msg("valid-labels"));
            return true;
        }
        String type = args[3];
        DataType dataType = DataType.AIM;
        try {
            dataType =  DataType.valueOf(type.toUpperCase());
        } catch (Exception ignored) {}
        String comment = parseComment(args, 4);
        if (comment.isEmpty()) comment = dataType.name();
        return handleStartPlayer(sender, args[1], sessionLabel, comment, dataType);
    }

    private boolean handleStartPlayer(CommandSender sender, String playerName, Label label, String comment, DataType dataType) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", playerName));
            return true;
        }
        sessionManager.startSession(player, label, comment, dataType);
        sender.sendMessage(getPrefix() + msg("session-started", "{LABEL}", label.name(), "{COUNT}", "1"));
        return true;
    }

    // ── /mlsac stop <player|all> ──────────────────────────────────────────────

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN) && !sender.hasPermission(Permissions.COLLECT)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(getPrefix() + msg("usage-stop"));
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) return handleStopAll(sender);
        return handleStopPlayer(sender, args[1]);
    }

    private boolean handleTrash(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN) && !sender.hasPermission(Permissions.COLLECT)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(getPrefix() + msg("usage-trash"));
            return true;
        }
        if (args[1].equals("all")) return handleTrashAll(sender);
        return handleTrashPlayer(sender, args[1]);
    }

    public boolean handleTrashAll(CommandSender sender) {
        int count = sessionManager.getActiveSessionCount();
        sessionManager.trashAllSessions();
        sender.sendMessage(getPrefix() + msg("all-sessions-stopped", "{COUNT}", String.valueOf(count)));
        return true;
    }

    public boolean handleStopAll(CommandSender sender) {
        int count = sessionManager.getActiveSessionCount();
        sessionManager.stopAllSessions();
        sender.sendMessage(getPrefix() + msg("all-sessions-stopped", "{COUNT}", String.valueOf(count)));
        return true;
    }

    private boolean handleTrashPlayer(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            if (!sessionManager.hasActiveSession(player)) {
                sender.sendMessage(getPrefix() + msg("no-sessions-to-stop"));
                return true;
            }
            sessionManager.removeSession(player);
            sender.sendMessage(getPrefix() + msg("session-stopped", "{PLAYER}", player.getName()));
            return true;
        }
        // Try offline lookup
        for (DataSession session : sessionManager.getActiveSessions()) {
            if (session.getPlayerName().equalsIgnoreCase(playerName)) {
                sender.sendMessage(getPrefix() +
                        ColorUtil.colorize("&cOffline stopping not fully supported."));
                return true;
            }
        }
        sender.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", playerName));
        return true;
    }

    private boolean handleStopPlayer(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            if (!sessionManager.hasActiveSession(player)) {
                sender.sendMessage(getPrefix() + msg("no-sessions-to-stop"));
                return true;
            }
            sessionManager.stopSession(player);
            sender.sendMessage(getPrefix() + msg("session-stopped", "{PLAYER}", player.getName()));
            return true;
        }
        // Try offline lookup
        for (DataSession session : sessionManager.getActiveSessions()) {
            if (session.getPlayerName().equalsIgnoreCase(playerName)) {
                sender.sendMessage(getPrefix() +
                        ColorUtil.colorize("&cOffline stopping not fully supported."));
                return true;
            }
        }
        sender.sendMessage(getPrefix() + msg("player-not-found", "{PLAYER}", playerName));
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String parseComment(String[] args, int startIndex) {
        if (startIndex >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(args[i]);
        }
        String comment = sb.toString();
        if (comment.startsWith("\"") && comment.endsWith("\"") && comment.length() >= 2) {
            comment = comment.substring(1, comment.length() - 1);
        } else if (comment.startsWith("\"")) {
            comment = comment.substring(1);
        }
        return comment.trim();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(getPrefix() + msg("usage-header"));
        sender.sendMessage(msg("usage-start"));
        sender.sendMessage(msg("usage-stop"));
        sender.sendMessage(msg("usage-datastatus"));
        sender.sendMessage(msg("usage-alerts"));
        sender.sendMessage(msg("usage-prob"));
        sender.sendMessage(msg("usage-suspects"));
        sender.sendMessage(msg("usage-punish"));
        sender.sendMessage(msg("usage-profile"));
        sender.sendMessage(msg("usage-reload"));
        sender.sendMessage(ColorUtil.colorize("&7  /walrus kicklist           &8- Last 10 kicks from AI anti-cheat"));
        sender.sendMessage(ColorUtil.colorize("&7  /walrus train [epochs]    &8- Train local ML model"));
        sender.sendMessage(ColorUtil.colorize("&7  /walrus localstatus       &8- Show local ML status"));
        sender.sendMessage(ColorUtil.colorize("&7  /walrus upload             &8- Upload dataset to workupload.com"));
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> commands = Arrays.asList(
                    "start", "stop", "trash", "datastatus", "alerts", "prob", "reload",
                    "kicklist", "suspects", "punish", "profile", "train", "localstatus", "upload");
            completions.addAll(filterStartsWith(commands, args[0]));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (Arrays.asList("start", "stop", "trash", "prob", "punish", "profile").contains(sub)) {
                List<String> targets = new ArrayList<>(getOnlinePlayerNames());
                if (sub.equals("stop") || sub.equals("trash")) targets.add("all");
                completions.addAll(filterStartsWith(targets, args[1]));
            } else if (sub.equals("train")) {
                completions.addAll(filterStartsWith(Arrays.asList("10", "50", "100", "200"), args[1]));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            List<String> labels = Arrays.stream(Label.values())
                    .map(Label::name)
                    .collect(Collectors.toList());
            completions.addAll(filterStartsWith(labels, args[2]));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("start")) {
            List<String> labels = Arrays.stream(DataType.values())
                    .map(DataType::name)
                    .collect(Collectors.toList());
            completions.addAll(filterStartsWith(labels, args[3]));
        }
        return completions;
    }

    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        String lp = prefix.toLowerCase();
        return options.stream()
                .filter(o -> o.toLowerCase().startsWith(lp))
                .collect(Collectors.toList());
    }

    public void cleanup() {
        for (ScheduledTask task : probTasks.values()) task.cancel();
        probTasks.clear();
        probTracking.clear();
    }
}