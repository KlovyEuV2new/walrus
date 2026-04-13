package wtf.walrus.ml.impl;

import wtf.walrus.data.TickData;
import wtf.walrus.ml.MLOut;
import wtf.walrus.ml.Model;

import java.io.*;
import java.util.*;

public class LocalModel extends Model {

    public static final int version = 44;

    public static final int IN;

    static {
        TickData dummy = new TickData(0,0,0,0,0,0,0,0);
        List<TickData> probe = List.of(dummy, dummy);
        IN = new LocalModel(null).extractFeaturesInternal(probe).size();
    }

    private final int BATCH_SIZE = 32;

    private static final double LR       = 0.0001;
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

    private double[][] mW1   = new double[IN][32];  private double[][] vW1 = new double[IN][32];
    private double[]   mB1   = new double[32];       private double[]   vB1 = new double[32];
    private double[][] mW2   = new double[32][16];  private double[][] vW2 = new double[32][16];
    private double[]   mB2   = new double[16];       private double[]   vB2 = new double[16];
    private double[][] mW3   = new double[16][8];   private double[][] vW3 = new double[16][8];
    private double[]   mB3   = new double[8];        private double[]   vB3 = new double[8];
    private double[]   mWOut = new double[8];        private double[]   vWOut = new double[8];
    private double     mBOut = 0.0;                  private double     vBOut = 0.0;

    private int adamStep = 0;

    private double[] featMean  = new double[IN];
    private double[] featStd   = new double[IN];
    private boolean  normReady = false;

    private final java.util.logging.Logger logger;
    private static final Random rnd = new Random();

    public LocalModel(java.util.logging.Logger logger) {
        super("local");
        this.logger = logger;
    }


