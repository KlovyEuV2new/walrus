package wtf.walrus.rotationloader;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;
import wtf.walrus.Main;
import wtf.walrus.data.TickData;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.ml.client.LocalAIClient;
import wtf.walrus.npc.NPC;
import wtf.walrus.server.AIClientProvider;
import wtf.walrus.server.FlatBufferSerializer;
import wtf.walrus.server.IAIClient;
import wtf.walrus.util.ColorUtil;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class RotationSession {
    private static final Map<UUID, List<RotationSession>> sessions = new ConcurrentHashMap<>();
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newCachedThreadPool();
    private static final Random RANDOM = new Random();

    private static final double[] CRIT_Y_OFFSETS = {
            0.42, 0.753, 1.001, 1.166, 1.249, 1.252,
            1.176, 1.024, 0.796, 0.495, 0.123
    };

    private final String name, fileName;
    private final UUID uuid;
    private final int entityId;
    private final List<TickData> ticks;
    private final boolean crits;
    private final boolean follow;
    private final String npcName;
    private NPC current = null;
    private BukkitRunnable currentTask = null;
    private int rotationCount = 0;
    private int predictionsCount = 0;
    private double sum = 0;
    private double min = Double.MAX_VALUE;
    private double max = Double.MIN_VALUE;
    private final List<TickData> rotationBuffer = new ArrayList<>();

    private int critPhase = -1;
    private int critCooldown = 0;
    private static final int CRIT_INTERVAL = 11;

    private double velX = 0;
    private double velZ = 0;

    public RotationSession(String name, String fileName, UUID uuid, int entityId, List<TickData> ticks, boolean crits) {
        this.name = name;
        this.fileName = fileName;
        this.uuid = uuid;
        this.entityId = entityId;
        this.ticks = ticks;
        this.crits = crits;
        this.follow = false;
        this.npcName = generateNPCName();
    }

    public RotationSession(String name, String fileName, UUID uuid, int entityId, List<TickData> ticks, boolean crits, boolean follow) {
        this.name = name;
        this.fileName = fileName;
        this.uuid = uuid;
        this.entityId = entityId;
        this.ticks = ticks;
        this.crits = crits;
        this.follow = follow;
        this.npcName = generateNPCName();
    }

    public RotationSession(String name, String fileName, UUID uuid, int entityId, List<TickData> ticks, boolean crits, Set<String> modelIds) {
        this.name = name;
        this.fileName = fileName;
        this.uuid = uuid;
        this.entityId = entityId;
        this.ticks = ticks;
        this.crits = crits;
        this.follow = false;
        this.npcName = generateNPCName();
    }

    private String generateNPCName() {
        int randomNum = 1000 + RANDOM.nextInt(9000);
        return "игрок_" + randomNum;
    }

    private void stopInternal(@Nullable User user) {
        if (currentTask != null && !currentTask.isCancelled()) {
            currentTask.cancel();
            currentTask = null;
        }

        if (user != null && predictionsCount > 0) {
            final int total = predictionsCount;
            final double avg = sum / total;
            final double mn = min;
            final double mx = max;
            String msg = String.format(
                    "=== FINAL [%s]: Total=%d, Avg=%.6f, Min=%.6f, Max=%.6f ===",
                    fileName, total, avg, mn, mx
            );
            user.sendMessage(msg);
        }

        if (current != null) {
            if (user != null) {
                try {
                    current.despawn(user);
                } catch (Exception ex) {
                    Main.instance.getLogger().warning("[RotationSession] despawn error: " + ex.getMessage());
                }
            }
            current = null;
        }

        synchronized (rotationBuffer) {
            rotationBuffer.clear();
        }
    }

    public void load(User user, Location location) {
        stop(user);

        NPC npc = new NPC(entityId, uuid, npcName, location);
        npc.spawn(user);
        this.current = npc;

        sessions.computeIfAbsent(user.getUUID(), k -> new CopyOnWriteArrayList<>()).add(this);

        rotationCount = 0;
        predictionsCount = 0;
        sum = 0;
        min = Double.MAX_VALUE;
        max = Double.MIN_VALUE;
        rotationBuffer.clear();
        critPhase = -1;
        critCooldown = 0;
        velX = 0;
        velZ = 0;

        final int[] tickIndex = {-1};
        final Location[] currentLocation = {location};

        currentTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (current == null || !current.equals(npc)) {
                    stop(user);
                    cancel();
                    return;
                }

                int nextIndex = tickIndex[0] + 1;
                if (nextIndex >= ticks.size()) {
                    stop(user);
                    cancel();
                    return;
                }

                tickIndex[0] = nextIndex;
                TickData tick = ticks.get(nextIndex);
                if (tick == null) return;

                if (follow) {
                    Player target = Bukkit.getPlayer(user.getUUID());
                    if (target != null) {
                        Location npcLoc = currentLocation[0];
                        org.bukkit.Location targetLoc = target.getLocation();

                        double dx = targetLoc.getX() - npcLoc.getX();
                        double dz = targetLoc.getZ() - npcLoc.getZ();
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        if (dist > 2.5) {
                            double speed = 0.22;
                            double friction = 0.91;

                            double dirX = dx / dist;
                            double dirZ = dz / dist;

                            velX += dirX * speed;
                            velZ += dirZ * speed;

                            double currentSpeed = Math.sqrt(velX * velX + velZ * velZ);
                            double maxSpeed = 0.35;
                            if (currentSpeed > maxSpeed) {
                                velX = (velX / currentSpeed) * maxSpeed;
                                velZ = (velZ / currentSpeed) * maxSpeed;
                            }

                            velX *= friction;
                            velZ *= friction;

                            double newX = npcLoc.getX() + velX;
                            double newZ = npcLoc.getZ() + velZ;
                            double newY = targetLoc.getY();

                            float yawToTarget = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

                            double eyeY = npcLoc.getY() + 1.62;
                            double targetEyeY = targetLoc.getY() + 1.62;
                            double dyEye = targetEyeY - eyeY;
                            double distEye = Math.sqrt(dx * dx + dz * dz);
                            float pitchToTarget = (float) (-Math.toDegrees(Math.atan2(dyEye, distEye)));

                            Location newLoc = new Location(newX, newY, newZ, yawToTarget, pitchToTarget);
                            npc.teleport(user, newLoc);
                            currentLocation[0] = newLoc;
                        } else {
                            velX *= 0.6;
                            velZ *= 0.6;

                            float yawToTarget = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

                            double eyeY = npcLoc.getY() + 1.62;
                            double targetEyeY = targetLoc.getY() + 1.62;
                            double dyEye = targetEyeY - eyeY;
                            double distEye = Math.sqrt(dx * dx + dz * dz);
                            float pitchToTarget = (float) (-Math.toDegrees(Math.atan2(dyEye, distEye)));

                            Location newLoc = new Location(npcLoc.getX(), npcLoc.getY(), npcLoc.getZ(), yawToTarget, pitchToTarget);
                            npc.teleport(user, newLoc);
                            currentLocation[0] = newLoc;
                        }
                    }
                }

                npc.rotate(user, tick.deltaYaw, tick.deltaPitch);
                rotationCount++;

                if (crits) {
                    if (critPhase >= 0) {
                        double yOffset = CRIT_Y_OFFSETS[critPhase];
                        Location base = currentLocation[0];
                        Location moved = new Location(base.getX(), base.getY() + yOffset, base.getZ(), base.getYaw(), base.getPitch());
                        npc.teleport(user, moved);
                        critPhase++;
                        if (critPhase >= CRIT_Y_OFFSETS.length) {
                            npc.teleport(user, currentLocation[0]);
                            critPhase = -1;
                            critCooldown = CRIT_INTERVAL;
                            npc.swing(user, 0);
                        }
                    } else if (critCooldown > 0) {
                        critCooldown--;
                    } else {
                        critPhase = 0;
                    }
                }

                synchronized (rotationBuffer) {
                    rotationBuffer.add(tick);

                    if (rotationCount % 40 == 0) {
                        List<TickData> toPredict = new ArrayList<>(rotationBuffer);
                        rotationBuffer.clear();

                        ASYNC_EXECUTOR.submit(() -> {
                            try {
                                AIClientProvider clientProvider = Main.instance.getAiClientProvider();
                                IAIClient client = clientProvider.get();
                                if (client == null) return;

                                if (client instanceof LocalAIClient) {
                                    var model = Main.instance.getLocalAIClientProvider().getModel();
                                    if (model == null) return;

                                    var out = model.predict(toPredict);
                                    if (out == null) return;

                                    handlePredictionResult(user, out.prob(), "local");
                                } else {
                                    Set<String> models = Main.instance.getPluginConfig().getModelNames().keySet()
                                            .stream()
                                            .filter(modelId -> Main.instance.getPluginConfig().isDisabledModel(modelId) || Main.instance.getPluginConfig().isOnlyAlertForModel(modelId))
                                            .collect(Collectors.toSet());

                                    if (models.isEmpty()) return;
                                    byte[] serialized = FlatBufferSerializer.serialize(toPredict);

                                    client.predict(serialized, uuid.toString(), npcName, models.stream().toList(), Main.instance.getPluginConfig().getModelsOnlyAlert())
                                            .subscribe(
                                                    response -> {
                                                        synchronized (RotationSession.this) {
                                                            if (response != null && response.getProbability() >= 0) {
                                                                handlePredictionResult(user, response.getProbability(), response.getModel());
                                                            }
                                                        }
                                                    },
                                                    error -> {
                                                        synchronized (RotationSession.this) {
                                                            Main.instance.getLogger().warning("[RotationSession] Error: " + error.getMessage());
                                                        }
                                                    }
                                            );
                                }
                            } catch (Exception e) {
                                Main.instance.getLogger().warning("[RotationSession] ML error: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }
        };
        currentTask.runTaskTimer(Main.instance, 1L, 1L);
    }

    private void handlePredictionResult(User user, double prob, String modelName) {
        synchronized (RotationSession.this) {
            predictionsCount++;
            sum += prob;
            if (prob < min) min = prob;
            if (prob > max) max = prob;
        }

        final int checkNum = predictionsCount;
        String color = NametagManager.getInfoColor(prob);
        final String finalProb = ColorUtil.colorize(color) + String.format("%.6f", prob);
        final String modelDisplay = (modelName != null && !modelName.isEmpty()) ? " §7[" + modelName + "§7]" : "";

        Bukkit.getScheduler().runTask(Main.instance, () ->
                user.sendMessage(String.format("[%s] Check #%d: prob=%s%s", fileName, checkNum, finalProb, modelDisplay))
        );
    }

    public void stop(User user) {
        stopInternal(user);

        List<RotationSession> userSessions = sessions.get(user.getUUID());
        if (userSessions != null) {
            userSessions.remove(this);
            if (userSessions.isEmpty()) {
                sessions.remove(user.getUUID());
            }
        }
    }

    public static void stopAll(User user) {
        List<RotationSession> userSessions = sessions.remove(user.getUUID());
        if (userSessions == null) return;

        for (RotationSession session : userSessions) {
            session.stopInternal(user);
        }
    }

    public static void stopAll() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            List<RotationSession> userSessions = sessions.remove(uuid);
            if (userSessions == null) continue;

            User user = resolveUser(uuid);

            for (RotationSession session : userSessions) {
                session.stopInternal(user);
            }
        }
    }

    @Nullable
    private static User resolveUser(UUID uuid) {
        Player bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) return null;

        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(bukkitPlayer);
        } catch (Exception ex) {
            Main.instance.getLogger().warning("[RotationSession] Error getting User for " + uuid + ": " + ex.getMessage());
            return null;
        }
    }

    public boolean isActive() {
        return current != null && currentTask != null && !currentTask.isCancelled();
    }

    public String getName() { return name; }
    public UUID getUuid() { return uuid; }
    public List<TickData> getTicks() { return ticks; }
    public boolean isCrits() { return crits; }
    public boolean isFollow() { return follow; }
    public String getNPCName() { return npcName; }
}