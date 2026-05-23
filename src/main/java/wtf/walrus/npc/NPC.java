package wtf.walrus.npc;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

public class NPC {
    private int entityId;
    private UUID uuid;
    private String name;
    private Location currentLocation;
    private float targetYaw;
    private float targetPitch;
    private float currentYaw;
    private float currentPitch;
    private double targetX, targetY, targetZ;
    private double lastX, lastY, lastZ;
    private long lastUpdate;
    private boolean spawned, hasInTab;
    private ServerVersion version;

    public NPC(int entityId, UUID uuid, String name, Location spawnLocation) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.name = name;
        this.currentLocation = spawnLocation;
        this.currentYaw = spawnLocation.getYaw();
        this.currentPitch = spawnLocation.getPitch();
        this.targetYaw = spawnLocation.getYaw();
        this.targetPitch = spawnLocation.getPitch();
        this.targetX = spawnLocation.getX();
        this.targetY = spawnLocation.getY();
        this.targetZ = spawnLocation.getZ();
        this.lastX = spawnLocation.getX();
        this.lastY = spawnLocation.getY();
        this.lastZ = spawnLocation.getZ();
        this.lastUpdate = System.currentTimeMillis();
        this.spawned = false;
        this.version = PacketEvents.getAPI().getServerManager().getVersion();
    }

    private void sendPacket(User user, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        if (user != null) {
            try {
                user.sendPacket(packet);
            } catch (Exception ignored) {}
        }
    }

    public void spawn(User user) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_20_2)) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(uuid),
                    com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.PLAYER,
                    currentLocation.getPosition(),
                    currentPitch,
                    currentYaw,
                    currentYaw,
                    0,
                    Optional.empty()
            );
            sendPacket(user, spawn);
        } else {
            WrapperPlayServerSpawnPlayer spawn = new WrapperPlayServerSpawnPlayer(
                    entityId,
                    uuid,
                    currentLocation
            );
            sendPacket(user, spawn);
        }

        if (Bukkit.getPlayer(uuid) == null) {
            WrapperPlayServerPlayerInfo info = new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    new WrapperPlayServerPlayerInfo.PlayerData(
                            net.kyori.adventure.text.Component.text(name),
                            new UserProfile(uuid, name),
                            com.github.retrooper.packetevents.protocol.player.GameMode.SURVIVAL,
                            0
                    )
            );
            sendPacket(user, info);
            hasInTab = true;
        }

        spawned = true;
    }

    public void despawn(User user) {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
        sendPacket(user, destroy);

        if (hasInTab) {
            WrapperPlayServerPlayerInfo info = new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                    new WrapperPlayServerPlayerInfo.PlayerData(
                            net.kyori.adventure.text.Component.text(name),
                            new UserProfile(uuid, name),
                            com.github.retrooper.packetevents.protocol.player.GameMode.SURVIVAL,
                            0
                    )
            );
            sendPacket(user, info);
        }

        spawned = false;
    }

    public void swing(@NotNull User user, int animationType) {
        try {
            WrapperPlayServerEntityAnimation.EntityAnimationType type =
                    WrapperPlayServerEntityAnimation.EntityAnimationType.values()[animationType];
            WrapperPlayServerEntityAnimation packet = new WrapperPlayServerEntityAnimation(entityId, type);
            sendPacket(user, packet);
        } catch (Exception ignored) {
        }
    }

    public void rotation(User user) {
        float yawDiff = targetYaw - currentYaw;
        if (yawDiff > 180) yawDiff -= 360;
        if (yawDiff < -180) yawDiff += 360;
        currentYaw += yawDiff;

        float pitchDiff = targetPitch - currentPitch;
        if (pitchDiff > 180) pitchDiff -= 360;
        if (pitchDiff < -180) pitchDiff += 360;
        currentPitch += pitchDiff;

        WrapperPlayServerEntityHeadLook head = new WrapperPlayServerEntityHeadLook(entityId, currentYaw);
        sendPacket(user, head);

        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                entityId,
                currentLocation.getPosition(),
                currentYaw,
                currentPitch,
                false
        );
        sendPacket(user, teleport);
    }

    public void move(User user) {
        double newX = currentLocation.getX() + (targetX - currentLocation.getX()) * 0.25;
        double newY = currentLocation.getY() + (targetY - currentLocation.getY()) * 0.25;
        double newZ = currentLocation.getZ() + (targetZ - currentLocation.getZ()) * 0.25;

        Location newLocation = new Location(newX, newY, newZ, currentYaw, currentPitch);

        double deltaX = newX - lastX;
        double deltaY = newY - lastY;
        double deltaZ = newZ - lastZ;

        if (Math.abs(deltaX) < 32767 && Math.abs(deltaY) < 32767 && Math.abs(deltaZ) < 32767) {
            WrapperPlayServerEntityRelativeMove move = new WrapperPlayServerEntityRelativeMove(
                    entityId, deltaX, deltaY, deltaZ, false
            );
            sendPacket(user, move);
        } else {
            WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                    entityId,
                    newLocation.getPosition(),
                    currentYaw,
                    currentPitch,
                    false
            );
            sendPacket(user, teleport);
        }

        this.currentLocation = newLocation;
        this.lastX = newX;
        this.lastY = newY;
        this.lastZ = newZ;
    }

    public void teleport(User user, Location location) {
        this.currentLocation = location;
        this.targetX = location.getX();
        this.targetY = location.getY();
        this.targetZ = location.getZ();
        this.currentYaw = location.getYaw();
        this.currentPitch = location.getPitch();
        this.targetYaw = location.getYaw();
        this.targetPitch = location.getPitch();
        this.lastX = location.getX();
        this.lastY = location.getY();
        this.lastZ = location.getZ();

        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                entityId,
                currentLocation.getPosition(),
                currentYaw,
                currentPitch,
                false
        );
        sendPacket(user, teleport);

        WrapperPlayServerEntityHeadLook head = new WrapperPlayServerEntityHeadLook(entityId, currentYaw);
        sendPacket(user, head);
    }

    public void rotate(User user, float deltaYaw, float deltaPitch) {
        this.targetYaw = currentYaw + deltaYaw;
        this.targetPitch = currentPitch + deltaPitch;

        if (targetYaw > 180) targetYaw -= 360;
        if (targetYaw < -180) targetYaw += 360;
        if (targetPitch > 90) targetPitch = 90;
        if (targetPitch < -90) targetPitch = -90;

        float yawDiff = targetYaw - currentYaw;
        if (yawDiff > 180) yawDiff -= 360;
        if (yawDiff < -180) yawDiff += 360;
        currentYaw += yawDiff;

        float pitchDiff = targetPitch - currentPitch;
        currentPitch += pitchDiff;

        WrapperPlayServerEntityHeadLook head = new WrapperPlayServerEntityHeadLook(entityId, currentYaw);
        sendPacket(user, head);

        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                entityId,
                currentLocation.getPosition(),
                currentYaw,
                currentPitch,
                false
        );
        sendPacket(user, teleport);
    }

    public void setRotation(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
    }

    public void setRotation(float yaw) {
        this.targetYaw = yaw;
    }

    public void setPitch(float pitch) {
        this.targetPitch = pitch;
    }

    public void setTarget(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void update() {
        this.lastUpdate = System.currentTimeMillis();
    }

    public boolean isExpired(long timeout) {
        return System.currentTimeMillis() - lastUpdate > timeout;
    }

    public int getEntityId() {
        return entityId;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public float getTargetYaw() {
        return targetYaw;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }

    public boolean isSpawned() {
        return spawned;
    }

    public void setTargetYaw(float targetYaw) {
        this.targetYaw = targetYaw;
    }

    public void setTargetPitch(float targetPitch) {
        this.targetPitch = targetPitch;
    }
}