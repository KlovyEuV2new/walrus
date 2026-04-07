package wtf.walrus.ml.managers;

import wtf.walrus.Main;
import wtf.walrus.checks.CheckType;
import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.checks.impl.ai.MiningCheck;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerdictManager {

    private final AICheck aiCheck;
    private final MiningCheck miningCheck;

    private final ConcurrentHashMap<UUID, CheckType> playerVerdicts = new ConcurrentHashMap<>();

    public VerdictManager(AICheck aiCheck, MiningCheck miningCheck) {
        this.aiCheck = aiCheck;
        this.miningCheck = miningCheck;
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

    public void removePlayer(UUID uuid) {
        playerVerdicts.remove(uuid);
    }
}