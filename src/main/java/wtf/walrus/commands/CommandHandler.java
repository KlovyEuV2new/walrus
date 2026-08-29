/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.commands;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import wtf.walrus.Main;
import wtf.walrus.Permissions;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.Config;
import wtf.walrus.config.Label;
import wtf.walrus.data.*;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.ml.client.LocalAIClient;
import wtf.walrus.ml.client.LocalAIClientProvider;
import wtf.walrus.npc.NPC;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.rotationloader.RotationSession;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.server.AIClientProvider;
import wtf.walrus.server.FlatBufferSerializer;
import wtf.walrus.server.IAIClient;
import wtf.walrus.session.ISessionManager;
import wtf.walrus.util.ColorUtil;
import wtf.walrus.util.DatasetUploader;
import wtf.walrus.violation.ViolationManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
            case "play":         return handlePlayRot(sender, args);
            case "stopplay":     return handleStopPlayRot(sender, args);
            case "target":       return handleSettt(sender, args);
            case "testbot":      return handleTestBot(sender);
            case "removebot":    return handleOffBot(sender);
            case "bans":         return handleBans(sender);
            case "save":         return handleSaveRot(sender, args);
            case "reloadset":    {
                if (!sender.hasPermission(Permissions.PLAY_ROTATION) && !sender.hasPermission(Permissions.ADMIN)) {
                    sender.sendMessage(getPrefix() + msg("no-permission"));
                    return true;
                }

                Main.instance.getBansManager().reloadDataset();
                return true;
            }
            default:
                sender.sendMessage(getPrefix() + msg("unknown-command", "{ARGS}", args[0]));
                sendUsage(sender);
                return true;
        }
    }

    private boolean handleBans(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getPrefix() + msg("players-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(Permissions.ADMIN) && !player.hasPermission(Permissions.BANS_MENU)) {
            player.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        new wtf.walrus.bans.menu.BansMenu(plugin, player).open();
        return true;
    }

    private boolean handlePlayRot(CommandSender sender, String[] args) {
        boolean crits = Arrays.stream(args)
                .anyMatch(arg -> arg.equalsIgnoreCase("-c")),
                follow = Arrays.stream(args)
                        .anyMatch(arg -> arg.equalsIgnoreCase("-f")),
                silent = Arrays.stream(args)
                        .anyMatch(arg -> arg.equalsIgnoreCase("-s"));

        String file = args[1];
        if (!sender.hasPermission(Permissions.PLAY_ROTATION) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (file == null || file.isEmpty()) {
            sendUsage(sender);
            return true;
        }

        User user = null;
        try {
            if (!(sender instanceof Player bukkitPlayer)) {
                sender.sendMessage(ColorUtil.colorize("&cOnly players can use this command."));
                return true;
            }

            user = PacketEvents.getAPI()
                    .getPlayerManager()
                    .getUser(bukkitPlayer);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (user == null) return false;

        WalrusPlayer player = WalrusPlayer.get(user.getUUID());
        if (player == null) return false;

        if (file.equals("*")) {
            playAllRotations(sender, user, player, crits, follow, silent);
            return true;
        }

        List<TickData> ticks = new ArrayList<>();
        try {
            ticks = Main.instance.getBansManager().loadAndClose(Main.instance, null, file);
        } catch (IOException ignored) {}

        if (ticks != null && !ticks.isEmpty()) {
            RotationSession session = new RotationSession(user.getName(), file, user.getUUID(), ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), ticks, crits, follow, silent);
            Location location = new Location(player.position.x, player.position.y, player.position.z, player.yaw, player.pitch);
            session.load(user, location);
            return true;
        }
        return true;
    }

    private void playAllRotations(CommandSender sender, User user, WalrusPlayer player, boolean crits, boolean follow, boolean silent) {
        File rotationFolder = new File(Main.instance.getDataFolder(), "mls/data");
        if (!rotationFolder.exists() || !rotationFolder.isDirectory()) {
            sender.sendMessage(ColorUtil.colorize("&cRotations folder not found!"));
            return;
        }

        File[] files = rotationFolder.listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            sender.sendMessage(ColorUtil.colorize("&cNo rotation files found!"));
            return;
        }

        sender.sendMessage(ColorUtil.colorize("&aFound &e" + files.length + " &arotation files."));

        if (silent) {
            playAllRotationsSilent(sender, user, player, files, crits, follow);
        } else {
            playAllRotationsNormal(sender, user, player, files, 0, crits, follow);
        }
    }

    private void playAllRotationsNormal(CommandSender sender, User user, WalrusPlayer player, File[] files, int index, boolean crits, boolean follow) {
        if (index >= files.length) {
            sender.sendMessage(ColorUtil.colorize("&aAll rotations completed!"));
            return;
        }

        String fileName = files[index].getName();
        sender.sendMessage(ColorUtil.colorize("&ePlaying rotation &6" + (index + 1) + "/" + files.length + "&e: &f" + fileName));

        List<TickData> ticks = new ArrayList<>();
        try {
            ticks = Main.instance.getBansManager().loadAndClose(Main.instance, null, fileName);
        } catch (IOException ignored) {}

        if (ticks != null && !ticks.isEmpty()) {
            RotationSession session = new RotationSession(user.getName(), fileName, user.getUUID(), ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), ticks, crits, follow, false);
            Location location = new Location(player.position.x, player.position.y, player.position.z, player.yaw, player.pitch);
            session.load(user, location);

            new BukkitRunnable() {
                @Override
                public void run() {
                    session.stop(user);
                    if (index + 1 < files.length) {
                        sender.sendMessage(ColorUtil.colorize("&eWaiting 5 seconds before next rotation..."));
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                playAllRotationsNormal(sender, user, player, files, index + 1, crits, follow);
                                this.cancel();
                            }
                        }.runTaskLater(Main.instance, 20L * 5);
                    } else {
                        playAllRotationsNormal(sender, user, player, files, index + 1, crits, follow);
                    }
                    this.cancel();
                }
            }.runTaskLater(Main.instance, ticks.size() + 20L * 5);
        } else {
            sender.sendMessage(ColorUtil.colorize("&cFailed to load &f" + fileName));
            playAllRotationsNormal(sender, user, player, files, index + 1, crits, follow);
        }
    }

    private void playAllRotationsSilent(CommandSender sender, User user, WalrusPlayer player, File[] files, boolean crits, boolean follow) {
        sender.sendMessage(ColorUtil.colorize("&eSilent mode: processing all files at once..."));

        SchedulerManager.getAdapter().runAsync(() -> {
            List<FileResult> results = new ArrayList<>();

            for (File file : files) {
                String fileName = file.getName();
                List<TickData> ticks = new ArrayList<>();
                try {
                    ticks = Main.instance.getBansManager().loadAndClose(Main.instance, null, fileName);
                } catch (IOException ignored) {}

                if (ticks == null || ticks.isEmpty()) {
                    SchedulerManager.getAdapter().runSync(() ->
                            sender.sendMessage(ColorUtil.colorize("&cFailed to load &f" + fileName))
                    );
                    continue;
                }

                final List<TickData> finalTicks = ticks;
                SchedulerManager.getAdapter().runSync(() ->
                        sender.sendMessage(ColorUtil.colorize("&7Processing &f" + fileName + " &7(" + finalTicks.size() + " ticks)"))
                );

                double totalProb = 0;
                double cheatSum = 0.0, legitSum = 0.0;
                int predictionsCount = 0;
                int cheatCount = 0, legitCount = 0;
                int tp = 0, tn = 0, fp = 0, fn = 0;
                double threshold = 0.5;

                int batchSize = 40;
                for (int i = 0; i < ticks.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, ticks.size());
                    List<TickData> batch = new ArrayList<>(ticks.subList(i, end));

                    try {
                        AIClientProvider clientProvider = Main.instance.getAiClientProvider();
                        IAIClient client = clientProvider.get();
                        if (client == null) continue;

                        if (client instanceof LocalAIClient) {
                            var model = Main.instance.getLocalAIClientProvider().getModel();
                            if (model == null) continue;

                            var out = model.predict(batch);
                            if (out == null) continue;

                            double truth = getLabelFromFileName(fileName);
                            double predProb = out.prob();
                            totalProb += predProb;
                            predictionsCount++;

                            if (truth == 1.0) {
                                cheatSum += predProb;
                                cheatCount++;
                            } else {
                                legitSum += predProb;
                                legitCount++;
                            }

                            double predClass = predProb >= threshold ? 1.0 : 0.0;
                            if (predClass == 1 && truth == 1) tp++;
                            else if (predClass == 0 && truth == 0) tn++;
                            else if (predClass == 1 && truth == 0) fp++;
                            else fn++;

                        } else {
                            Set<String> models = Main.instance.getPluginConfig().getModelNames().keySet()
                                    .stream()
                                    .filter(modelId -> Main.instance.getPluginConfig().isDisabledModel(modelId) || Main.instance.getPluginConfig().isOnlyAlertForModel(modelId))
                                    .collect(Collectors.toSet());

                            if (models.isEmpty()) continue;
                            byte[] serialized = FlatBufferSerializer.serialize(batch);

                            final double[] prob = {0};
                            final boolean[] done = {false};

                            client.predict(serialized, user.getUUID().toString(), user.getName(), models.stream().toList(), Main.instance.getPluginConfig().getModelsOnlyAlert())
                                    .subscribe(
                                            response -> {
                                                synchronized (results) {
                                                    if (response != null && response.getProbability() >= 0) {
                                                        prob[0] = response.getProbability();
                                                    }
                                                    done[0] = true;
                                                }
                                            },
                                            error -> {
                                                synchronized (results) {
                                                    done[0] = true;
                                                    Main.instance.getLogger().warning("[RotationSession] Error: " + error.getMessage());
                                                }
                                            }
                                    );

                            long startTime = System.currentTimeMillis();
                            while (!done[0] && System.currentTimeMillis() - startTime < 5000) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ignored) {}
                            }

                            if (done[0]) {
                                double truth = getLabelFromFileName(fileName);
                                double predProb = prob[0];
                                totalProb += predProb;
                                predictionsCount++;

                                if (truth == 1.0) {
                                    cheatSum += predProb;
                                    cheatCount++;
                                } else {
                                    legitSum += predProb;
                                    legitCount++;
                                }

                                double predClass = predProb >= threshold ? 1.0 : 0.0;
                                if (predClass == 1 && truth == 1) tp++;
                                else if (predClass == 0 && truth == 0) tn++;
                                else if (predClass == 1 && truth == 0) fp++;
                                else fn++;
                            }
                        }
                    } catch (Exception e) {
                        Main.instance.getLogger().warning("[RotationSession] ML error: " + e.getMessage());
                    }
                }

                if (predictionsCount > 0) {
                    double avgProb = totalProb / predictionsCount;
                    double cheatAvg = cheatCount == 0 ? 0 : cheatSum / cheatCount;
                    double legitAvg = legitCount == 0 ? 0 : legitSum / legitCount;

                    results.add(new FileResult(fileName, avgProb, predictionsCount, ticks.size(),
                            cheatAvg, legitAvg, cheatCount, legitCount, tp, tn, fp, fn));

                    final String fileResult = String.format(
                            "  &7%s &8-> &bAvg: &f%.6f &8| &cCHEAT: &f%.6f &8| &aLEGIT: &f%.6f &8| &7Samples: &f%d",
                            fileName, avgProb, cheatAvg, legitAvg, predictionsCount);

                    SchedulerManager.getAdapter().runSync(() ->
                            sender.sendMessage(ColorUtil.colorize(fileResult))
                    );
                }
            }

            results.sort((a, b) -> Double.compare(b.avgProb, a.avgProb));

            double totalCheatAvg = 0, totalLegitAvg = 0;
            int totalCheatFiles = 0, totalLegitFiles = 0;
            int totalCheatSamples = 0, totalLegitSamples = 0;
            int totalFiles = results.size();

            for (FileResult result : results) {
                if (result.cheatCount > 0) {
                    totalCheatAvg += result.cheatAvg;
                    totalCheatFiles++;
                    totalCheatSamples += result.cheatCount;
                }
                if (result.legitCount > 0) {
                    totalLegitAvg += result.legitAvg;
                    totalLegitFiles++;
                    totalLegitSamples += result.legitCount;
                }
            }

            double overallCheatAvg = totalCheatFiles > 0 ? totalCheatAvg / totalCheatFiles : 0;
            double overallLegitAvg = totalLegitFiles > 0 ? totalLegitAvg / totalLegitFiles : 0;

            int finalTotalCheatFiles = totalCheatFiles;
            int finalTotalLegitFiles = totalLegitFiles;
            int finalTotalLegitSamples = totalLegitSamples;
            int finalTotalCheatSamples = totalCheatSamples;
            SchedulerManager.getAdapter().runSync(() -> {
                sender.sendMessage(ColorUtil.colorize("&6&l=== SILENT ROTATION RESULTS ==="));
                sender.sendMessage(ColorUtil.colorize("&7Total files: &f" + results.size()));
                sender.sendMessage(ColorUtil.colorize("&7Sorted by average probability (highest first):"));
                sender.sendMessage(ColorUtil.colorize("&7" + "─".repeat(70)));

                int rank = 1;
                for (FileResult result : results) {
                    String color = result.avgProb > 0.8 ? "&c" : result.avgProb > 0.6 ? "&e" : "&a";
                    String cheatColor = result.cheatAvg > 0.7 ? "&c" : "&e";
                    String legitColor = result.legitAvg < 0.3 ? "&a" : "&e";

                    sender.sendMessage(ColorUtil.colorize(
                            "&6#" + rank + " &f" + result.fileName +
                                    " &8- &bAvg: " + color + String.format("%.6f", result.avgProb) +
                                    " &8| &cCHEAT: " + cheatColor + String.format("%.6f", result.cheatAvg) +
                                    " &8| &aLEGIT: " + legitColor + String.format("%.6f", result.legitAvg) +
                                    " &8| Samples: &f" + result.predictions +
                                    " &8| Ticks: &f" + result.totalTicks
                    ));

                    if (rank <= 5) {
                        double accuracy = result.tp + result.tn > 0 ?
                                (double)(result.tp + result.tn) / (result.tp + result.tn + result.fp + result.fn) : 0;
                        double precision = result.tp + result.fp > 0 ?
                                (double)result.tp / (result.tp + result.fp) : 0;
                        double recall = result.tp + result.fn > 0 ?
                                (double)result.tp / (result.tp + result.fn) : 0;
                        double f1 = precision + recall > 0 ?
                                2 * (precision * recall) / (precision + recall) : 0;

                        sender.sendMessage(ColorUtil.colorize(
                                "    &7TP: &a" + result.tp + " &7TN: &a" + result.tn +
                                        " &7FP: &c" + result.fp + " &7FN: &c" + result.fn +
                                        " &8| Acc: &f" + String.format("%.3f", accuracy) +
                                        " &8| F1: &f" + String.format("%.3f", f1)
                        ));
                    }
                    rank++;
                }

                sender.sendMessage(ColorUtil.colorize("&7" + "─".repeat(70)));

                sender.sendMessage(ColorUtil.colorize("&6&l=== OVERALL STATISTICS ==="));
                sender.sendMessage(ColorUtil.colorize("&7Total files analyzed: &f" + totalFiles));
                sender.sendMessage(ColorUtil.colorize("&7Average CHEAT probability: &c" + String.format("%.6f", overallCheatAvg) +
                        " &8(&f" + finalTotalCheatFiles + " &7files, &f" + finalTotalCheatSamples + " &7samples)"));
                sender.sendMessage(ColorUtil.colorize("&7Average LEGIT probability: &a" + String.format("%.6f", overallLegitAvg) +
                        " &8(&f" + finalTotalLegitFiles + " &7files, &f" + finalTotalLegitSamples + " &7samples)"));

                double separation = overallCheatAvg - overallLegitAvg;
                String sepColor = separation > 0.3 ? "&a" : separation > 0.15 ? "&e" : "&c";
                sender.sendMessage(ColorUtil.colorize("&7CHEAT-LEGIT separation: " + sepColor + String.format("%.6f", separation)));

                sender.sendMessage(ColorUtil.colorize("&7" + "─".repeat(70)));
            });

            Main.instance.getLogger().info("[MLSAC] Silent rotation results:");
            for (FileResult result : results) {
                Main.instance.getLogger().info(String.format(
                        "  %s: avg=%.6f cheat=%.6f legit=%.6f (samples=%d, ticks=%d)",
                        result.fileName, result.avgProb, result.cheatAvg, result.legitAvg,
                        result.predictions, result.totalTicks
                ));
            }

            Main.instance.getLogger().info(String.format(
                    "[MLSAC] Overall: avg=%.6f cheat=%.6f legit=%.6f",
                    overallCheatAvg, overallLegitAvg
            ));
        });
    }

    private double getLabelFromFileName(String fileName) {
        if (fileName.toUpperCase().startsWith("CHEAT")) return 1.0;
        if (fileName.toUpperCase().startsWith("LEGIT")) return 0.0;
        return 0.5;
    }

    private static class FileResult {
        String fileName;
        double avgProb;
        int predictions;
        int totalTicks;
        double cheatAvg;
        double legitAvg;
        int cheatCount;
        int legitCount;
        int tp, tn, fp, fn;

        FileResult(String fileName, double avgProb, int predictions, int totalTicks,
                   double cheatAvg, double legitAvg, int cheatCount, int legitCount,
                   int tp, int tn, int fp, int fn) {
            this.fileName = fileName;
            this.avgProb = avgProb;
            this.predictions = predictions;
            this.totalTicks = totalTicks;
            this.cheatAvg = cheatAvg;
            this.legitAvg = legitAvg;
            this.cheatCount = cheatCount;
            this.legitCount = legitCount;
            this.tp = tp;
            this.tn = tn;
            this.fp = fp;
            this.fn = fn;
        }
    }

    private boolean handleStopPlayRot(CommandSender sender, String[] args) {
        User user = null;
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ColorUtil.colorize("&cOnly players can use this command."));
                return true;
            }

            Player bukkitPlayer = (Player) sender;
            user = PacketEvents.getAPI()
                    .getPlayerManager()
                    .getUser(bukkitPlayer);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (user == null) return false;

        RotationSession.stopAll(user);
        sender.sendMessage(ColorUtil.colorize("&aAll rotation sessions stopped!"));
        return true;
    }

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

        int epochs = 50;
        if (args.length >= 2) {
            try {
                epochs = Integer.parseInt(args[1]);
                epochs = Math.max(1, Math.min(epochs, 1000));
            } catch (NumberFormatException e) {
                sender.sendMessage(getPrefix() + ColorUtil.colorize("&cInvalid epoch count, using 50"));
            }
        }

        int threads = 1;
        if (args.length >= 3) {
            try {
                threads = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getPrefix() + ColorUtil.colorize("&cInvalid threads count, using 1"));
            }
        }


        final int finalEpochs = epochs, finalThreads = threads;
        sender.sendMessage(getPrefix() + ColorUtil.colorize(
                "&eStarting local model training with &f" + finalEpochs + " &eepochs..."));
        sender.sendMessage(ColorUtil.colorize("&7Training data: " + localProvider.getDataDir().getAbsolutePath()));

        SchedulerManager.getAdapter().runAsync(() -> {
            String result = localProvider.trainAndSave(finalEpochs, finalThreads);
            SchedulerManager.getAdapter().runSync(() ->
                    sender.sendMessage(getPrefix() + ColorUtil.colorize("&a" + result))
            );
        });
        return true;
    }

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

    private boolean handleSaveRot(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /walrus save <player> <label> <source> <comment>");
            sender.sendMessage("§cSource: AIM or BLOCK");
            return false;
        }

        try {
            String playerName = args[1];
            String labelName = args[2];
            String sourceType = args[3].toUpperCase();
            String comment = args.length >= 5 ? args[4] : "PLUGIN_LOG";

            OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            UUID uuid = target.getUniqueId();

            List<TickData> ticks = null;

            if (sourceType.equals("AIM")) {
                AIPlayerData p = Main.instance.getAiCheck().getPlayerData(uuid);
                ticks = p.getTicksLog(false);
                if (ticks.isEmpty()) {
                    sender.sendMessage("§cNo AIM tick data found for " + playerName);
                    return false;
                }
                sender.sendMessage("§aLoaded " + ticks.size() + " ticks from AIM data");

            } else if (sourceType.equals("BLOCK")) {
                MiningPlayerData p = Main.instance.getMiningCheck().getPlayerData(uuid);
                ticks = p.getTicksLog(false);
                if (ticks.isEmpty()) {
                    sender.sendMessage("§cNo BLOCK tick data found for " + playerName);
                    return false;
                }
                sender.sendMessage("§aLoaded " + ticks.size() + " ticks from BLOCK data");

            } else {
                sender.sendMessage("§cInvalid source. Use: AIM or BLOCK");
                return false;
            }

            Label label;
            try {
                label = Label.valueOf(labelName.toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid label. Available: " + Arrays.toString(Label.values()));
                return false;
            }

            try {
                Main.instance.getBansManager().saveAndClose(
                        Main.instance,
                        null,
                        playerName,
                        uuid,
                        label,
                        comment,
                        ticks,
                        true,
                        false
                );

                sender.sendMessage("§aSuccessfully saved " + sourceType + " data for " + playerName + " as " + label);
                return true;
            } catch (IOException e) {
                sender.sendMessage("§cFailed to save data: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

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

    private boolean handleSettt(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN) && !sender.hasPermission(Permissions.PLAY_ROTATION)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        if (!(sender instanceof Player bukkitPlayer)) return false;
        WalrusPlayer wp = WalrusPlayer.get(bukkitPlayer.getUniqueId());
        if (wp == null) return false;
        if (args.length < 2 || args[1].isEmpty()) {
            wp.tt = new ArrayList<>();
            return true;
        }
        String file = args[1];
        List<TickData> ft = null;
        try {
            ft = plugin.getBansManager().loadAndClose(Main.instance, null, file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (ft == null) return false;
        wp.tt = ft;
        return true;
    }

    public boolean handleOffBot(CommandSender sender) {
        if (!sender.hasPermission(Permissions.COLLECT) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }

        if (!(sender instanceof Player bukkitPlayer)) {
            sender.sendMessage(ColorUtil.colorize("&cOnly players can use this command."));
            return true;
        }

        WalrusPlayer player = WalrusPlayer.get(bukkitPlayer.getUniqueId());
        if (player == null) return false;

        for (NPC npc : player.cn) {
            npc.despawn(player.user);
        }
        player.cn.clear();
        return true;
    }

    public boolean handleTestBot(CommandSender sender) {
        if (!sender.hasPermission(Permissions.COLLECT) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }

        if (!(sender instanceof Player bukkitPlayer)) {
            sender.sendMessage(ColorUtil.colorize("&cOnly players can use this command."));
            return true;
        }

        WalrusPlayer player = WalrusPlayer.get(bukkitPlayer.getUniqueId());
        if (player == null) return false;

        Location location = new Location(player.position.x, player.position.y, player.position.z, player.yaw, player.pitch);
        NPC npc = new NPC(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), player.uuid, player.user.getName(), location);
        npc.spawn(player.user);
        player.cn.add(npc);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(Permissions.RELOAD) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(getPrefix() + msg("no-permission"));
            return true;
        }
        plugin.reloadPluginConfig();
        Main.instance.getBansManager().reloadDataset();
        sender.sendMessage(getPrefix() + msg("config-reloaded"));
        return true;
    }

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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> commands = Arrays.asList(
                    "start", "stop", "trash", "datastatus", "alerts", "prob", "reload", "play", "stopplay", "reloadset", "bans", "save",
                    "kicklist", "suspects", "punish", "profile", "train", "localstatus", "upload", "target", "testbot", "removebot");
            completions.addAll(filterStartsWith(commands, args[0]));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (Arrays.asList("start", "stop", "trash", "prob", "punish", "profile").contains(sub)) {
                List<String> targets = new ArrayList<>(getOnlinePlayerNames());
                if (sub.equals("stop") || sub.equals("trash")) targets.add("all");
                completions.addAll(filterStartsWith(targets, args[1]));
            } else if (sub.equals("train")) {
                completions.addAll(filterStartsWith(Arrays.asList("10", "50", "100", "200"), args[1]));
            } else if (Arrays.asList("play", "target").contains(sub)) {
                completions = Main.instance.getBansManager().datasets.stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
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