package wtf.walrus.bans;

import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.walrus.Main;
import wtf.walrus.Permissions;
import wtf.walrus.alert.AlertManager;
import wtf.walrus.bans.config.BDRecord;
import wtf.walrus.bans.config.BansConfig;
import wtf.walrus.config.Label;
import wtf.walrus.data.DataSession;
import wtf.walrus.data.TickData;
import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.util.ColorUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class BansManager {
    public final Main plugin;

    public List<String> datasets = new ArrayList<>();
    public BansConfig config;

    public BansManager(Main plugin) {
        this.plugin = plugin;
        this.config = new BansConfig(this);
        try {
            this.datasets = getCsvFiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> getCsvFiles() throws IOException {
        File baseData = new File(plugin.getDataFolder(), "mls/data");
        File baseBData = new File(plugin.getDataFolder(), "mls/bdata");

        Set<String> files = new HashSet<>();

        if (baseData.exists()) {
            Files.list(baseData.toPath())
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".csv"))
                    .forEach(files::add);
        }

        if (baseBData.exists()) {
            Files.list(baseBData.toPath())
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".csv"))
                    .forEach(files::add);
        }

        return new ArrayList<>(files);
    }

    public void reloadDataset() {
        try {
            this.datasets = getCsvFiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public record ComparedWindow(String file, int liveIdx, int logIdx, double score, List<TickData> liveTicks, List<TickData> logTicks) {}

    public List<ComparedWindow> compare(List<TickData> ticks, List<TickData> dataset) {
        if (ticks.size() < 40 || dataset.size() < 40) return Collections.emptyList();

        List<List<TickData>> liveWindows = new ArrayList<>();
        for (int i = 0; i + 40 <= ticks.size(); i += 40) {
            liveWindows.add(ticks.subList(i, i + 40));
        }

        List<List<TickData>> loggedWindows = new ArrayList<>();
        for (int i = 0; i + 40 <= dataset.size(); i += 40) {
            loggedWindows.add(dataset.subList(i, i + 40));
        }

        List<ComparedWindow> allMatches = new ArrayList<>();

        for (int li = 0; li < liveWindows.size(); li++) {
            for (int gi = 0; gi < loggedWindows.size(); gi++) {
                double s = plugin.getLocalAIClientProvider().getModel().compare(liveWindows.get(li), loggedWindows.get(gi)).prob();
                allMatches.add(new ComparedWindow(null, li, gi, s, liveWindows.get(li), loggedWindows.get(gi)));
            }
        }

        return allMatches.stream()
                .sorted(Comparator.comparingDouble(ComparedWindow::score).reversed())
                .limit(50)
                .collect(Collectors.toList());
    }

    public List<ComparedWindow> compare(List<TickData> ticks, String folder) {
        reloadDataset();

        File bdataFolder = new File(plugin.getDataFolder(), "mls/bdata");
        if (folder != null && !folder.isEmpty()) bdataFolder = new File(bdataFolder, folder);
        if (!bdataFolder.exists()) return Collections.emptyList();

        List<File> bdataFiles;
        try {
            bdataFiles = Files.list(bdataFolder.toPath())
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().endsWith(".csv"))
                    .filter(f -> !f.getName().startsWith("LEGIT"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }

        if (bdataFiles.isEmpty() || ticks.size() < 40) return Collections.emptyList();

        List<List<TickData>> liveWindows = new ArrayList<>();
        for (int i = 0; i + 40 <= ticks.size(); i += 40) {
            liveWindows.add(ticks.subList(i, i + 40));
        }

        List<ComparedWindow> allMatches = new ArrayList<>();

        for (File file : bdataFiles) {
            List<TickData> logged;
            try {
                logged = loadFromFile(file, 1);
            } catch (IOException e) {
                continue;
            }
            if (logged == null || logged.size() < 40) continue;

            List<List<TickData>> loggedWindows = new ArrayList<>();
            for (int i = 0; i + 40 <= logged.size(); i += 40) {
                loggedWindows.add(logged.subList(i, i + 40));
            }

            for (int li = 0; li < liveWindows.size(); li++) {
                for (int gi = 0; gi < loggedWindows.size(); gi++) {
                    double s = plugin.getLocalAIClientProvider().getModel().compare(liveWindows.get(li), loggedWindows.get(gi)).prob();
                    allMatches.add(new ComparedWindow(file.getName(), li, gi, s, liveWindows.get(li), loggedWindows.get(gi)));
                }
            }
        }

        return allMatches.stream()
                .sorted(Comparator.comparingDouble(ComparedWindow::score).reversed())
                .limit(50)
                .collect(Collectors.toList());
    }

    public List<TickData> loadFromFile(File file, int labelId) throws IOException {
        List<TickData> ticks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] v = line.split(",");
                if (v.length < 9) continue;
                if (!v[0].equalsIgnoreCase(String.valueOf(labelId))) continue;
                ticks.add(new TickData(
                        Float.parseFloat(v[1]), Float.parseFloat(v[2]),
                        Float.parseFloat(v[3]), Float.parseFloat(v[4]),
                        Float.parseFloat(v[5]), Float.parseFloat(v[6]),
                        Float.parseFloat(v[7]), Float.parseFloat(v[8])
                ));
            }
        }
        return ticks;
    }

    public void saveAndClose(Main plugin, String sessionFolder, User user, Label label, String comment, List<TickData> ticks, boolean base) throws IOException {
        this.saveAndClose(plugin, sessionFolder, user.getName(), user.getUUID(), label, comment, ticks, base, false);
    }

    public void saveAndClose(Main plugin, String sessionFolder, String username, UUID uuid, Label label, String comment, List<TickData> ticks, boolean base, boolean nolimit) throws IOException {
        String csvContent = DataSession.generateCsvContent(label, ticks);
        if (csvContent.isEmpty()) {
            plugin.getLogger().info("[DataSession] No ticks recorded for " + username + ", skipping save.");
            return;
        }

        File dataFolder = new File(plugin.getDataFolder(), "mls/bdata");
        if (sessionFolder != null && !sessionFolder.isEmpty()) {
            dataFolder = new File(dataFolder, sessionFolder);
        }
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File outputFile = new File(dataFolder, DataSession.generateFileName(System.currentTimeMillis(), Label.CHEAT, "BAN_LOG" + (!comment.isEmpty() ? "_" + comment : ""), username));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(csvContent);
        }
        long saveTime = System.currentTimeMillis();
        if (base) {
            config.bdbConfig.set(uuid, outputFile.getName(), saveTime, nolimit);
            String ownerName = username;
            BDRecord newRecord = new BDRecord(uuid, saveTime, outputFile.getName(), nolimit);
            wtf.walrus.bans.menu.BansMenu.onRecordAdded(newRecord, ownerName);
            sendAlert(username, label, comment, outputFile.getName());
        } if (!nolimit) {
            config.bdbConfig.set(outputFile.getName(), saveTime, nolimit);
        }
    }

    public void sendAlert(String player, Label label, String comment, String file) {
        for (Player bp : Bukkit.getOnlinePlayers()) {
            if (((!bp.hasPermission(Permissions.ALERTS) || !bp.hasPermission(Permissions.BANS_MENU))
                    && !bp.hasPermission(Permissions.ADMIN)) || !Main.instance.getAlertManager().hasEnabledAlerts(bp.getUniqueId())) continue;
            bp.sendMessage(getPrefix() + placeholders(msg("bans-alert"), player, label, comment, file));
        }
    }

    public String placeholders(String input, String player, Label label, String comment, String file) {
        return input.replace("{PLAYER}", player)
                .replace("{LABEL}", label.name())
                .replace("{FILE}", file)
                .replace("{COMMENT}", comment);
    }

    public void replaceLabel(String fileName, Label newLabel) {
        File file = new File(plugin.getDataFolder(), "mls/bdata/" + fileName);

        if (!file.exists()) return;

        try {
            List<String> lines = Files.readAllLines(file.toPath());

            if (lines.isEmpty()) return;

            List<String> rewritten = new ArrayList<>();
            rewritten.add(lines.get(0));

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);

                if (line.isEmpty()) continue;
                String[] split = line.split(",");
                split[0] = String.valueOf(newLabel.getId());
                rewritten.add(String.join(",", split));
            }

            Files.write(file.toPath(), rewritten);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getPrefix() {
        return ColorUtil.colorize(plugin.getMessagesConfig().getPrefix());
    }

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key));
    }

    private String msg(String key, String... replacements) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key, replacements));
    }

    public void reload() {
        reloadDataset();
        this.config = new BansConfig(this);
    }

    public List<TickData> loadAndClose(Main plugin, String sessionFolder, String fileName) throws IOException {
        File dataFolder = new File(plugin.getDataFolder(), "mls/bdata");
        if (sessionFolder != null && !sessionFolder.isEmpty()) {
            dataFolder = new File(dataFolder, sessionFolder);
        }

        File inputFile = new File(dataFolder, fileName);
        if (!inputFile.exists()) {
            File fallbackFolder = new File(plugin.getDataFolder(), "mls/data");
            if (sessionFolder != null && !sessionFolder.isEmpty()) {
                fallbackFolder = new File(fallbackFolder, sessionFolder);
            }

            File fallbackFile = new File(fallbackFolder, fileName);
            if (!fallbackFile.exists()) {
                plugin.getLogger().warning("[BanSystem] File not found: " + inputFile.getAbsolutePath());
                plugin.getLogger().warning("[BanSystem] Also not found in fallback: " + fallbackFile.getAbsolutePath());
                return null;
            }
            inputFile = fallbackFile;
        }

        List<TickData> ticks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] values = line.split(",");
                ticks.add(new TickData(
                        Float.parseFloat(values[1]), Float.parseFloat(values[2]),
                        Float.parseFloat(values[3]), Float.parseFloat(values[4]),
                        Float.parseFloat(values[5]), Float.parseFloat(values[6]),
                        Float.parseFloat(values[7]), Float.parseFloat(values[8])
                ));
            }
        }

        return ticks;
    }
}