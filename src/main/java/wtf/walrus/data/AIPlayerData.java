package wtf.walrus.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import wtf.walrus.util.AimProcessor;
import wtf.walrus.util.BufferCalculator;

public class AIPlayerData {

    private final UUID playerId;
    private final AimProcessor aimProcessor;
    private final Deque<TickData> tickBuffer;
    private final Deque<Double> probabilityHistory;
    private final int sequence;
    private int ticksSinceAttack;
    public int ticksSinceVerdict = 0;
    private volatile long lastAttackTime = 0;
    public int ticksStep, lastEndTick;
    private volatile double buffer;
    private volatile double lastProbability;
    private volatile boolean pendingRequest;
    private volatile boolean isBedrock;
    private volatile int highProbabilityDetections;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private DamageVerdict damageVerdict;

    public int getLastEndTick() { return lastEndTick; }

    public AIPlayerData(UUID playerId) {
        this(playerId, 40);
    }

    public AIPlayerData(UUID playerId, int sequence) {
        this.playerId = playerId;
        this.sequence = sequence;
        this.aimProcessor = new AimProcessor();
        this.tickBuffer = new ArrayDeque<>(sequence);
        this.probabilityHistory = new ArrayDeque<>(10);
        this.ticksSinceAttack = sequence + 1;
        this.ticksStep = 0;
        this.buffer = 0.0;
        this.lastProbability = 0.0;
        this.pendingRequest = false;
        this.isBedrock = false;
        this.highProbabilityDetections = 0;
    }

    public long getLastAttackTime() {
        return lastAttackTime;
    }

    public TickData processTick(float yaw, float pitch) {
        TickData tickData = aimProcessor.process(yaw, pitch);

        lock.writeLock().lock();
        try {
            if (tickBuffer.size() >= sequence) {
                tickBuffer.pollFirst();
            }
            tickBuffer.addLast(tickData);

            if (ticksSinceAttack <= sequence + 1) {
                ticksSinceAttack++;
            }
            ticksSinceVerdict++;
            ticksStep++;
        } finally {
            lock.writeLock().unlock();
        }

        return tickData;
    }

    public void onAttack() {
        lock.writeLock().lock();
        try {
            this.ticksSinceAttack = 0;
            this.lastAttackTime = System.currentTimeMillis();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onTeleport() {
        lock.writeLock().lock();
        try {
            aimProcessor.reset();
            clearBuffer();
            ticksSinceAttack = sequence + 1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isInCombat() {
        lock.readLock().lock();
        try {
            return ticksSinceAttack <= sequence;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void incrementStepCounter() {
        lock.writeLock().lock();
        try {
            this.ticksStep++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void incrementTicksSinceAttack() {
        lock.writeLock().lock();
        try {
            if (this.ticksSinceAttack <= sequence + 1) {
                this.ticksSinceAttack++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearBuffer() {
        lock.writeLock().lock();
        try {
            tickBuffer.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void fullReset() {
        lock.writeLock().lock();
        try {
            tickBuffer.clear();
            probabilityHistory.clear();
            aimProcessor.reset();
            pendingRequest = false;
            ticksSinceAttack = sequence + 1;
            ticksStep = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setPendingRequest(boolean pending) {
        lock.writeLock().lock();
        try {
            this.pendingRequest = pending;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isPendingRequest() {
        lock.readLock().lock();
        try {
            return pendingRequest;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TickData> getTickBuffer() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tickBuffer);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void resetStepCounter() {
        lock.writeLock().lock();
        try {
            ticksStep = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean shouldSendData(int step, int sequence) {
        lock.readLock().lock();
        try {
            return !pendingRequest && ticksStep >= step && tickBuffer.size() >= sequence;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean inEndCombat() {
        return lastEndTick == sequence;
    }

    public int getTicksSinceAttack() {
        lock.readLock().lock();
        try {
            return ticksSinceAttack;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getTicksStep() {
        lock.readLock().lock();
        try {
            return ticksStep;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getBufferSize() {
        lock.readLock().lock();
        try {
            return tickBuffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getSequence() {
        return sequence;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public AimProcessor getAimProcessor() {
        return aimProcessor;
    }

    public boolean isBedrock() {
        return isBedrock;
    }

    public void setBedrock(boolean bedrock) {
        this.isBedrock = bedrock;
    }

    public double getBuffer() {
        lock.readLock().lock();
        try {
            return buffer;
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getLastProbability() {
        lock.readLock().lock();
        try {
            return lastProbability;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Double> getProbabilityHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(probabilityHistory);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Double> getFormatedProbabilityHistory() {
        lock.readLock().lock();
        try {
            List<Double> all = new ArrayList<>(probabilityHistory);
            int size = all.size();
            return size <= 5 ? all : all.subList(size - 5, size);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getAverageProbability() {
        lock.readLock().lock();
        try {
            if (probabilityHistory.isEmpty()) return 0.0;
            return probabilityHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getFormatedAverageProbability() {
        lock.readLock().lock();
        try {
            List<Double> all = new ArrayList<>(probabilityHistory);
            int size = all.size();
            List<Double> last5 = size <= 5 ? all : all.subList(size - 5, size);
            return last5.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateBuffer(double probability, double multiplier, double decreaseAmount, double threshold) {
        lock.writeLock().lock();
        try {
            this.lastProbability = probability;
            if (probabilityHistory.size() >= 20) {
                probabilityHistory.pollFirst();
            }
            probabilityHistory.addLast(probability);
            if (probability > 0.8) {
                highProbabilityDetections++;
            }
            this.buffer = BufferCalculator.updateBuffer(buffer, probability, multiplier, decreaseAmount, threshold);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean shouldFlag(double flagThreshold) {
        lock.readLock().lock();
        try {
            return BufferCalculator.shouldFlag(buffer, flagThreshold);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void resetBuffer(double resetValue) {
        lock.writeLock().lock();
        try {
            this.buffer = BufferCalculator.resetBuffer(resetValue);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getHighProbabilityDetections() {
        return highProbabilityDetections;
    }

    public DamageVerdict getDamageVerdict() {
        return damageVerdict;
    }

    public void setDamageVerdict(DamageVerdict damageVerdict) {
        this.damageVerdict = damageVerdict;
    }
}