package wtf.walrus.ml.managers;

import wtf.walrus.Main;
import wtf.walrus.checks.CheckType;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerdictManager {

    private final AICheck aiCheck;
    private final MiningCheck miningCheck;

    private final Map<UUID, String> servers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> teleports = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, CheckType> playerVerdicts = new ConcurrentHashMap<>();

    public VerdictManager(AICheck aiCheck, MiningCheck miningCheck) {
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
    }

    public void quit(UUID uuid, String serverName, boolean enable) {
        try {
            if (!enable) servers.put(uuid, serverName);
            else servers.remove(uuid);
        } catch (Exception ignored) {}
    }

    public String getServer(UUID uuid) {
        return getServer(uuid, Main.instance.getPluginConfig().getServerName());
    }

    public String getServer(UUID uuid, String def) {
        return servers.getOrDefault(uuid, def);
    }

    public boolean isQuit(UUID uuid) {
        return !servers.containsKey(uuid);
    }

    public void setVerdict(UUID playerUuid, CheckType type) {
        playerVerdicts.put(playerUuid, type);
    }

    public CheckType getLastVerdict(UUID playerUuid) {
        return playerVerdicts.getOrDefault(playerUuid, CheckType.UNKNOWN);
    }

    public Object getLastClass(UUID playerUuid) {
        CheckType verdict = getLastVerdict(playerUuid);
        if (verdict == CheckType.AIM)   return aiCheck;
        if (verdict == CheckType.BLOCK) return miningCheck;
        return null;
    }

    public UUID getTeleport(UUID uuid) {
        return teleports.get(uuid);
    }

    public void removeTeleport(UUID uuid) {
        teleports.remove(uuid);
    }

    public void removePlayer(UUID uuid) {
        playerVerdicts.remove(uuid);
    }

    public void addTeleport(UUID playerUUID, UUID targetUUID) {
        teleports.put(playerUUID, targetUUID);
    }
}