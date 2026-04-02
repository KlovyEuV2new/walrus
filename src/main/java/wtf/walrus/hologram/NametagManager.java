package wtf.walrus.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.walrus.Main;

import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.config.HologramConfig;
import wtf.walrus.data.AIPlayerData;
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

    private final Map<UUID, int[]> armorStandIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSentText = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersMap = new ConcurrentHashMap<>();

    private ScheduledTask task;
    private int cleanupCounter = 0;

    public NametagManager(JavaPlugin plugin, AICheck aiCheck) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.aiCheck = aiCheck;
    }

    public void start() {
        org.bukkit.configuration.file.FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
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
        return player.hasPermission(wtf.walrus.Permissions.ADMIN)
                || player.hasPermission(wtf.walrus.Permissions.ALERTS);
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
            org.bukkit.configuration.file.FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
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
        AIPlayerData data = aiCheck.getPlayerData(target.getUniqueId());
        if (data == null)
            return;

        org.bukkit.configuration.file.FileConfiguration config = ((Main) plugin).getHologramConfig().getConfig();
        String format = config.getString("nametags.format", "&6▶ &7AVG: &f{AVG} &8| {HISTORY} &6◀");

        double avgProb = data.getFormatedAverageProbability();
        if (avgProb < 0.0001 / data.getBufferSize()) {
            despawnForAll(target.getUniqueId());
            return;
        }
        List<Double> history = data.getFormatedProbabilityHistory();

        String historyStr = "-";
        if (!history.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Double val : history) {
                sb.append(getColorInfo(val)).append(" ");
            }
            historyStr = sb.toString().trim();
        }

        String filled = format
                .replace("{AVG}", String.format("%.4f", avgProb))
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
                List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> metadata =
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

    private List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> getVersionedMetadata(
            Player viewer, String text) {
        List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> metadata = new ArrayList<>();
        com.github.retrooper.packetevents.protocol.player.ClientVersion clientVersion = PacketEvents.getAPI()
                .getPlayerManager().getClientVersion(viewer);
        int version = clientVersion != null ? clientVersion.getProtocolVersion() : 770;

        metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Byte>(
                0, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE, (byte) 0x20));

        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySerializer =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                        .character('&')
                        .hexColors()
                        .useUnusualXRepeatedCharacterHexFormat()
                        .build();
        net.kyori.adventure.text.Component component = legacySerializer.deserialize(text);

        if (version >= 766) {
            metadata.add(
                    new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Optional<net.kyori.adventure.text.Component>>(
                            2,
                            com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.of(component)));
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Boolean>(
                    3, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BOOLEAN, true));
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Byte>(
                    15, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE, (byte) 0x10));

        } else if (version >= 393) {
            String json = com.github.retrooper.packetevents.util.adventure.AdventureSerializer.getGsonSerializer()
                    .serialize(component);
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Optional<String>>(
                    2, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.OPTIONAL_COMPONENT,
                    Optional.of(json)));
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Boolean>(
                    3, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BOOLEAN, true));

        } else {
            String legacyStr = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .serialize(component);
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<String>(
                    2, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.STRING, legacyStr));
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Boolean>(
                    3, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BOOLEAN, true));
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
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<Byte>(
                    markerIndex, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE,
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