package wtf.walrus.data;

import wtf.walrus.player.WalrusPlayer;
import wtf.walrus.util.SensValues;
import wtf.walrus.util.TimeMilistUtil;

import java.util.*;

public class PlayerRotationData {

    public WalrusPlayer player;

    private float yaw;
    private float pitch;
    private float lastYaw;
    private float lastPitch;
    private float deltaYaw;
    private float deltaPitch;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float yawAccel;
    private float pitchAccel;
    private float lastYawAccel;
    private float lastPitchAccel;
    private float rawMouseDeltaX;
    private float rawMouseDeltaY;
    private float fuckedPredictedPitch;
    private float fuckedPredictedYaw;
    private float lastFuckedPredictedPitch;
    private float lastFuckedPredictedYaw;
    private boolean invalidRate;
    private boolean invalidSensitivity;
    private boolean cinematic;
    private double finalSensitivity;
    private double mcpSensitivity;
    private final ArrayDeque<Integer> sensitivitySamples;
    private int sensitivity;
    private int lastRate;
    private int lastInvalidSensitivity;
    private TimeMilistUtil lastCinematic;
    private int mouseDeltaX;
    private int mouseDeltaY;
    private float lastJoltYaw;
    private float joltYaw;
    private float joltPitch;

    private float dy = 0.0f;
    private float dx = 0.0f;
    private float ddy = 0.0f;
    private float ddx = 0.0f;
    private float dddy = 0.0f;
    private float dddx = 0.0f;
    private final float lastDy = 0.0f;
    private final float lastDx = 0.0f;
    private final float lastDdy = 0.0f;
    private final float lastDdx = 0.0f;
    private float smoothness = 0.0f;
    private float consistency = 0.0f;
    private float smoothnessPitch;
    private float smoothnessYaw;

    private long lastHighRate = 0L;
    private long lastSmooth = 0L;
    private int isTotallyNotCinematic = 0;
    private final List<Double> yawSamples = new ArrayList<>();
    private final List<Double> pitchSamples = new ArrayList<>();

    public PlayerRotationData(WalrusPlayer player) {
        this.player = player;
        this.sensitivitySamples = new ArrayDeque<>();
        this.lastCinematic = new TimeMilistUtil(player);
    }

    public void handle(final float yaw, final float pitch) {
        this.lastYaw = this.yaw;
        this.lastPitch = this.pitch;
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastDeltaYaw = this.deltaYaw;
        this.lastDeltaPitch = this.deltaPitch;
        this.deltaYaw = Math.abs(yaw - this.lastYaw);
        this.deltaPitch = Math.abs(pitch - this.lastPitch);
        this.lastPitchAccel = this.pitchAccel;
        this.lastYawAccel = this.yawAccel;
        this.yawAccel = Math.abs(this.deltaYaw - this.lastDeltaYaw);
        this.pitchAccel = Math.abs(this.deltaPitch - this.lastDeltaPitch);
        final float f = (float) this.mcpSensitivity * 0.6f + 0.2f;
        final float gcd = f * f * f * 1.2f;
        this.rawMouseDeltaX = this.deltaYaw / gcd;
        this.rawMouseDeltaY = this.deltaPitch / gcd;
        this.mouseDeltaX = (int) (this.deltaYaw / gcd);
        this.mouseDeltaY = (int) (this.deltaPitch / gcd);
        final float expectedYaw = this.deltaYaw * 1.073742f + (float) (this.deltaYaw + 0.15);
        final float expectedPitch = this.deltaPitch * 1.073742f - (float) (this.deltaPitch - 0.15);
        final float pitchDiff = Math.abs(this.deltaPitch - expectedPitch);
        final float yawDiff = Math.abs(this.deltaYaw - expectedYaw);
        processCinematic();
        this.lastFuckedPredictedPitch = this.fuckedPredictedPitch;
        this.lastFuckedPredictedYaw = this.fuckedPredictedYaw;
        this.fuckedPredictedPitch = Math.abs(this.deltaPitch - pitchDiff);
        this.fuckedPredictedYaw = Math.abs(this.deltaYaw - yawDiff);
        this.smoothnessYaw = 10.0f - Math.abs(yawAccel - lastYawAccel);
        this.smoothnessPitch = 5.0f - Math.abs(pitchAccel - lastPitchAccel);
        this.smoothness = 10.0f - Math.abs(yawAccel - lastYawAccel);
        this.consistency = 1.0f - Math.abs(1.0f - (deltaYaw / (deltaPitch + 0.0001f)));
        this.dx = Math.abs(yaw - this.lastYaw);
        this.dy = Math.abs(pitch - this.lastPitch);
        this.ddx = Math.abs(this.dx - this.lastDx);
        this.ddy = Math.abs(this.dy - this.lastDy);
        this.dddx = Math.abs(this.ddx - this.lastDdx);
        this.dddy = Math.abs(this.ddy - this.lastDdy);
        if (this.deltaPitch > 0.1 && this.deltaPitch < 25.0f) {
            this.processSensitivity();
        }
    }

