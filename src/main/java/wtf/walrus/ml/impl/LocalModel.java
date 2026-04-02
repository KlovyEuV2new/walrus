package wtf.walrus.ml.impl;

import wtf.walrus.data.TickData;
import wtf.walrus.ml.Model;

import java.io.*;
import java.util.*;

public class LocalModel extends Model {

    public static final int version = 43;
    public static final int IN = 34;

    private final int BATCH_SIZE = 32;

    private static final double LR       = 0.0001; // 0.001
    private static final double BETA1    = 0.9;
    private static final double BETA2    = 0.999;
    private static final double EPS      = 1e-8;
    private static final double LAMBDA   = 1e-4;
    private static final double FB_BETA  = 0.5;
    private static final int    PATIENCE = 20;

    private double[][] w1   = new double[IN][32];
    private double[]   b1   = new double[32];
    private double[][] w2   = new double[32][16];
    private double[]   b2   = new double[16];
    private double[][] w3   = new double[16][8];
    private double[]   b3   = new double[8];
    private double[]   wOut = new double[8];
    private double     bOut = 0.0;

    private double[][] mW1   = new double[IN][32];
    private double[]   mB1   = new double[32];
    private double[][] mW2   = new double[32][16];
    private double[]   mB2   = new double[16];
    private double[][] mW3   = new double[16][8];
    private double[]   mB3   = new double[8];
    private double[]   mWOut = new double[8];
    private double     mBOut = 0.0;

    private double[][] vW1   = new double[IN][32];
    private double[]   vB1   = new double[32];
    private double[][] vW2   = new double[32][16];
    private double[]   vB2   = new double[16];
    private double[][] vW3   = new double[16][8];
    private double[]   vB3   = new double[8];
    private double[]   vWOut = new double[8];
    private double     vBOut = 0.0;

    private int adamStep = 0;

    private double[] featMean = new double[IN];
    private double[] featStd  = new double[IN];
    private boolean  normReady = false;

    private final java.util.logging.Logger logger;
    private static final Random rnd = new Random();

    public LocalModel(java.util.logging.Logger logger) {
        super("local");
        this.logger = logger;
    }

    @Override
    public double predict(List<TickData> ticks) {
        if (ticks == null || ticks.size() < 10) return 0.0;
        double[] f = extractFeatures(ticks);
        if (f == null) return 0.0;
        return predictFromFeatures(f);
    }

    @Override
    public double predictFromFeatures(double[] raw) {
        double[] x = normalise(raw);

        double[] h1 = new double[32];
        for (int j = 0; j < 32; j++) {
            double s = b1[j];
            for (int i = 0; i < IN; i++) s += x[i] * w1[i][j];
            h1[j] = relu(s);
        }

        double[] h2 = new double[16];
        for (int j = 0; j < 16; j++) {
            double s = b2[j];
            for (int i = 0; i < 32; i++) s += h1[i] * w2[i][j];
            h2[j] = relu(s);
        }

        double[] h3 = new double[8];
        for (int j = 0; j < 8; j++) {
            double s = b3[j];
            for (int i = 0; i < 16; i++) s += h2[i] * w3[i][j];
            h3[j] = relu(s);
        }

        double out = bOut;
        for (int i = 0; i < 8; i++) out += h3[i] * wOut[i];
        return sigmoid(out);
    }

