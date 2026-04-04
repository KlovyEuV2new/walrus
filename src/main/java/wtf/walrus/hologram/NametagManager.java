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
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;
import wtf.walrus.config.HologramConfig;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.scheduler.ScheduledTask;
import wtf.walrus.scheduler.SchedulerManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class NametagManager extends PacketListenerAbstract implements Listener {

    private static final double LINE_GAP = 0.25;

    private final JavaPlugin plugin;
    private final AICheck aiCheck;
    private final MiningCheck miningCheck;

    private final Map<UUID, int[]> armorStandIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSentText = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersMap = new ConcurrentHashMap<>();

    private ScheduledTask task;
    private int cleanupCounter = 0;

    public NametagManager(JavaPlugin plugin, AICheck aiCheck, MiningCheck miningCheck) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
    }

    public void start() {
        FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
        if (!config.getBoolean("nametags.enabled", true))
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
    }

    private boolean hasViewPermission(Player player) {
        return player.hasPermission(Permissions.ADMIN)
                || player.hasPermission(Permissions.ALERTS);
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
            FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
            double baseOffset = config.getDouble("nametags.height_offset", 2.3);

            Set<UUID> viewers = viewersMap.get(player.getUniqueId());
            if (viewers == null)
                return;

            for (int i = 0; i < entityIds.length; i++) {
                double lineY = pos.getY() + baseOffset + (entityIds.length - 1 - i) * LINE_GAP;
                WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                        entityIds[i], new Vector3d(pos.getX(), lineY, pos.getZ()), 0f, 0f, false);

                for (UUID viewerId : viewers) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null && viewer.isOnline() && hasViewPermission(viewer)) {
                        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleport);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void globalTick() {
        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Player> admins = new ArrayList<>();

        for (Player p : allPlayers) {
            if (hasViewPermission(p)) {
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

        FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
        String format = config.getString("nametags.format", "&6▶ &7AVG: &f{AVG} &8| {HISTORY} &6◀");

        double avgProb = data.getFormatedAverageProbability();
        double mineAvgProb = miningData.getFormatedAverageProbability();

        if (avgProb <= 0.0 && mineAvgProb <= 0.0) {
            despawnForAll(target.getUniqueId());
            return;
        }

        List<Double> history = data.getFormatedProbabilityHistory();
        List<Double> mineHistory = miningData.getFormatedProbabilityHistory();

        String historyStr = "-";
        if (!history.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Double val : history) {
                sb.append(getColorInfo(val)).append(" ");
            }
            historyStr = sb.toString().trim();
        }

        String mineHistoryStr = "-";
        if (!mineHistory.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Double val : mineHistory) {
                sb.append(getColorInfo(val)).append(" ");
            }
            mineHistoryStr = sb.toString().trim();
        }

        String filled = format
                .replace("{AVG}", String.format("%.4f", avgProb))
                .replace("{MINE_AVG}", String.format("%.4f", mineAvgProb))
                .replace("{MINE_AVG_COLORED}", getColorInfo(mineAvgProb))
                .replace("{MINE_HISTORY}", mineHistoryStr)
                .replace("{AVG_COLORED}", getColorInfo(avgProb))
                .replace("{HISTORY}", historyStr);

        String[] lines = filled.split("\\{NL\\}", -1);

        double baseOffset = config.getDouble("nametags.height_offset", 2.3);
        Location baseLoc = target.getLocation();

        int[] existingIds = armorStandIds.get(target.getUniqueId());
        if (existingIds != null && existingIds.length != lines.length) {
            despawnForAll(target.getUniqueId());
        }

        int[] entityIds = armorStandIds.computeIfAbsent(target.getUniqueId(), k -> {
            int[] ids = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                ids[i] = ThreadLocalRandom.current().nextInt(1000000, 2000000);
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
                    viewer.getLocation().distanceSquared(baseLoc) > 10000) {
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
                } else if (!hasViewPermission(viewer)) {
                    removeViewer(target.getUniqueId(), viewer);
                }
            }
        }

        if (textChanged) {
            lastSentText.put(target.getUniqueId(), textKey);
        }
    }

    public void updateFor(Player target, Player viewer, int[] entityIds, String[] lines,
                          Location baseLoc, double baseOffset, boolean textChanged) {
        Set<UUID> viewers = viewersMap.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean isNew = viewers.add(viewer.getUniqueId());

        for (int i = 0; i < entityIds.length; i++) {
            double lineY = baseLoc.getY() + baseOffset + (entityIds.length - 1 - i) * LINE_GAP;
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
                List<EntityData<?>> metadata =
                        getVersionedMetadata(viewer, lines[i]);
                WrapperPlayServerEntityMetadata metadataPacket =
                        new WrapperPlayServerEntityMetadata(entityIds[i], metadata);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
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

    private List<EntityData<?>> getVersionedMetadata(
            Player viewer, String text) {
        List<EntityData<?>> metadata = new ArrayList<>();
        ClientVersion clientVersion = PacketEvents.getAPI()
                .getPlayerManager().getClientVersion(viewer);
        int version = clientVersion != null ? clientVersion.getProtocolVersion() : 770;

        metadata.add(new EntityData<Byte>(
                0, EntityDataTypes.BYTE, (byte) 0x20));

        LegacyComponentSerializer legacySerializer =
                LegacyComponentSerializer.builder()
                        .character('&')
                        .hexColors()
                        .useUnusualXRepeatedCharacterHexFormat()
                        .build();
        Component component = legacySerializer.deserialize(text);

        if (version >= 766) {
            metadata.add(
                    new EntityData<Optional<Component>>(
                            2,
                            EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.of(component)));
            metadata.add(new EntityData<Boolean>(
                    3, EntityDataTypes.BOOLEAN, true));
            metadata.add(new EntityData<Byte>(
                    15, EntityDataTypes.BYTE, (byte) 0x10));

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

        if (version < 766) {
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
        }

        return metadata;
    }

    public static String getColorInfo(double val) {
        HologramConfig holo = Main.instance.getHologramConfig();
        String fmt = String.format("%.4f", val);
        if (val < 0.5) return holo.getColorLow()       + fmt;
        if (val < 0.6) return holo.getColorMedium()    + fmt;
        if (val < 0.8) return holo.getColorHigh()      + fmt;
        if (val < 0.9) return holo.getColorCritical()  + fmt;
        return             holo.getColorCriticalBold() + fmt;
    }

    public static String getColorInfoFull(double val) {
        HologramConfig holo = Main.instance.getHologramConfig();
        String fmt = String.format("%.4f", val);
        if (val < 0.5) return holo.getColorLow()       + fmt;
        if (val < 0.6) return holo.getColorMedium()    + fmt;
        if (val < 0.8) return holo.getColorHigh()      + fmt;
        if (val < 0.9) return holo.getColorCritical()  + fmt;
        if (val > 0.99999) return holo.getColorCriticalBold() + val;
        return             holo.getColorCriticalBold() + fmt;
    }
}