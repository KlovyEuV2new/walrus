package wtf.walrus.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.walrus.Main;

import wtf.walrus.Permissions;
import wtf.walrus.checks.CheckType;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.HologramConfig;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.ml.managers.VerdictManager;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;
import wtf.walrus.util.FastMath;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class NametagManager extends PacketListenerAbstract implements Listener {

    private static final double LINE_GAP = 0.25;
    private static long PERM_CACHE_TTL = 2000L;

    private final JavaPlugin plugin;
    private final AICheck aiCheck;
    private final MiningCheck miningCheck;

    private final Map<UUID, int[]> armorStandIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSentText = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersMap = new ConcurrentHashMap<>();

    private final Map<UUID, long[]> permCache = new ConcurrentHashMap<>();

    private ScheduledTask task;
    private int cleanupCounter = 0;

    private String format;
    private double baseOffset;
    private boolean enabled;

    private static final LegacyComponentSerializer legacySerializer =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    public NametagManager(JavaPlugin plugin, AICheck aiCheck, MiningCheck miningCheck) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
    }

    public void start() {
        reload((Main) plugin);
        if (!enabled)
            return;

        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        task = SchedulerManager.getAdapter().runSyncRepeating(this::globalTick, 1L, 1L);
    }

    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (UUID targetId : new HashSet<>(armorStandIds.keySet())) {
            despawnForAll(targetId);
        }
        armorStandIds.clear();
        lastSentText.clear();
        viewersMap.clear();
        permCache.clear();
    }

    private static final String NL = "{NL}";

    private String[] splitFast(String input) {
        ArrayList<String> result = new ArrayList<>(4);

        int start = 0;
        int idx;

        while ((idx = input.indexOf(NL, start)) != -1) {
            result.add(input.substring(start, idx));
            start = idx + NL.length();
        }

        result.add(input.substring(start));

        return result.toArray(new String[0]);
    }

    private boolean hasViewPermission(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long[] cached = permCache.get(uuid);

        if (cached != null && now - cached[1] < PERM_CACHE_TTL) {
            return cached[0] == 1L;
        }

        boolean result = player.hasPermission(Permissions.ADMIN)
                || player.hasPermission(Permissions.ALERTS);

        permCache.put(uuid, new long[]{result ? 1L : 0L, now});
        return result;
    }

    public void invalidatePermCache(UUID uuid) {
        permCache.remove(uuid);
    }

    private double getVersionedOffset(Player viewer, double baseOffset) {
        ClientVersion clientVersion = PacketEvents.getAPI()
                .getPlayerManager().getClientVersion(viewer);
        int version = clientVersion != null ? clientVersion.getProtocolVersion() : 770;
        if (version < 755) {
            return baseOffset - 1.8;
        }
        return baseOffset;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            if (event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION &&
                    event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                return;

            Player player = event.getPlayer();
            if (player == null)
                return;

            int[] entityIds = armorStandIds.get(player.getUniqueId());
            if (entityIds == null)
                return;

            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            if (!flying.hasPositionChanged())
                return;

            Vector3d pos = flying.getLocation().getPosition();

            Set<UUID> viewers = viewersMap.get(player.getUniqueId());
            if (viewers == null)
                return;

            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !viewer.isOnline() || !hasViewPermission(viewer))
                    continue;

                HologramConfig hologramConfig = Main.instance.getHologramConfig();
                double versionedOffset = baseOffset;
                if (hologramConfig.isVersionedOffset()) versionedOffset = getVersionedOffset(viewer, baseOffset);
                for (int i = 0; i < entityIds.length; i++) {
                    double lineY = pos.getY() + versionedOffset + (entityIds.length - 1 - i) * LINE_GAP;
                    WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                            entityIds[i], new Vector3d(pos.getX(), lineY, pos.getZ()), 0f, 0f, false);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleport);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void globalTick() {
        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Player> admins = new ArrayList<>();

        for (Player p : allPlayers) {
            if (hasViewPermission(p) && (Main.instance.getAlertManager().hasEnabledAlerts(p.getUniqueId()) || !Main.instance.getHologramConfig().holoSyncAlerts())) {
                admins.add(p);
            }
        }

        if (admins.isEmpty()) {
            for (UUID targetId : new HashSet<>(armorStandIds.keySet())) {
                despawnForAll(targetId);
            }
            return;
        }

        for (Player target : allPlayers) {
            updateNametag(target, admins);
        }

        if (++cleanupCounter > 100) {
            cleanupCounter = 0;
            cleanupOfflineViewers();
        }
    }

    private void cleanupOfflineViewers() {
        armorStandIds.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        permCache.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);

        for (Map.Entry<UUID, Set<UUID>> entry : viewersMap.entrySet()) {
            entry.getValue().removeIf(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return true;
                if (!hasViewPermission(p)) {
                    int[] ids = armorStandIds.get(entry.getKey());
                    if (ids != null) {
                        PacketEvents.getAPI().getPlayerManager()
                                .sendPacket(p, new WrapperPlayServerDestroyEntities(ids));
                    }
                    return true;
                }
                return false;
            });
        }
    }

    public void updateNametag(Player target, List<Player> admins) {
        AIPlayerData data = aiCheck.getOrCreatePlayerData(target);
        MiningPlayerData miningData = miningCheck.getOrCreatePlayerData(target);
        if (data == null || miningData == null)
            return;

        double avgProb = data.getFormatedAverageProbability();
        double mineAvgProb = miningData.getFormatedAverageProbability();

        if (avgProb <= 0.0 && mineAvgProb <= 0.0) {
            despawnForAll(target.getUniqueId());
            return;
        }

        List<Double> history = data.getFormatedProbabilityHistory();
        List<Double> mineHistory = miningData.getFormatedProbabilityHistory();

        String historyStr = buildHistoryStr(history);
        String mineHistoryStr = buildHistoryStr(mineHistory);

        VerdictManager verdictManager = Main.instance.getVerdictManager();

        UUID uuid = target.getUniqueId();
        CheckType lastType = verdictManager.getLastVerdict(uuid);
        Object lastClass = verdictManager.getLastClass(uuid);

        boolean invalidLast = false;
        List<Double> lastHistory = new ArrayList<>();
        String lastHistStr = "-";
        double lastAvg = 0, lastFullAvg = 0;
        int lastPercent = 0;
        if (lastClass instanceof AICheck) {
            lastAvg = data.getFormatedAverageProbability();
            lastFullAvg = data.getAverageProbability();
            lastPercent = (int) (lastFullAvg * 100);
            lastHistory = data.getFormatedProbabilityHistory();
            lastHistStr = buildHistoryStr(history);
        } else if (lastClass instanceof MiningCheck) {
            lastAvg = miningData.getFormatedAverageProbability();
            lastFullAvg = miningData.getAverageProbability();
            lastPercent = (int) (lastFullAvg * 100);
            lastHistory = miningData.getFormatedProbabilityHistory();
            lastHistStr = buildHistoryStr(mineHistory);
        } else {
            invalidLast = true;
        }

        StringBuilder sb = new StringBuilder(format.length() + 128);

        int a = 0;

        while (a < format.length()) {
            char c = format.charAt(a);

            if (c == '{') {
                int end = format.indexOf('}', a);
                if (end != -1) {
                    String key = format.substring(a, end + 1);

                    switch (key) {

                        case "{LAST}":
                            sb.append(Main.instance.getCheckTypeManager().getName(lastType));
                            break;

                        case "{AVG}":
                            sb.append(FastMath.format(avgProb, 4));
                            break;

                        case "{MINE_AVG}":
                            sb.append(FastMath.format(mineAvgProb, 4));
                            break;

                        case "{MINE_AVG_COLORED}":
                            sb.append(getColorInfo(mineAvgProb));
                            break;

                        case "{MINE_HISTORY}":
                            sb.append(mineHistoryStr);
                            break;

                        case "{AVG_COLORED}":
                            sb.append(getColorInfo(avgProb));
                            break;

                        case "{HISTORY}":
                            sb.append(historyStr);
                            break;

                        case "{LAST_AVG}":
                            sb.append(FastMath.format(lastAvg, 4));
                            break;

                        case "{LAST_AVG_COLORED}":
                            sb.append(getColorInfo(lastAvg));
                            break;

                        case "{LAST_FULL_PERCENT}":
                            sb.append(lastPercent);
                            break;

                        case "{LAST_PERCENT_FULL_COLOR}":
                            sb.append(getInfoColor(lastFullAvg));
                            break;

                        case "{LAST_PERCENT}":
                            sb.append((int) (lastAvg * 100));
                            break;

                        case "{LAST_PERCENT_COLOR}":
                            sb.append(getInfoColor(lastAvg));
                            break;

                        case "{LAST_HISTORY}":
                            sb.append(lastHistStr);
                            break;

                        default:
                            sb.append(key);
                            break;
                    }

                    a = end + 1;
                    continue;
                }
            }

            sb.append(c);
            a++;
        }
        String filled = sb.toString();

        String[] lines = splitFast(filled);
        Location baseLoc = target.getLocation();

        int[] existingIds = armorStandIds.get(target.getUniqueId());
        if (existingIds != null && existingIds.length != lines.length) {
            despawnForAll(target.getUniqueId());
        }

        int[] entityIds = armorStandIds.computeIfAbsent(target.getUniqueId(), k -> {
            int[] ids = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                ids[i] = ThreadLocalRandom.current().nextInt(Integer.MIN_VALUE, -1000000);
            }
            return ids;
        });

        String textKey = String.join("\n", lines);
        String lastText = lastSentText.get(target.getUniqueId());
        boolean textChanged = !textKey.equals(lastText);

        for (Player viewer : admins) {
            if (viewer.getUniqueId().equals(target.getUniqueId()))
                continue;

            if (!viewer.getWorld().equals(target.getWorld()) ||
                    viewer.getLocation().distanceSquared(baseLoc) > 2304) {
                removeViewer(target.getUniqueId(), viewer);
                continue;
            }

            updateFor(target, viewer, entityIds, lines, baseLoc, baseOffset, textChanged);
        }

        Set<UUID> currentViewers = viewersMap.get(target.getUniqueId());
        if (currentViewers != null) {
            for (UUID viewerId : new HashSet<>(currentViewers)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) {
                    currentViewers.remove(viewerId);
                } else if (!hasViewPermission(viewer) || (!Main.instance.getAlertManager().hasEnabledAlerts(viewerId) && Main.instance.getHologramConfig().holoSyncAlerts())) {
                    removeViewer(target.getUniqueId(), viewer);
                }
            }
        }

        if (textChanged) {
            lastSentText.put(target.getUniqueId(), textKey);
        }
    }

    private String buildHistoryStr(List<Double> history) {
        if (history.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Double val : history) {
            sb.append(getColorInfo(val)).append(" ");
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public void updateFor(Player target, Player viewer, int[] entityIds, String[] lines,
                          Location baseLoc, double baseOffset, boolean textChanged) {
        Set<UUID> viewers = viewersMap.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean isNew = viewers.add(viewer.getUniqueId());

        HologramConfig hologramConfig = Main.instance.getHologramConfig();
        double versionedOffset = baseOffset;
        if (hologramConfig.isVersionedOffset()) versionedOffset = getVersionedOffset(viewer, baseOffset);

        for (int i = 0; i < entityIds.length; i++) {
            double lineY = baseLoc.getY() + versionedOffset + (entityIds.length - 1 - i) * LINE_GAP;
            Vector3d linePos = new Vector3d(baseLoc.getX(), lineY, baseLoc.getZ());

            if (isNew) {
                WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                        entityIds[i], Optional.of(UUID.randomUUID()), EntityTypes.ARMOR_STAND,
                        linePos, 0f, 0f, 0f, 0, Optional.empty());
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);

                WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                        entityIds[i], linePos, 0f, 0f, false);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleport);
            }

            if (isNew || textChanged) {
                List<EntityData<?>> metadata = getVersionedMetadata(lines[i]);
                WrapperPlayServerEntityMetadata metadataPacket =
                        new WrapperPlayServerEntityMetadata(entityIds[i], metadata);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        invalidatePermCache(event.getPlayer().getUniqueId());
        for (UUID targetId : new HashSet<>(viewersMap.keySet())) {
            removeViewer(targetId, event.getPlayer());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld() != event.getTo().getWorld())
            return;
        if (event.getFrom().distanceSquared(event.getTo()) > 2500) {
            for (UUID targetId : new HashSet<>(viewersMap.keySet())) {
                removeViewer(targetId, event.getPlayer());
            }
        }
    }

    public void handlePlayerQuit(Player player) {
        invalidatePermCache(player.getUniqueId());
        despawnForAll(player.getUniqueId());

        for (UUID targetId : new HashSet<>(viewersMap.keySet())) {
            removeViewer(targetId, player);
        }
    }

    private void removeViewer(UUID targetId, Player viewer) {
        Set<UUID> viewers = viewersMap.get(targetId);
        if (viewers != null && viewers.remove(viewer.getUniqueId())) {
            int[] entityIds = armorStandIds.get(targetId);
            if (entityIds != null) {
                WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityIds);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
            }
        }
    }

    private void despawnForAll(UUID targetId) {
        int[] ids = armorStandIds.remove(targetId);
        lastSentText.remove(targetId);
        Set<UUID> viewers = viewersMap.remove(targetId);

        if (ids == null || viewers == null)
            return;

        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(ids);
        for (UUID viewerId : viewers) {
            Player p = Bukkit.getPlayer(viewerId);
            if (p != null && p.isOnline())
                PacketEvents.getAPI().getPlayerManager().sendPacket(p, destroy);
        }
    }

    /**
     * PacketEvents serializes packets using the server protocol. Clients on another
     * protocol are handled later by ViaVersion, so metadata must not be selected
     * from the viewer's protocol version here.
     */
    private List<EntityData<?>> getVersionedMetadata(String text) {
        List<EntityData<?>> metadata = new ArrayList<>();
        int version = PacketEvents.getAPI().getServerManager()
                .getVersion().toClientVersion().getProtocolVersion();

        metadata.add(new EntityData<Byte>(
                0, EntityDataTypes.BYTE, (byte) 0x20));

        Component component = legacySerializer.deserialize(text);

        if (version >= 766) {
            metadata.add(
                    new EntityData<Optional<Component>>(
                            2,
                            EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.of(component)));
            metadata.add(new EntityData<Boolean>(
                    3, EntityDataTypes.BOOLEAN, true));

        } else if (version >= 393) {
            String json = AdventureSerializer.getGsonSerializer()
                    .serialize(component);
            metadata.add(new EntityData<Optional<String>>(
                    2, EntityDataTypes.OPTIONAL_COMPONENT,
                    Optional.of(json)));
            metadata.add(new EntityData<Boolean>(
                    3, EntityDataTypes.BOOLEAN, true));

        } else {
            String legacyStr = LegacyComponentSerializer.legacySection()
                    .serialize(component);
            metadata.add(new EntityData<String>(
                    2, EntityDataTypes.STRING, legacyStr));
            metadata.add(new EntityData<Boolean>(
                    3, EntityDataTypes.BOOLEAN, true));
        }

        int markerIndex = 15;
        if (version < 755) {
            if (version >= 448)
                markerIndex = 14;
            else if (version >= 385)
                markerIndex = 12;
            else if (version >= 107)
                markerIndex = 11;
            else
                markerIndex = 10;
        }
        metadata.add(new EntityData<Byte>(
                markerIndex, EntityDataTypes.BYTE,
                (byte) 0x10));

        return metadata;
    }

    public void reload(Main plugin) {
        FileConfiguration holoConfig = plugin.getHologramConfig().getConfig();
        PERM_CACHE_TTL = holoConfig.getInt("ttl-update", 40) * 50L;
        format = holoConfig.getString("nametags.format", "&6▶ &7AVG: &f{AVG} &8| {HISTORY} &6◀");;
        baseOffset = holoConfig.getDouble("nametags.height_offset", 2.3);
        enabled = holoConfig.getBoolean("nametags.enabled", true);
    }

    public static String getInfoColor(double val) {
        HologramConfig holo = Main.instance.getHologramConfig();
        if (val < 0.5) return holo.getColorLow();
        if (val < 0.6) return holo.getColorMedium();
        if (val < 0.8) return holo.getColorHigh();
        if (val < 0.9) return holo.getColorCritical();
        return             holo.getColorCriticalBold();
    }

    public static String getColorInfo(double val) {
        return getColorInfo(val, 4);
    }

    public static String getColorInfo(double val, int decimalPlaces) {
        HologramConfig holo = Main.instance.getHologramConfig();
        String fmt = FastMath.format(val, decimalPlaces);
        if (val < 0.5) return holo.getColorLow()       + fmt;
        if (val < 0.6) return holo.getColorMedium()    + fmt;
        if (val < 0.8) return holo.getColorHigh()      + fmt;
        if (val < 0.9) return holo.getColorCritical()  + fmt;
        return             holo.getColorCriticalBold() + fmt;
    }

    public static String getColorInfoFull(double val) {
        return getColorInfoFull(val, 4);
    }

    public static String getColorInfoFull(double val, int decimalPlaces) {
        HologramConfig holo = Main.instance.getHologramConfig();
        String fmt;
        if (decimalPlaces > 0) fmt = FastMath.format(val, decimalPlaces);
        else fmt = BigDecimal.valueOf(val)
                .stripTrailingZeros()
                .toPlainString();
        if (val < 0.5) return holo.getColorLow()       + fmt;
        if (val < 0.6) return holo.getColorMedium()    + fmt;
        if (val < 0.8) return holo.getColorHigh()      + fmt;
        if (val < 0.9) return holo.getColorCritical()  + fmt;
        return             holo.getColorCriticalBold() + fmt;
    }
}