    @Override
    public void trainBatch(List<double[]> featuresList, List<Double> labels, int epochs) {
        if (featuresList.isEmpty()) return;

        if (!normReady) fitNormaliser(featuresList);
        if (!isTrained()) { initWeights(new Random(1)); resetAdam(); }

        int size     = featuresList.size();
        int splitIdx = Math.max(1, (int) (size * 0.8));

        List<double[]> trainFeatures = featuresList.subList(0, splitIdx);
        List<Double>   trainLabels   = labels.subList(0, splitIdx);
        List<double[]> valFeatures   = featuresList.subList(splitIdx, size);
        List<Double>   valLabels     = labels.subList(splitIdx, size);

        int[] indices = new int[trainFeatures.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;

        double[] x   = new double[IN];
        double[] h1  = new double[32]; double[] z1 = new double[32];
        double[] h2  = new double[16]; double[] z2 = new double[16];
        double[] h3  = new double[8];  double[] z3 = new double[8];
        double[] dH3 = new double[8];
        double[] dH2 = new double[16];
        double[] dH1 = new double[32];

        double[][] bestW1   = new double[IN][32];
        double[][] bestW2   = new double[32][16];
        double[][] bestW3   = new double[16][8];
        double[]   bestWOut = new double[8];
        double[]   bestB1   = new double[32];
        double[]   bestB2   = new double[16];
        double[]   bestB3   = new double[8];
        double     bestBOut = bOut;

        double bestValLoss   = Double.MAX_VALUE;
        int    noImprovCount = 0;
        int    bestEpoch     = 0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            shuffleArray(indices, rnd);

            for (int batchStart = 0; batchStart < trainFeatures.size(); batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + BATCH_SIZE, trainFeatures.size());
                int batchSize = batchEnd - batchStart;

                double[] bOutGradBatch = new double[1];
                double[] wOutGradBatch = new double[8];
                double[] b1GradBatch = new double[32];
                double[] b2GradBatch = new double[16];
                double[] b3GradBatch = new double[8];
                double[][] w1GradBatch = new double[IN][32];
                double[][] w2GradBatch = new double[32][16];
                double[][] w3GradBatch = new double[16][8];

                for (int idxPtr = batchStart; idxPtr < batchEnd; idxPtr++) {
                    int idx = indices[idxPtr];
                    double[] raw = trainFeatures.get(idx);
                    for (int i = 0; i < IN; i++) {
                        double std = featStd[i] < 1e-8 ? 1.0 : featStd[i];
                        x[i] = (raw[i] - featMean[i]) / std;
                    }
                    double y = trainLabels.get(idx);

                    for (int j = 0; j < 32; j++) { double s = b1[j]; for (int i = 0; i < IN; i++) s += x[i] * w1[i][j]; z1[j] = s; h1[j] = relu(s); }
                    for (int j = 0; j < 16; j++) { double s = b2[j]; for (int i = 0; i < 32; i++) s += h1[i] * w2[i][j]; z2[j] = s; h2[j] = relu(s); }
                    for (int j = 0; j < 8; j++)  { double s = b3[j]; for (int i = 0; i < 16; i++) s += h2[i] * w3[i][j]; z3[j] = s; h3[j] = relu(s); }
                    double out = bOut; for (int i = 0; i < 8; i++) out += h3[i] * wOut[i];
                    double p    = sigmoid(out);
                    double dOut = p - y;

                    bOutGradBatch[0] += dOut;
                    for (int i = 0; i < 8; i++) wOutGradBatch[i] += dOut * h3[i];

                    for (int i = 0; i < 8; i++) dH3[i] = dOut * wOut[i] * reluDeriv(z3[i]);
                    Arrays.fill(dH2, 0.0);
                    for (int i = 0; i < 8; i++) for (int j = 0; j < 16; j++) dH2[j] += dH3[i] * w3[j][i];
                    for (int i = 0; i < 16; i++) dH2[i] *= reluDeriv(z2[i]);
                    Arrays.fill(dH1, 0.0);
                    for (int i = 0; i < 16; i++) for (int j = 0; j < 32; j++) dH1[j] += dH2[i] * w2[j][i];
                    for (int i = 0; i < 32; i++) dH1[i] *= reluDeriv(z1[i]);

                    for (int i = 0; i < 32; i++) { b1GradBatch[i] += dH1[i]; for (int j = 0; j < IN; j++) w1GradBatch[j][i] += dH1[i] * x[j]; }
                    for (int i = 0; i < 16; i++) { b2GradBatch[i] += dH2[i]; for (int j = 0; j < 32; j++) w2GradBatch[j][i] += dH2[i] * h1[j]; }
                    for (int i = 0; i < 8; i++)  { b3GradBatch[i] += dH3[i]; for (int j = 0; j < 16; j++) w3GradBatch[j][i] += dH3[i] * h2[j]; }
                }

                double invBatch = 1.0 / batchSize;
                bOutGradBatch[0] *= invBatch;
                for (int i = 0; i < 8; i++) wOutGradBatch[i] *= invBatch;
                for (int i = 0; i < 32; i++) { b1GradBatch[i] *= invBatch; for (int j = 0; j < IN; j++) w1GradBatch[j][i] *= invBatch; }
                for (int i = 0; i < 16; i++) { b2GradBatch[i] *= invBatch; for (int j = 0; j < 32; j++) w2GradBatch[j][i] *= invBatch; }
                for (int i = 0; i < 8; i++)  { b3GradBatch[i] *= invBatch; for (int j = 0; j < 16; j++) w3GradBatch[j][i] *= invBatch; }

                adamStep++;
                double bc1 = 1.0 - Math.pow(BETA1, adamStep);
                double bc2 = 1.0 - Math.pow(BETA2, adamStep);

                mBOut = BETA1 * mBOut + (1 - BETA1) * bOutGradBatch[0];
                vBOut = BETA2 * vBOut + (1 - BETA2) * bOutGradBatch[0]*bOutGradBatch[0];
                bOut -= LR * (mBOut/bc1) / (Math.sqrt(vBOut/bc2)+EPS);

                for (int i = 0; i < 8; i++) {
                    double gw = wOutGradBatch[i] + LAMBDA*wOut[i];
                    mWOut[i] = BETA1*mWOut[i] + (1-BETA1)*gw;
                    vWOut[i] = BETA2*vWOut[i] + (1-BETA2)*gw*gw;
                    wOut[i] -= LR*(mWOut[i]/bc1)/(Math.sqrt(vWOut[i]/bc2)+EPS);
                }

                for (int i = 0; i < 32; i++) { mB1[i]=BETA1*mB1[i]+(1-BETA1)*b1GradBatch[i]; vB1[i]=BETA2*vB1[i]+(1-BETA2)*b1GradBatch[i]*b1GradBatch[i]; b1[i]-=LR*(mB1[i]/bc1)/(Math.sqrt(vB1[i]/bc2)+EPS);
                    for (int j = 0; j < IN; j++) { double gw=w1GradBatch[j][i]+LAMBDA*w1[j][i]; mW1[j][i]=BETA1*mW1[j][i]+(1-BETA1)*gw; vW1[j][i]=BETA2*vW1[j][i]+(1-BETA2)*gw*gw; w1[j][i]-=LR*(mW1[j][i]/bc1)/(Math.sqrt(vW1[j][i]/bc2)+EPS); } }
                for (int i = 0; i < 16; i++) { mB2[i]=BETA1*mB2[i]+(1-BETA1)*b2GradBatch[i]; vB2[i]=BETA2*vB2[i]+(1-BETA2)*b2GradBatch[i]*b2GradBatch[i]; b2[i]-=LR*(mB2[i]/bc1)/(Math.sqrt(vB2[i]/bc2)+EPS);
                    for (int j = 0; j < 32; j++) { double gw=w2GradBatch[j][i]+LAMBDA*w2[j][i]; mW2[j][i]=BETA1*mW2[j][i]+(1-BETA1)*gw; vW2[j][i]=BETA2*vW2[j][i]+(1-BETA2)*gw*gw; w2[j][i]-=LR*(mW2[j][i]/bc1)/(Math.sqrt(vW2[j][i]/bc2)+EPS); } }
                for (int i = 0; i < 8; i++)  { mB3[i]=BETA1*mB3[i]+(1-BETA1)*b3GradBatch[i]; vB3[i]=BETA2*vB3[i]+(1-BETA2)*b3GradBatch[i]*b3GradBatch[i]; b3[i]-=LR*(mB3[i]/bc1)/(Math.sqrt(vB3[i]/bc2)+EPS);
                    for (int j = 0; j < 16; j++) { double gw=w3GradBatch[j][i]+LAMBDA*w3[j][i]; mW3[j][i]=BETA1*mW3[j][i]+(1-BETA1)*gw; vW3[j][i]=BETA2*vW3[j][i]+(1-BETA2)*gw*gw; w3[j][i]-=LR*(mW3[j][i]/bc1)/(Math.sqrt(vW3[j][i]/bc2)+EPS); } }
            }

            if (!valFeatures.isEmpty()) {
                double valLoss = computeLoss(valFeatures, valLabels);
                if (valLoss < bestValLoss - 1e-6) {
                    bestValLoss   = valLoss;
                    noImprovCount = 0;
                    bestEpoch     = epoch + 1;
                    snapshotWeights(bestW1, bestW2, bestW3, bestWOut, bestB1, bestB2, bestB3);
                    bestBOut = bOut;
                } else if (++noImprovCount >= PATIENCE) {
                    logger.info("[LocalModel] Early stopping at epoch " + (epoch + 1)
                            + ", best epoch=" + bestEpoch);
                    break;
                }
            }
        }