    private void processCinematic() {
        long now = System.currentTimeMillis();

        double differenceYaw = Math.abs(this.deltaYaw - this.lastDeltaYaw);
        double differencePitch = Math.abs(this.deltaPitch - this.lastDeltaPitch);
        double joltYaw = Math.abs(differenceYaw - this.deltaYaw);
        double joltPitch = Math.abs(differencePitch - this.deltaPitch);

        boolean cinematic = now - lastHighRate > 250L || now - lastSmooth < 9000L;

        if (joltYaw > 1.0 && joltPitch > 1.0) {
            lastHighRate = now;
        }

        yawSamples.add((double) this.deltaYaw);
        pitchSamples.add((double) this.deltaPitch);

        if (yawSamples.size() >= 20) {
            Set<Double> shannonYaw = new HashSet<>();
            Set<Double> shannonPitch = new HashSet<>();
            List<Double> stackYaw = new ArrayList<>();
            List<Double> stackPitch = new ArrayList<>();

            for (Double s : yawSamples) {
                stackYaw.add(s);
                if (stackYaw.size() >= 10) {
                    shannonYaw.add(calculateShannonEntropy(stackYaw));
                    stackYaw.clear();
                }
            }
            for (Double s : pitchSamples) {
                stackPitch.add(s);
                if (stackPitch.size() >= 10) {
                    shannonPitch.add(calculateShannonEntropy(stackPitch));
                    stackPitch.clear();
                }
            }

            if (shannonYaw.size() != 1 || shannonPitch.size() != 1
                    || !shannonYaw.toArray()[0].equals(shannonPitch.toArray()[0])) {
                isTotallyNotCinematic = 20;
            }

            int posYaw = 0, negYaw = 0, posPitch = 0, negPitch = 0;
            for (int i = 1; i < yawSamples.size(); i++) {
                double d = yawSamples.get(i) - yawSamples.get(i - 1);
                if (d > 0) posYaw++; else if (d < 0) negYaw++;
            }
            for (int i = 1; i < pitchSamples.size(); i++) {
                double d = pitchSamples.get(i) - pitchSamples.get(i - 1);
                if (d > 0) posPitch++; else if (d < 0) negPitch++;
            }

            if (posYaw > negYaw || posPitch > negPitch) {
                lastSmooth = now;
            }

            yawSamples.clear();
            pitchSamples.clear();
        }

        if (isTotallyNotCinematic > 0) {
            --isTotallyNotCinematic;
            this.cinematic = false;
        } else {
            this.cinematic = cinematic;
        }
    }

