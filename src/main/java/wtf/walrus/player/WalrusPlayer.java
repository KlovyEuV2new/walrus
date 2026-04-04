package wtf.walrus.player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import io.github.retrooper.packetevents.adventure.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import wtf.walrus.Main;
import wtf.walrus.checks.manager.CheckManager;
import wtf.walrus.data.PlayerRotationData;
import wtf.walrus.util.SimulationUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class WalrusPlayer {

    private static final CopyOnWriteArrayList<WalrusPlayer> players = new CopyOnWriteArrayList<>();

    public final User user;
    public final UUID uuid;

    public GameMode gameMode;
    public boolean wasSprinting, isSprinting, wasSneaking, isSneaking, isFlying, isAllowedFlight, godMode, creativeAbility,
            inventoryOpened, isGliding, isSwimming, wasSwimming, wasGliding, inVehicle, didSendMovementBeforeTickEnd,
            lastPacketWasTeleport, lastPacketWasOnePointSeventeenDuplicate, lastOnGround, onGround;
    public int heldSlot, xp, openWindowID;
    public long lastInteract, lastAttack, lastStopSprint, lastStartSprint, lastStopSneaking, lastStartSneaking, firstJoined;
    public Vector3d lastPosition, position;
    public double deltaY, deltaXZ, deltaZ, deltaX, lastDeltaY, lastDeltaXZ, lastDeltaZ, lastDeltaX;
    public float lastYaw, yaw, deltaYaw, lastPitch, pitch, deltaPitch, lastDeltaPitch, lastDeltaYaw, flySpeed;
    public float health, absorption;

    public final CheckManager checkManager;
    public final PlayerRotationData rotationData;

    private final AtomicInteger transactionIDCounter = new AtomicInteger(0);
    private final Set<Short> didWeSendThatTrans = ConcurrentHashMap.newKeySet();
    private long lastTransSent = 0;
    private long lastTransactionReceived = 0;
    private short lastTransactionReceivedId = 0;

    public int entityID;

    private final Set<Short> receivedTransactions = ConcurrentHashMap.newKeySet();
    private final Map<Short, Long> sentTransactionTimes = new ConcurrentHashMap<>();

    public long getTransactionPing() {
        Long sent = sentTransactionTimes.get(lastTransactionReceivedId);
        if (sent == null) return -1;
        return lastTransactionReceived - sent;
    }

    public WalrusPlayer(User user, UUID uuid) {
        this.user = user;
        this.uuid = uuid;
        this.checkManager = new CheckManager(this);
        this.rotationData = new PlayerRotationData(this);
        this.firstJoined = System.currentTimeMillis();
        this.entityID = user.getEntityId();
        players.add(this);
    }

    public void disconnect(Component reason) {
        try {
            user.sendPacket(new WrapperPlayServerDisconnect(reason));
        } catch (Exception ignored) {
        } finally {
            user.closeConnection();
        }
    }

    public boolean hasAttackedSince(long time) {
        return System.nanoTime() - lastAttack <= time * 1_000_000;
    }

    public void onDisconnect() {
        players.remove(this);
    }

    public double deltaX() {
        if (lastPosition == null || position == null) return 0;
        return position.getX() - lastPosition.getX();
    }

    public double deltaY() {
        if (lastPosition == null || position == null) return 0;
        return position.getY() - lastPosition.getY();
    }

    public double deltaZ() {
        if (lastPosition == null || position == null) return 0;
        return position.getZ() - lastPosition.getZ();
    }

    public double deltaXZ() {
        if (lastPosition == null || position == null) return 0;
        double dx = position.getX() - lastPosition.getX();
        double dz = position.getZ() - lastPosition.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double distanceXZ(Vector3d from, Vector3d to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx*dx + dz*dz);
    }

    public static WalrusPlayer get(UUID uuid) {
        for (WalrusPlayer player : players) if (player.uuid.equals(uuid)) return player;
        return null;
    }

    public void sendTransaction() {
        sendTransaction(false);
    }

    @Contract(pure = true)
    public boolean canSkipTicks() {
        return user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && !supportsEndTick();
    }

    @Contract(pure = true)
    public boolean supportsEndTick() {
        return user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2) && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2);
    }

    public void sendTransaction(boolean async) {
        if (user.getEncoderState() != com.github.retrooper.packetevents.protocol.ConnectionState.PLAY) return;

        lastTransSent = System.currentTimeMillis();
        short transactionID = (short) (-1 * (transactionIDCounter.getAndIncrement() & 0x7FFF));

        try {
            PacketWrapper<?> packet = new WrapperPlayServerWindowConfirmation((byte) 0, transactionID, false);

            sentTransactionTimes.put(transactionID, System.currentTimeMillis());

            if (async) {
                runSafely(() -> {
                    addTransactionSend(transactionID);
                    user.writePacket(packet);
                });
            } else {
                addTransactionSend(transactionID);
                user.writePacket(packet);
            }
        } catch (Exception ignored) {}
    }

    public void cleanupTransactions() {
        long now = System.currentTimeMillis();
        sentTransactionTimes.entrySet().removeIf(entry -> now - entry.getValue() > 15000);
    }

    private void addTransactionSend(short id) {
        didWeSendThatTrans.add(id);
    }

    public void runSafely(Runnable runnable) {
        com.github.retrooper.packetevents.netty.channel.ChannelHelper.runInEventLoop(user.getChannel(), runnable);
    }

    public long getLastTransSent() {
        return lastTransSent;
    }

    public void setLastTransactionReceived(long lastTransactionReceived) {
        this.lastTransactionReceived = lastTransactionReceived;
    }

    public void setLastTransactionReceivedId(short lastTransactionReceivedId) {
        this.lastTransactionReceivedId = lastTransactionReceivedId;
    }

    public short getLastTransactionReceivedId() {
        return lastTransactionReceivedId;
    }

    public long getLastTransactionReceived() {
        return lastTransactionReceived;
    }

    public static List<WalrusPlayer> getPlayers() {
        return players;
    }
}