    @Override
    public MLOut predict(List<TickData> ticks) {
        if (ticks == null || ticks.isEmpty()) return new MLOut(0.0, new String[]{"unknown"});

        NamedFeatureBuilder builder = extractFeaturesInternal(ticks);
        if (builder == null) return new MLOut(0.0, new String[]{"unknown"});

        double[] f      = builder.toArray();
        String[] fNames = builder.names();

        double[] contrib = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < w1[i].length; j++) sum += Math.abs(f[i] * w1[i][j]);
            contrib[i] = sum;
        }

        Integer[] indices = new Integer[f.length];
        for (int i = 0; i < f.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(contrib[b], contrib[a]));

        int topN = f.length;
        String[] bestNames = new String[topN];
        for (int i = 0; i < topN; i++) {
            bestNames[i] = fNames[indices[i]].toLowerCase();
        }

        double pf = predictFromFeatures(f);
        return new MLOut(pf, bestNames);
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

    public String[] getFeatureNames(List<TickData> ticks) {
        NamedFeatureBuilder b = extractFeaturesInternal(ticks);
        return b != null ? b.names() : new String[0];
    }

    @Override
    public double[] extractFeatures(List<TickData> ticks) {
        NamedFeatureBuilder b = extractFeaturesInternal(ticks);
        return b != null ? b.toArray() : null;
    }

    private NamedFeatureBuilder extractFeaturesInternal(List<TickData> ticks) {
        int n = ticks.size();
        if (n < 2) return null;

        double[] dY = new double[n], dP = new double[n];
        double[] aY = new double[n], aP = new double[n];
        double[] jY = new double[n], jP = new double[n];

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            TickData t = ticks.get(i);
            dY[i] = t.deltaYaw;  dP[i] = t.deltaPitch;
            aY[i] = t.accelYaw;  aP[i] = t.accelPitch;
            jY[i] = t.jerkYaw;   jP[i] = t.jerkPitch;
            if (dY[i] < minY) minY = dY[i]; if (dY[i] > maxY) maxY = dY[i];
            if (dP[i] < minP) minP = dP[i]; if (dP[i] > maxP) maxP = dP[i];
        }

        double meanY = mean(dY), stdY = std(dY, meanY);
        double meanP = mean(dP), stdP = std(dP, meanP);

        NamedFeatureBuilder b = new NamedFeatureBuilder();

        b.add("meanYaw",       meanY);
        b.add("stdYaw",        stdY);
        b.add("meanAbsYaw",    meanAbs(dY));
        b.add("iqrNormYaw",    stdY > 1e-8 ? (fastPct(dY, 75) - fastPct(dY, 25)) / stdY : 0.0);
        b.add("entropyYaw",    entropy(dY, minY, maxY));
        b.add("skewnessYaw",   skewness(dY, meanY));
        b.add("kurtosisYaw",   kurtosis(dY, meanY));
        b.add("netDispYaw",    netDispRatio(dY));
        b.add("signChangeYaw", signChangeRate(dY));
        b.add("maxAbsYaw",     maxAbs(dY));

        b.add("meanPitch",       meanP);
        b.add("stdPitch",        stdP);
        b.add("meanAbsPitch",    meanAbs(dP));
        b.add("iqrNormPitch",    stdP > 1e-8 ? (fastPct(dP, 75) - fastPct(dP, 25)) / stdP : 0.0);
        b.add("entropyPitch",    entropy(dP, minP, maxP));
        b.add("skewnessPitch",   skewness(dP, meanP));
        b.add("kurtosis Pitch",  kurtosis(dP, meanP));
        b.add("netDispPitch",    netDispRatio(dP));
        b.add("signChangePitch", signChangeRate(dP));
        b.add("maxAbsPitch",     maxAbs(dP));

        b.add("pearsonYawPitch", pearson(dY, dP));
        b.add("energyRatio",     energy(dP) > 1e-8 ? energy(dY) / energy(dP) : 1.0);
        b.add("meanAbsRatio",    meanAbs(dP) > 1e-8 ? meanAbs(dY) / meanAbs(dP) : 1.0);

        b.add("stdJerkSum",  std(jY, mean(jY)) + std(jP, mean(jP)));
        b.add("stdAccelSum", std(aY, mean(aY)) + std(aP, mean(aP)));

        b.add("autocorrYaw",   autocorr(dY, 1));
        b.add("autocorrPitch", autocorr(dP, 1));
        b.add("maxRunYaw",     maxRunLength(dY));
        b.add("maxRunPitch",   maxRunLength(dP));

        b.add("highFreqEnergyYaw",   highFreqEnergy(dY));
        b.add("highFreqEnergyPitch", highFreqEnergy(dP));

        b.add("accelSymYaw",   accelSymmetry(dY));
        b.add("accelSymPitch", accelSymmetry(dP));

        b.add("crossCorrLag",    crossCorrLag(dY, dP));
        b.add("phaseStability",  phaseStability(dY, dP));
        b.add("periodicityYaw",  periodicityScore(dY));
        b.add("periodicityPitch",periodicityScore(dP));

        return b;
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
                int batchEnd  = Math.min(batchStart + BATCH_SIZE, trainFeatures.size());
                int batchSize = batchEnd - batchStart;

                double[] bOutGrad = new double[1];
                double[] wOutGrad = new double[8];
                double[] b1Grad   = new double[32];
                double[] b2Grad   = new double[16];
                double[] b3Grad   = new double[8];
                double[][] w1Grad = new double[IN][32];
                double[][] w2Grad = new double[32][16];
                double[][] w3Grad = new double[16][8];

                for (int idxPtr = batchStart; idxPtr < batchEnd; idxPtr++) {
                    int idx = indices[idxPtr];
                    double[] raw = trainFeatures.get(idx);
                    for (int i = 0; i < IN; i++) {
                        double s = featStd[i] < 1e-8 ? 1.0 : featStd[i];
                        x[i] = (raw[i] - featMean[i]) / s;
                    }
                    double y = trainLabels.get(idx);

                    for (int j = 0; j < 32; j++) { double s = b1[j]; for (int i = 0; i < IN; i++) s += x[i] * w1[i][j]; z1[j] = s; h1[j] = relu(s); }
                    for (int j = 0; j < 16; j++) { double s = b2[j]; for (int i = 0; i < 32; i++) s += h1[i] * w2[i][j]; z2[j] = s; h2[j] = relu(s); }
                    for (int j = 0; j < 8; j++)  { double s = b3[j]; for (int i = 0; i < 16; i++) s += h2[i] * w3[i][j]; z3[j] = s; h3[j] = relu(s); }
                    double out = bOut; for (int i = 0; i < 8; i++) out += h3[i] * wOut[i];
                    double p   = sigmoid(out);
                    double dOut = p - y;

                    bOutGrad[0] += dOut;
                    for (int i = 0; i < 8; i++) wOutGrad[i] += dOut * h3[i];

                    for (int i = 0; i < 8; i++) dH3[i] = dOut * wOut[i] * reluDeriv(z3[i]);
                    Arrays.fill(dH2, 0.0);
                    for (int i = 0; i < 8; i++) for (int j = 0; j < 16; j++) dH2[j] += dH3[i] * w3[j][i];
                    for (int i = 0; i < 16; i++) dH2[i] *= reluDeriv(z2[i]);
                    Arrays.fill(dH1, 0.0);
                    for (int i = 0; i < 16; i++) for (int j = 0; j < 32; j++) dH1[j] += dH2[i] * w2[j][i];
                    for (int i = 0; i < 32; i++) dH1[i] *= reluDeriv(z1[i]);

                    for (int i = 0; i < 32; i++) { b1Grad[i] += dH1[i]; for (int j = 0; j < IN; j++) w1Grad[j][i] += dH1[i] * x[j]; }
                    for (int i = 0; i < 16; i++) { b2Grad[i] += dH2[i]; for (int j = 0; j < 32; j++) w2Grad[j][i] += dH2[i] * h1[j]; }
                    for (int i = 0; i < 8; i++)  { b3Grad[i] += dH3[i]; for (int j = 0; j < 16; j++) w3Grad[j][i] += dH3[i] * h2[j]; }
                }

                double inv = 1.0 / batchSize;
                bOutGrad[0] *= inv;
                for (int i = 0; i < 8; i++) wOutGrad[i] *= inv;
                for (int i = 0; i < 32; i++) { b1Grad[i] *= inv; for (int j = 0; j < IN; j++) w1Grad[j][i] *= inv; }
                for (int i = 0; i < 16; i++) { b2Grad[i] *= inv; for (int j = 0; j < 32; j++) w2Grad[j][i] *= inv; }
                for (int i = 0; i < 8; i++)  { b3Grad[i] *= inv; for (int j = 0; j < 16; j++) w3Grad[j][i] *= inv; }

                adamStep++;
                double bc1 = 1.0 - Math.pow(BETA1, adamStep);
                double bc2 = 1.0 - Math.pow(BETA2, adamStep);

                mBOut = BETA1 * mBOut + (1 - BETA1) * bOutGrad[0];
                vBOut = BETA2 * vBOut + (1 - BETA2) * bOutGrad[0] * bOutGrad[0];
                bOut -= LR * (mBOut / bc1) / (Math.sqrt(vBOut / bc2) + EPS);

                for (int i = 0; i < 8; i++) {
                    double gw = wOutGrad[i] + LAMBDA * wOut[i];
                    mWOut[i] = BETA1 * mWOut[i] + (1 - BETA1) * gw;
                    vWOut[i] = BETA2 * vWOut[i] + (1 - BETA2) * gw * gw;
                    wOut[i] -= LR * (mWOut[i] / bc1) / (Math.sqrt(vWOut[i] / bc2) + EPS);
                }

                for (int i = 0; i < 32; i++) {
                    mB1[i] = BETA1 * mB1[i] + (1 - BETA1) * b1Grad[i];
                    vB1[i] = BETA2 * vB1[i] + (1 - BETA2) * b1Grad[i] * b1Grad[i];
                    b1[i] -= LR * (mB1[i] / bc1) / (Math.sqrt(vB1[i] / bc2) + EPS);
                    for (int j = 0; j < IN; j++) {
                        double gw = w1Grad[j][i] + LAMBDA * w1[j][i];
                        mW1[j][i] = BETA1 * mW1[j][i] + (1 - BETA1) * gw;
                        vW1[j][i] = BETA2 * vW1[j][i] + (1 - BETA2) * gw * gw;
                        w1[j][i] -= LR * (mW1[j][i] / bc1) / (Math.sqrt(vW1[j][i] / bc2) + EPS);
                    }
                }

                for (int i = 0; i < 16; i++) {
                    mB2[i] = BETA1 * mB2[i] + (1 - BETA1) * b2Grad[i];
                    vB2[i] = BETA2 * vB2[i] + (1 - BETA2) * b2Grad[i] * b2Grad[i];
                    b2[i] -= LR * (mB2[i] / bc1) / (Math.sqrt(vB2[i] / bc2) + EPS);
                    for (int j = 0; j < 32; j++) {
                        double gw = w2Grad[j][i] + LAMBDA * w2[j][i];
                        mW2[j][i] = BETA1 * mW2[j][i] + (1 - BETA1) * gw;
                        vW2[j][i] = BETA2 * vW2[j][i] + (1 - BETA2) * gw * gw;
                        w2[j][i] -= LR * (mW2[j][i] / bc1) / (Math.sqrt(vW2[j][i] / bc2) + EPS);
                    }
                }

                for (int i = 0; i < 8; i++) {
                    mB3[i] = BETA1 * mB3[i] + (1 - BETA1) * b3Grad[i];
                    vB3[i] = BETA2 * vB3[i] + (1 - BETA2) * b3Grad[i] * b3Grad[i];
                    b3[i] -= LR * (mB3[i] / bc1) / (Math.sqrt(vB3[i] / bc2) + EPS);
                    for (int j = 0; j < 16; j++) {
                        double gw = w3Grad[j][i] + LAMBDA * w3[j][i];
                        mW3[j][i] = BETA1 * mW3[j][i] + (1 - BETA1) * gw;
                        vW3[j][i] = BETA2 * vW3[j][i] + (1 - BETA2) * gw * gw;
                        w3[j][i] -= LR * (mW3[j][i] / bc1) / (Math.sqrt(vW3[j][i] / bc2) + EPS);
                    }
                }
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
                    if (logger != null)
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

        if (logger != null)
            logger.info("[LocalModel] Training finished. Adam lr=" + LR + " FB_BETA=" + FB_BETA);
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

    private double computeLoss(List<double[]> featuresList, List<Double> labels) {
        double loss = 0.0;
        for (int i = 0; i < featuresList.size(); i++) {
            double p = Math.max(1e-15, Math.min(1.0 - 1e-15, predictFromFeatures(featuresList.get(i))));
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
                boolean predPos = predictFromFeatures(featuresList.get(i)) >= threshold;
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
        if (logger != null)
            logger.info("[LocalModel] Best threshold=" + bestThreshold
                    + " F-beta(b=" + FB_BETA + ")=" + bestScore);
        return bestThreshold;
    }

    private double[] normalise(double[] f) {
        double[] n = new double[IN];
        if (!normReady) { System.arraycopy(f, 0, n, 0, IN); return n; }
        for (int i = 0; i < IN; i++) {
            double s = featStd[i] < 1e-8 ? 1.0 : featStd[i];
            n[i] = (f[i] - featMean[i]) / s;
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

    private double relu(double x)       { return x > 0 ? x : 0; }
    private double reluDeriv(double x)  { return x > 0 ? 1 : 0; }
    private double sigmoid(double x) {
        if (x > 20) return 1.0; if (x < -20) return 0.0;
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
        for (int i = 0; i < x.length; i++) { double ex=x[i]-mx, ey=y[i]-my; num+=ex*ey; dx+=ex*ex; dy+=ey*ey; }
        double d = Math.sqrt(dx*dy);
        return d < 1e-10 ? 0 : num/d;
    }

    private double fastPct(double[] input, double percentile) {
        double[] v = Arrays.copyOf(input, input.length);
        int n = v.length, k = Math.min(Math.max((int) Math.ceil(percentile / 100.0 * n) - 1, 0), n - 1);
        int left = 0, right = n - 1;
        while (left < right) {
            int pp = partition(v, left, right, left + rnd.nextInt(right - left + 1));
            if (pp == k) return v[pp]; else if (pp < k) left = pp + 1; else right = pp - 1;
        }
        return v[left];
    }

    private int partition(double[] v, int left, int right, int pivotIndex) {
        double pv = v[pivotIndex], t; v[pivotIndex] = v[right]; v[right] = pv;
        int si = left;
        for (int i = left; i < right; i++) if (v[i] < pv) { t = v[si]; v[si] = v[i]; v[i] = t; si++; }
        t = v[right]; v[right] = v[si]; v[si] = t;
        return si;
    }

    private double entropy(double[] v, double min, double max) {
        int bins = Math.max(4, (int) Math.round(Math.sqrt(v.length)));
        if (max - min < 1e-10) return 0;
        int[] h = new int[bins]; double range = max - min;
        for (double d : v) { int b = (int)((d-min)/range*(bins-1)); if(b<0)b=0; if(b>=bins)b=bins-1; h[b]++; }
        double e = 0, nInv = 1.0 / v.length;
        for (int c : h) if (c > 0) { double p = c * nInv; e -= p * Math.log(p); }
        return e;
    }

    private double skewness(double[] v, double m) {
        double s = std(v, m); if (s < 1e-8) return 0;
        double sum = 0; for (double x : v) { double z = (x-m)/s; sum += z*z*z; } return sum / v.length;
    }

    private double kurtosis(double[] v, double m) {
        double s = std(v, m); if (s < 1e-8) return 0;
        double sum = 0; for (double x : v) { double z = (x-m)/s; sum += z*z*z*z; } return sum / v.length - 3.0;
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
        for (int i = 1; i < v.length - 1; i++) { double d = v[i+1] - 2*v[i] + v[i-1]; e += d*d; }
        return e / v.length;
    }

    private double accelSymmetry(double[] v) {
        if (v.length < 4) return 0;
        int half = v.length / 2; double sumDiff = 0;
        for (int i = 0; i < half; i++) sumDiff += Math.abs(Math.abs(v[i]) - Math.abs(v[v.length-1-i]));
        return sumDiff / half;
    }

    private double crossCorrLag(double[] x, double[] y) {
        double bestCorr = -1; int bestLag = 0, maxLag = Math.min(5, x.length / 4);
        double mx = mean(x), my = mean(y);
        for (int lag = 0; lag <= maxLag; lag++) {
            double num = 0, dx = 0, dy = 0;
            for (int i = lag; i < x.length; i++) {
                num += (x[i]-mx)*(y[i-lag]-my);
                dx  += (x[i]-mx)*(x[i]-mx);
                dy  += (y[i-lag]-my)*(y[i-lag]-my);
            }
            double corr = Math.sqrt(dx*dy) < 1e-10 ? 0 : Math.abs(num / Math.sqrt(dx*dy));
            if (corr > bestCorr) { bestCorr = corr; bestLag = lag; }
        }
        return bestLag;
    }

    private double phaseStability(double[] y, double[] p) {
        if (y.length < 4) return 0;
        double[] dAngle = new double[y.length - 1];
        double prevAngle = Math.atan2(p[0], y[0]);
        for (int i = 1; i < y.length; i++) {
            double angle = Math.atan2(p[i], y[i]);
            double d = angle - prevAngle;
            while (d >  Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            dAngle[i - 1] = d;
            prevAngle = angle;
        }
        double m = mean(dAngle);
        return std(dAngle, m);
    }

    private double periodicityScore(double[] v) {
        if (v.length < 8) return 0;
        double maxAc = 0;
        for (int lag = 2; lag <= v.length / 3; lag++) {
            double ac = Math.abs(autocorr(v, lag));
            if (ac > maxAc) maxAc = ac;
        }
        return maxAc;
    }
}