    private double calculateShannonEntropy(List<Double> data) {
        if (data.isEmpty()) return 0.0;
        Map<Double, Integer> freq = new HashMap<>();
        for (Double v : data) freq.put(v, freq.getOrDefault(v, 0) + 1);
        double entropy = 0.0;
        int total = data.size();
        for (Integer count : freq.values()) {
            double p = (double) count / total;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private void processSensitivity() {
        final double gcd = getGcd(this.deltaPitch, this.lastDeltaPitch);
        final double sensitivityModifier = Math.cbrt(0.8333 * gcd);
        final double sensitivityStepTwo = 1.666 * sensitivityModifier - 0.3333;
        final double finalSensitivity = sensitivityStepTwo * 200.0;
        this.finalSensitivity = finalSensitivity;
        this.sensitivitySamples.add((int) finalSensitivity);
        if (this.sensitivitySamples.size() == 40) {
            this.sensitivity = getMode(this.sensitivitySamples);
            if (this.hasValidSensitivity()) {
                this.mcpSensitivity = SensValues.SENSITIVITY_MCP_VALUES.get(this.sensitivity);
            }
            this.sensitivitySamples.clear();
        }
    }

    private static double getGcd(double a, double b) {
        if (a < b) {
            return getGcd(b, a);
        } else {
            return Math.abs(b) < 0.001D ? a : getGcd(b, a - Math.floor(a / b) * b);
        }
    }

    private static int getMode(ArrayDeque<Integer> samples) {
        int maxCount = 0;
        int mode = 0;
        for (int val : samples) {
            int count = 0;
            for (int other : samples) {
                if (other == val) count++;
            }
            if (count > maxCount) {
                maxCount = count;
                mode = val;
            }
        }
        return mode;
    }

    public boolean hasValidSensitivity() {
        return this.sensitivity > 0 && this.sensitivity < 200;
    }

    public boolean hasValidSensitivityNormalized() {
        return this.sensitivity > 0 && this.sensitivity < 269;
    }

    public boolean usingCinematicCamera() {
        return this.cinematic;
    }

    public boolean hasTooLowSensitivity() {
        return this.sensitivity >= 0 && this.sensitivity < 50;
    }

    public boolean hasTooZeroDelta() {
        return this.deltaYaw == 0 && this.deltaPitch == 0;
    }

    public float getYaw()                    { return yaw; }
    public float getPitch()                  { return pitch; }
    public float getLastYaw()                { return lastYaw; }
    public float getLastPitch()              { return lastPitch; }
    public float getDeltaYaw()               { return deltaYaw; }
    public float getDeltaPitch()             { return deltaPitch; }
    public float getLastDeltaYaw()           { return lastDeltaYaw; }
    public float getLastDeltaPitch()         { return lastDeltaPitch; }
    public float getYawAccel()               { return yawAccel; }
    public float getPitchAccel()             { return pitchAccel; }
    public float getLastYawAccel()           { return lastYawAccel; }
    public float getLastPitchAccel()         { return lastPitchAccel; }
    public float getRawMouseDeltaX()         { return rawMouseDeltaX; }
    public float getRawMouseDeltaY()         { return rawMouseDeltaY; }
    public float getFuckedPredictedPitch()   { return fuckedPredictedPitch; }
    public float getFuckedPredictedYaw()     { return fuckedPredictedYaw; }
    public float getLastFuckedPredictedPitch() { return lastFuckedPredictedPitch; }
    public float getLastFuckedPredictedYaw() { return lastFuckedPredictedYaw; }
    public boolean isInvalidRate()           { return invalidRate; }
    public boolean isInvalidSensitivity()    { return invalidSensitivity; }
    public boolean isCinematic()             { return cinematic; }
    public double getFinalSensitivity()      { return finalSensitivity; }
    public double getMcpSensitivity()        { return mcpSensitivity; }
    public int getSensitivity()              { return sensitivity; }
    public int getLastRate()                 { return lastRate; }
    public int getMouseDeltaX()              { return mouseDeltaX; }
    public int getMouseDeltaY()              { return mouseDeltaY; }
    public float getLastJoltYaw()            { return lastJoltYaw; }
    public float getJoltYaw()               { return joltYaw; }
    public float getJoltPitch()             { return joltPitch; }
    public float getDy()                     { return dy; }
    public float getDx()                     { return dx; }
    public float getDdy()                    { return ddy; }
    public float getDdx()                    { return ddx; }
    public float getDddy()                   { return dddy; }
    public float getDddx()                   { return dddx; }
    public float getSmoothness()             { return smoothness; }
    public float getConsistency()            { return consistency; }
    public float getSmoothnessPitch()        { return smoothnessPitch; }
    public float getSmoothnessYaw()          { return smoothnessYaw; }
}