        if (!valFeatures.isEmpty() && bestEpoch > 0) {
            restoreWeights(bestW1, bestW2, bestW3, bestWOut, bestB1, bestB2, bestB3);
            bOut = bestBOut;
        }

        double bestThreshold = findOptimalThreshold(featuresList, labels);
        setOptimalThreshold(bestThreshold);
        setTrained(true);

        logger.info("[LocalModel] Training finished. Adam lr=" + LR + " FB_BETA=" + FB_BETA);
    }

    private void resetAdam() {
        adamStep = 0; mBOut = 0.0; vBOut = 0.0;
        for (int i = 0; i < IN; i++)  Arrays.fill(mW1[i], 0.0);
        for (int i = 0; i < 32; i++) Arrays.fill(mW2[i], 0.0);
        for (int i = 0; i < 16; i++) Arrays.fill(mW3[i], 0.0);
        Arrays.fill(mB1, 0.0); Arrays.fill(mB2, 0.0); Arrays.fill(mB3, 0.0); Arrays.fill(mWOut, 0.0);
        for (int i = 0; i < IN; i++)  Arrays.fill(vW1[i], 0.0);
        for (int i = 0; i < 32; i++) Arrays.fill(vW2[i], 0.0);
        for (int i = 0; i < 16; i++) Arrays.fill(vW3[i], 0.0);
        Arrays.fill(vB1, 0.0); Arrays.fill(vB2, 0.0); Arrays.fill(vB3, 0.0); Arrays.fill(vWOut, 0.0);
    }

    private void snapshotWeights(double[][] bW1, double[][] bW2, double[][] bW3,
                                 double[] bWOut, double[] bB1, double[] bB2, double[] bB3) {
        for (int i = 0; i < IN; i++) System.arraycopy(w1[i], 0, bW1[i], 0, 32);
        for (int i = 0; i < 32; i++) System.arraycopy(w2[i], 0, bW2[i], 0, 16);
        for (int i = 0; i < 16; i++) System.arraycopy(w3[i], 0, bW3[i], 0, 8);
        System.arraycopy(wOut, 0, bWOut, 0, 8);
        System.arraycopy(b1,   0, bB1,   0, 32);
        System.arraycopy(b2,   0, bB2,   0, 16);
        System.arraycopy(b3,   0, bB3,   0, 8);
    }

    private void restoreWeights(double[][] bW1, double[][] bW2, double[][] bW3,
                                double[] bWOut, double[] bB1, double[] bB2, double[] bB3) {
        for (int i = 0; i < IN; i++) System.arraycopy(bW1[i], 0, w1[i], 0, 32);
        for (int i = 0; i < 32; i++) System.arraycopy(bW2[i], 0, w2[i], 0, 16);
        for (int i = 0; i < 16; i++) System.arraycopy(bW3[i], 0, w3[i], 0, 8);
        System.arraycopy(bWOut, 0, wOut, 0, 8);
        System.arraycopy(bB1,   0, b1,   0, 32);
        System.arraycopy(bB2,   0, b2,   0, 16);
        System.arraycopy(bB3,   0, b3,   0, 8);
    }

    private double computeLoss(List<double[]> featuresList, List<Double> labels)
    {
        if (featuresList.size() != labels.size()) {
            throw new IllegalArgumentException("Features/labels size mismatch");
        }
        double loss = 0.0;
        for (int i = 0; i < featuresList.size(); i++) {
            double p = predictFromFeatures(featuresList.get(i));
            p = Math.max(1e-15, Math.min(1.0 - 1e-15, p));
            double y = labels.get(i);
            loss += -(y * Math.log(p) + (1.0 - y) * Math.log(1.0 - p));
        }
        return loss / featuresList.size();
    }

    private double findOptimalThreshold(List<double[]> featuresList, List<Double> labels) {
        if (featuresList.isEmpty()) return 0.5;
        double bestThreshold = 0.5, bestScore = -1.0;
        double betaSq = FB_BETA * FB_BETA;

        for (int t = 40; t <= 95; t++) {
            double threshold = t / 100.0;
            int tp = 0, fp = 0, fn = 0;
            for (int i = 0; i < featuresList.size(); i++) {
                double  pred    = predictFromFeatures(featuresList.get(i));
                boolean predPos = pred >= threshold;
                boolean actPos  = labels.get(i) >= 0.5;
                if (predPos && actPos) tp++;
                else if (predPos)      fp++;
                else if (actPos)       fn++;
            }
            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall    = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
            double score     = (precision + recall) < 1e-8 ? 0.0
                    : (1 + betaSq) * precision * recall / (betaSq * precision + recall);
            if (score > bestScore) { bestScore = score; bestThreshold = threshold; }
        }

        logger.info("[LocalModel] Best threshold=" + bestThreshold
                + " F-beta(b=" + FB_BETA + ")=" + bestScore);
        return bestThreshold;
    }

    @Override
    public double[] extractFeatures(List<TickData> ticks) {
        int n = ticks.size();
        if (n < 2) return null;

        double[] dY = new double[n]; double[] dP = new double[n];
        double[] aY = new double[n]; double[] aP = new double[n];
        double[] jY = new double[n]; double[] jP = new double[n];

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            TickData t = ticks.get(i);
            dY[i] = t.deltaYaw;   dP[i] = t.deltaPitch;
            aY[i] = t.accelYaw;   aP[i] = t.accelPitch;
            jY[i] = t.jerkYaw;    jP[i] = t.jerkPitch;
            if (dY[i] < minY) minY = dY[i]; if (dY[i] > maxY) maxY = dY[i];
            if (dP[i] < minP) minP = dP[i]; if (dP[i] > maxP) maxP = dP[i];
        }

        double[] f = new double[IN];
        int idx = 0;

        double meanY = mean(dY), stdY = std(dY, meanY);
        f[idx++] = meanY;
        f[idx++] = stdY;
        f[idx++] = meanAbs(dY);
        f[idx++] = stdY > 1e-8 ? (fastPct(dY, 75) - fastPct(dY, 25)) / stdY : 0.0;
        f[idx++] = entropy(dY, minY, maxY);
        f[idx++] = skewness(dY, meanY);
        f[idx++] = kurtosis(dY, meanY);
        f[idx++] = netDispRatio(dY);
        f[idx++] = signChangeRate(dY);
        f[idx++] = maxAbs(dY);

        double meanP = mean(dP), stdP = std(dP, meanP);
        f[idx++] = meanP;
        f[idx++] = stdP;
        f[idx++] = meanAbs(dP);
        f[idx++] = stdP > 1e-8 ? (fastPct(dP, 75) - fastPct(dP, 25)) / stdP : 0.0;
        f[idx++] = entropy(dP, minP, maxP);
        f[idx++] = skewness(dP, meanP);
        f[idx++] = kurtosis(dP, meanP);
        f[idx++] = netDispRatio(dP);
        f[idx++] = signChangeRate(dP);
        f[idx++] = maxAbs(dP);

        f[idx++] = pearson(dY, dP);

        double eY = energy(dY), eP = energy(dP);
        f[idx++] = eP > 1e-8 ? eY / eP : 1.0;

        double mAbsP = meanAbs(dP);
        f[idx++] = mAbsP > 1e-8 ? meanAbs(dY) / mAbsP : 1.0;

        double meanJY = mean(jY), meanJP = mean(jP);
        f[idx++] = std(jY, meanJY) + std(jP, meanJP);

        double meanAY = mean(aY), meanAP = mean(aP);
        f[idx++] = std(aY, meanAY) + std(aP, meanAP);

        f[idx++] = autocorr(dY, 1);
        f[idx++] = autocorr(dP, 1);
        f[idx++] = maxRunLength(dY);
        f[idx++] = maxRunLength(dP);
        f[idx++] = highFreqEnergy(dY);
        f[idx++] = highFreqEnergy(dP);
        f[idx++] = accelSymmetry(dY);
        f[idx++] = accelSymmetry(dP);
        f[idx++] = crossCorrLag(dY, dP);

        return f;
    }

    @Override
    public void save(File file) throws IOException {
        file.getParentFile().mkdirs();
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            dos.writeInt(version);
            dos.writeBoolean(isTrained());
            dos.writeBoolean(normReady);
            dos.writeDouble(getOptimalThreshold());
            dos.writeInt(adamStep);

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) dos.writeDouble(w1[i][j]);
            for (double v : b1) dos.writeDouble(v);
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) dos.writeDouble(w2[i][j]);
            for (double v : b2) dos.writeDouble(v);
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) dos.writeDouble(w3[i][j]);
            for (double v : b3) dos.writeDouble(v);
            for (double v : wOut) dos.writeDouble(v);
            dos.writeDouble(bOut);
            for (double m : featMean) dos.writeDouble(m);
            for (double s : featStd)  dos.writeDouble(s);

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) dos.writeDouble(mW1[i][j]);
            for (double v : mB1) dos.writeDouble(v);
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) dos.writeDouble(mW2[i][j]);
            for (double v : mB2) dos.writeDouble(v);
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) dos.writeDouble(mW3[i][j]);
            for (double v : mB3) dos.writeDouble(v);
            for (double v : mWOut) dos.writeDouble(v);
            dos.writeDouble(mBOut);

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) dos.writeDouble(vW1[i][j]);
            for (double v : vB1) dos.writeDouble(v);
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) dos.writeDouble(vW2[i][j]);
            for (double v : vB2) dos.writeDouble(v);
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) dos.writeDouble(vW3[i][j]);
            for (double v : vB3) dos.writeDouble(v);
            for (double v : vWOut) dos.writeDouble(v);
            dos.writeDouble(vBOut);
        }
    }

    @Override
    public boolean load(File file) {
        if (!file.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (dis.readInt() != version) return false;
            setTrained(dis.readBoolean());
            normReady = dis.readBoolean();
            setOptimalThreshold(dis.readDouble());
            adamStep = dis.readInt();

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) w1[i][j] = dis.readDouble();
            for (int i = 0; i < 32; i++) b1[i] = dis.readDouble();
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) w2[i][j] = dis.readDouble();
            for (int i = 0; i < 16; i++) b2[i] = dis.readDouble();
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) w3[i][j] = dis.readDouble();
            for (int i = 0; i < 8; i++)  b3[i]   = dis.readDouble();
            for (int i = 0; i < 8; i++)  wOut[i]  = dis.readDouble();
            bOut = dis.readDouble();
            for (int i = 0; i < IN; i++) featMean[i] = dis.readDouble();
            for (int i = 0; i < IN; i++) featStd[i]  = dis.readDouble();

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) mW1[i][j] = dis.readDouble();
            for (int i = 0; i < 32; i++) mB1[i] = dis.readDouble();
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) mW2[i][j] = dis.readDouble();
            for (int i = 0; i < 16; i++) mB2[i] = dis.readDouble();
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) mW3[i][j] = dis.readDouble();
            for (int i = 0; i < 8; i++)  mB3[i]   = dis.readDouble();
            for (int i = 0; i < 8; i++)  mWOut[i]  = dis.readDouble();
            mBOut = dis.readDouble();

            for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) vW1[i][j] = dis.readDouble();
            for (int i = 0; i < 32; i++) vB1[i] = dis.readDouble();
            for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) vW2[i][j] = dis.readDouble();
            for (int i = 0; i < 16; i++) vB2[i] = dis.readDouble();
            for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++) vW3[i][j] = dis.readDouble();
            for (int i = 0; i < 8; i++)  vB3[i]   = dis.readDouble();
            for (int i = 0; i < 8; i++)  vWOut[i]  = dis.readDouble();
            vBOut = dis.readDouble();

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private double[] normalise(double[] f) {
        double[] n = new double[IN];
        if (!normReady) { System.arraycopy(f, 0, n, 0, IN); return n; }
        for (int i = 0; i < IN; i++) {
            double std = featStd[i] < 1e-8 ? 1.0 : featStd[i];
            n[i] = (f[i] - featMean[i]) / std;
        }
        return n;
    }

    private void fitNormaliser(List<double[]> all) {
        int n = all.size();
        Arrays.fill(featMean, 0);
        for (double[] f : all) for (int i = 0; i < IN; i++) featMean[i] += f[i];
        for (int i = 0; i < IN; i++) featMean[i] /= n;
        Arrays.fill(featStd, 0);
        for (double[] f : all) for (int i = 0; i < IN; i++) { double d = f[i] - featMean[i]; featStd[i] += d * d; }
        for (int i = 0; i < IN; i++) { featStd[i] = Math.sqrt(featStd[i] / n); if (featStd[i] < 1e-8) featStd[i] = 1.0; }
        normReady = true;
    }

    private void initWeights(Random r) {
        double s1 = Math.sqrt(2.0 / IN);
        for (int i = 0; i < IN; i++) for (int j = 0; j < 32; j++) w1[i][j] = (r.nextDouble() - 0.5) * 2.0 * s1;
        double s2 = Math.sqrt(2.0 / 32);
        for (int i = 0; i < 32; i++) for (int j = 0; j < 16; j++) w2[i][j] = (r.nextDouble() - 0.5) * 2.0 * s2;
        double s3 = Math.sqrt(2.0 / 16);
        for (int i = 0; i < 16; i++) for (int j = 0; j < 8; j++)  w3[i][j] = (r.nextDouble() - 0.5) * 2.0 * s3;
        double sO = Math.sqrt(2.0 / 8);
        for (int i = 0; i < 8; i++) wOut[i] = (r.nextDouble() - 0.5) * 2.0 * sO;
    }

    private void shuffleArray(int[] arr, Random r) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1); int tmp = arr[j]; arr[j] = arr[i]; arr[i] = tmp;
        }
    }

    private double clip(double g)      { return Math.max(-5, Math.min(5, g)); }
    private double relu(double x)      { return x > 0 ? x : 0; }
    private double reluDeriv(double x) { return x > 0 ? 1 : 0; }
    private double sigmoid(double x) {
        if (x > 20) return 1.0;
        if (x < -20) return 0.0;
        return 1.0 / (1.0 + Math.exp(-x));
    }
    private double mean(double[] v)    { double s = 0; for (double d : v) s += d; return s / v.length; }
    private double meanAbs(double[] v) { double s = 0; for (double d : v) s += Math.abs(d); return s / v.length; }
    private double std(double[] v, double m) { double s = 0; for (double d : v) s += (d-m)*(d-m); return Math.sqrt(s/v.length); }
    private double maxAbs(double[] v)  { double m = 0; for (double d : v) m = Math.max(m, Math.abs(d)); return m; }
    private double energy(double[] v)  { double e = 0; for (double d : v) e += d*d; return e; }

    private double netDispRatio(double[] v) {
        double net = 0, tot = 0;
        for (double d : v) { net += d; tot += Math.abs(d); }
        return tot < 1e-8 ? 0 : Math.abs(net) / tot;
    }

    private double signChangeRate(double[] v) {
        if (v.length < 2) return 0;
        int c = 0;
        for (int i = 1; i < v.length; i++) if ((v[i] >= 0) != (v[i-1] >= 0)) c++;
        return (double) c / (v.length - 1);
    }

    private double pearson(double[] x, double[] y) {
        double mx = mean(x), my = mean(y), num = 0, dx = 0, dy = 0;
        for (int i = 0; i < x.length; i++) {
            double ex = x[i]-mx, ey = y[i]-my;
            num += ex*ey; dx += ex*ex; dy += ey*ey;
        }
        double d = Math.sqrt(dx*dy);
        return d < 1e-10 ? 0 : num/d;
    }

    private double fastPct(double[] input, double percentile) {
        double[] v = Arrays.copyOf(input, input.length);
        int n = v.length, k = Math.min(Math.max((int) Math.ceil(percentile/100.0*n)-1, 0), n-1);
        int left = 0, right = n-1;
        while (left < right) {
            int pp = partition(v, left, right, left + rnd.nextInt(right-left+1));
            if (pp == k) return v[pp]; else if (pp < k) left = pp+1; else right = pp-1;
        }
        return v[left];
    }

    private int partition(double[] v, int left, int right, int pivotIndex) {
        double pv = v[pivotIndex]; double t = v[pivotIndex]; v[pivotIndex] = v[right]; v[right] = t;
        int si = left;
        for (int i = left; i < right; i++) if (v[i] < pv) { t = v[si]; v[si] = v[i]; v[i] = t; si++; }
        t = v[right]; v[right] = v[si]; v[si] = t;
        return si;
    }

    private double entropy(double[] v, double min, double max) {
        int bins = Math.max(4, (int) Math.round(Math.sqrt(v.length)));
        if (max-min < 1e-10) return 0;
        int[] h = new int[bins]; double range = max-min;
        for (double d : v) { int b = (int)((d-min)/range*(bins-1)); if (b<0) b=0; if (b>=bins) b=bins-1; h[b]++; }
        double e = 0, nInv = 1.0/v.length;
        for (int c : h) if (c > 0) { double p = c*nInv; e -= p*Math.log(p); }
        return e;
    }

    private double skewness(double[] v, double m) {
        double s = std(v, m); if (s < 1e-8) return 0;
        double sum = 0; for (double x : v) { double z=(x-m)/s; sum+=z*z*z; } return sum/v.length;
    }

    private double kurtosis(double[] v, double m) {
        double s = std(v, m); if (s < 1e-8) return 0;
        double sum = 0; for (double x : v) { double z=(x-m)/s; sum+=z*z*z*z; } return sum/v.length-3.0;
    }

    private double autocorr(double[] v, int lag) {
        if (v.length <= lag) return 0;
        double m = mean(v), num = 0, den = 0;
        for (int i = lag; i < v.length; i++) num += (v[i]-m)*(v[i-lag]-m);
        for (double x : v) den += (x-m)*(x-m);
        return den < 1e-10 ? 0 : num/den;
    }

    private double maxRunLength(double[] v) {
        if (v.length == 0) return 0;
        int maxRun = 1, cur = 1;
        for (int i = 1; i < v.length; i++) { if ((v[i]>=0)==(v[i-1]>=0)) cur++; else cur=1; if (cur>maxRun) maxRun=cur; }
        return (double) maxRun / v.length;
    }

    private double highFreqEnergy(double[] v) {
        if (v.length < 3) return 0;
        double e = 0;
        for (int i = 1; i < v.length-1; i++) { double d=v[i+1]-2*v[i]+v[i-1]; e+=d*d; }
        return e / v.length;
    }

    private double accelSymmetry(double[] v) {
        if (v.length < 4) return 0;
        int half = v.length/2; double sumDiff = 0;
        for (int i = 0; i < half; i++) sumDiff += Math.abs(Math.abs(v[i])-Math.abs(v[v.length-1-i]));
        return sumDiff/half;
    }

    private double crossCorrLag(double[] x, double[] y) {
        double bestCorr = -1; int bestLag = 0, maxLag = Math.min(5, x.length/4);
        double mx = mean(x), my = mean(y);
        for (int lag = 0; lag <= maxLag; lag++) {
            double num = 0, dx = 0, dy = 0;
            for (int i = lag; i < x.length; i++) {
                num += (x[i]-mx)*(y[i-lag]-my);
                dx  += (x[i]-mx)*(x[i]-mx);
                dy  += (y[i-lag]-my)*(y[i-lag]-my);
            }
            double corr = Math.sqrt(dx*dy) < 1e-10 ? 0 : Math.abs(num/Math.sqrt(dx*dy));
            if (corr > bestCorr) { bestCorr = corr; bestLag = lag; }
        }
        return bestLag;
    }
}