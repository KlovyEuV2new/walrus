package wtf.walrus.ml.impl;

import wtf.walrus.data.TickData;
import wtf.walrus.ml.MLOut;
import wtf.walrus.ml.Model;

import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public class GRUModel extends Model {

    public static final int version = 47;

    private static final int TICK_IN = 6;
    private static final int GRU_H   = 64;

    public static final int IN;

    static {
        TickData dummy = new TickData(0, 0, 0, 0, 0, 0, 0, 0);
        List<TickData> probe = List.of(dummy, dummy);
        IN = new GRUModel(null).extractFeaturesInternal(probe).size();
    }

    private static final int BATCH_SIZE = 128;

    private static final double LR       = 0.0001;
    private static final double BETA1    = 0.9;
    private static final double BETA2    = 0.999;
    private static final double EPS      = 1e-8;
    private static final double LAMBDA   = 1e-5;
    private static final double FB_BETA  = 1.0;
    private static final int    PATIENCE = 5;

    private double[][] gruWrX = new double[TICK_IN][GRU_H];
    private double[][] gruWrH = new double[GRU_H][GRU_H];
    private double[]   gruBr  = new double[GRU_H];
    private double[][] gruWzX = new double[TICK_IN][GRU_H];
    private double[][] gruWzH = new double[GRU_H][GRU_H];
    private double[]   gruBz  = new double[GRU_H];
    private double[][] gruWnX = new double[TICK_IN][GRU_H];
    private double[][] gruWnH = new double[GRU_H][GRU_H];
    private double[]   gruBn  = new double[GRU_H];

    private double[] wOut = new double[GRU_H];
    private double   bOut = 0.0;

    private double[][] mGruWrX = new double[TICK_IN][GRU_H]; private double[][] vGruWrX = new double[TICK_IN][GRU_H];
    private double[][] mGruWrH = new double[GRU_H][GRU_H];   private double[][] vGruWrH = new double[GRU_H][GRU_H];
    private double[]   mGruBr  = new double[GRU_H];           private double[]   vGruBr  = new double[GRU_H];
    private double[][] mGruWzX = new double[TICK_IN][GRU_H]; private double[][] vGruWzX = new double[TICK_IN][GRU_H];
    private double[][] mGruWzH = new double[GRU_H][GRU_H];   private double[][] vGruWzH = new double[GRU_H][GRU_H];
    private double[]   mGruBz  = new double[GRU_H];           private double[]   vGruBz  = new double[GRU_H];
    private double[][] mGruWnX = new double[TICK_IN][GRU_H]; private double[][] vGruWnX = new double[TICK_IN][GRU_H];
    private double[][] mGruWnH = new double[GRU_H][GRU_H];   private double[][] vGruWnH = new double[GRU_H][GRU_H];
    private double[]   mGruBn  = new double[GRU_H];           private double[]   vGruBn  = new double[GRU_H];

    private double[] mWOut = new double[GRU_H]; private double[] vWOut = new double[GRU_H];
    private double   mBOut = 0.0;               private double   vBOut = 0.0;

    private int adamStep = 0;

    private double[] featMean = new double[IN];
    private double[] featStd  = new double[IN];
    private boolean  normReady = false;

    private final java.util.logging.Logger logger;
    private static final Random rnd = new Random();

    public GRUModel(java.util.logging.Logger logger) {
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

        Integer[] indices = new Integer[f.length];
        for (int i = 0; i < f.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(Math.abs(f[b]), Math.abs(f[a])));

        String[] bestNames = new String[f.length];
        for (int i = 0; i < f.length; i++) bestNames[i] = fNames[indices[i]].toLowerCase();

        double pf = predictFromSequence(ticks);
        return new MLOut(pf, bestNames);
    }

    @Override
    public double predictFromFeatures(double[] raw) {
        double[] h = new double[GRU_H];
        int seqLen = raw.length / TICK_IN;
        for (int t = 0; t < seqLen; t++) {
            double[] x = new double[TICK_IN];
            for (int i = 0; i < TICK_IN; i++) x[i] = raw[t * TICK_IN + i];
            h = gruStep(x, h);
        }
        double out = bOut;
        for (int i = 0; i < GRU_H; i++) out += h[i] * wOut[i];
        return sigmoid(out);
    }

    private double predictFromSequence(List<TickData> ticks) {
        double[] h = new double[GRU_H];
        for (TickData t : ticks) {
            double[] x = extractTickFeatures(t);
            h = gruStep(x, h);
        }
        double out = bOut;
        for (int i = 0; i < GRU_H; i++) out += h[i] * wOut[i];
        return sigmoid(out);
    }

    public String[] getFeatureNames(List<TickData> ticks) {
        NamedFeatureBuilder b = extractFeaturesInternal(ticks);
        return b != null ? b.names() : new String[0];
    }

    @Override
    public double[] extractFeatures(List<TickData> ticks) {
        if (ticks == null || ticks.isEmpty()) return null;
        double[] raw = new double[ticks.size() * TICK_IN];
        for (int t = 0; t < ticks.size(); t++) {
            double[] x = extractTickFeatures(ticks.get(t));
            for (int i = 0; i < TICK_IN; i++) raw[t * TICK_IN + i] = x[i];
        }
        return raw;
    }

    private double[] extractTickFeatures(TickData t) {
        return new double[]{t.deltaYaw, t.deltaPitch, t.accelYaw, t.accelPitch, t.gcdErrorYaw, t.gcdErrorPitch};
    }

    private NamedFeatureBuilder extractFeaturesInternal(List<TickData> ticks) {
        if (ticks == null || ticks.size() < 2) return null;
        NamedFeatureBuilder b = new NamedFeatureBuilder();
        for (int t = 0; t < ticks.size(); t++) {
            TickData td = ticks.get(t);
            b.add("deltaYaw_"   + t, td.deltaYaw);
            b.add("deltaPitch_" + t, td.deltaPitch);
            b.add("accelYaw_"   + t, td.accelYaw);
            b.add("accelPitch_" + t, td.accelPitch);
            b.add("errorYaw_"   + t, td.gcdErrorYaw);
            b.add("errorPitch_" + t, td.gcdErrorPitch);
        }
        return b;
    }

    private double[] gruStep(double[] x, double[] hPrev) {
        double[] r    = new double[GRU_H];
        double[] z    = new double[GRU_H];
        double[] n    = new double[GRU_H];
        double[] hNew = new double[GRU_H];

        for (int j = 0; j < GRU_H; j++) {
            double sr = gruBr[j];
            for (int i = 0; i < TICK_IN; i++) sr += x[i] * gruWrX[i][j];
            for (int i = 0; i < GRU_H; i++)  sr += hPrev[i] * gruWrH[i][j];
            r[j] = sigmoid(sr);

            double sz = gruBz[j];
            for (int i = 0; i < TICK_IN; i++) sz += x[i] * gruWzX[i][j];
            for (int i = 0; i < GRU_H; i++)  sz += hPrev[i] * gruWzH[i][j];
            z[j] = sigmoid(sz);
        }

        for (int j = 0; j < GRU_H; j++) {
            double sn = gruBn[j];
            for (int i = 0; i < TICK_IN; i++) sn += x[i] * gruWnX[i][j];
            for (int i = 0; i < GRU_H; i++)  sn += (r[i] * hPrev[i]) * gruWnH[i][j];
            n[j] = tanh(sn);
            hNew[j] = (1.0 - z[j]) * n[j] + z[j] * hPrev[j];
        }
        return hNew;
    }

    @Override
    public void trainBatch(List<double[]> featuresList, List<Double> labels, int epochs, int threads) {
        if (featuresList.isEmpty()) return;

        if (!normReady) fitNormaliser(featuresList);
        if (!isTrained()) { initWeights(new Random(1)); resetAdam(); }

        int size     = featuresList.size();
        int splitIdx = Math.max(1, (int) (size * 0.8));

        List<double[]> trainFeatures = featuresList.subList(0, splitIdx);
        List<Double>   trainLabels   = labels.subList(0, splitIdx);
        List<double[]> valFeatures   = featuresList.subList(splitIdx, size);
        List<Double>   valLabels     = labels.subList(splitIdx, size);

        int[] idxArr = new int[trainFeatures.size()];
        for (int i = 0; i < idxArr.length; i++) idxArr[i] = i;

        double[][] bestGruWrX = new double[TICK_IN][GRU_H]; double[][] bestGruWrH = new double[GRU_H][GRU_H]; double[] bestGruBr = new double[GRU_H];
        double[][] bestGruWzX = new double[TICK_IN][GRU_H]; double[][] bestGruWzH = new double[GRU_H][GRU_H]; double[] bestGruBz = new double[GRU_H];
        double[][] bestGruWnX = new double[TICK_IN][GRU_H]; double[][] bestGruWnH = new double[GRU_H][GRU_H]; double[] bestGruBn = new double[GRU_H];
        double[]   bestWOut   = new double[GRU_H];
        double     bestBOut   = bOut;

        double bestValLoss   = Double.MAX_VALUE;
        int    noImprovCount = 0;
        int    bestEpoch     = 0;

        Thread trainingOwner = Thread.currentThread();
        int trainingThreads = trainingThreadCount(threads);
        if (logger != null)
            logger.info("using cpu threads (" + trainingThreads + ")");
        ForkJoinPool trainingPool = new ForkJoinPool(trainingThreads);
        try {
            for (int epoch = 0; epoch < epochs; epoch++) {
                shuffleArray(idxArr, rnd);

                for (int batchStart = 0; batchStart < trainFeatures.size(); batchStart += BATCH_SIZE) {
                    int batchEnd  = Math.min(batchStart + BATCH_SIZE, trainFeatures.size());
                    int batchSize = batchEnd - batchStart;

                    Gradient batchGradient = new Gradient();
                    int parallelStart = batchStart;
                    int parallelEnd = batchEnd;
                    int workerCount = Math.min(trainingThreads, batchSize);
                    int samplesPerWorker = (batchSize + workerCount - 1) / workerCount;
                    Gradient[] workerGradients = new Gradient[workerCount];

                    trainingPool.submit(() -> IntStream.range(0, workerCount)
                            .parallel()
                            .forEach(worker -> {
                                int workerStart = parallelStart + worker * samplesPerWorker;
                                int workerEnd = Math.min(workerStart + samplesPerWorker, parallelEnd);
                                Gradient workerGradient = new Gradient();
                                workerGradients[worker] = workerGradient;

                                for (int idxPtr = workerStart; idxPtr < workerEnd; idxPtr++) {
                                    if (trainingOwner.isInterrupted()) {
                                        throw new CancellationException("Local model training was cancelled");
                                    }

                                    double[][] gGruWrX = workerGradient.gruWrX; double[][] gGruWrH = workerGradient.gruWrH; double[] gGruBr = workerGradient.gruBr;
                                    double[][] gGruWzX = workerGradient.gruWzX; double[][] gGruWzH = workerGradient.gruWzH; double[] gGruBz = workerGradient.gruBz;
                                    double[][] gGruWnX = workerGradient.gruWnX; double[][] gGruWnH = workerGradient.gruWnH; double[] gGruBn = workerGradient.gruBn;
                                    double[]   gWOut   = workerGradient.wOut;
                                    double[]   gBOut   = workerGradient.bOut;

                                    int      idx = idxArr[idxPtr];
                                    double[] raw = trainFeatures.get(idx);
                                    double   y   = trainLabels.get(idx);

                                    int seqLen = raw.length / TICK_IN;

                                    double[][] hs    = new double[seqLen + 1][GRU_H];
                                    double[][] rsAll = new double[seqLen][GRU_H];
                                    double[][] zsAll = new double[seqLen][GRU_H];
                                    double[][] nsAll = new double[seqLen][GRU_H];

                                    for (int t = 0; t < seqLen; t++) {
                                        int rawOffset  = t * TICK_IN;
                                        double[] hPrev = hs[t];
                                        double[] r     = new double[GRU_H];
                                        double[] z     = new double[GRU_H];
                                        double[] n     = new double[GRU_H];

                                        for (int j = 0; j < GRU_H; j++) {
                                            double vr = gruBr[j];
                                            for (int i = 0; i < TICK_IN; i++) vr += raw[rawOffset + i] * gruWrX[i][j];
                                            for (int i = 0; i < GRU_H; i++)  vr += hPrev[i] * gruWrH[i][j];
                                            r[j] = sigmoid(vr);

                                            double vz = gruBz[j];
                                            for (int i = 0; i < TICK_IN; i++) vz += raw[rawOffset + i] * gruWzX[i][j];
                                            for (int i = 0; i < GRU_H; i++)  vz += hPrev[i] * gruWzH[i][j];
                                            z[j] = sigmoid(vz);
                                        }
                                        for (int j = 0; j < GRU_H; j++) {
                                            double vn = gruBn[j];
                                            for (int i = 0; i < TICK_IN; i++) vn += raw[rawOffset + i] * gruWnX[i][j];
                                            for (int i = 0; i < GRU_H; i++)  vn += (r[i] * hPrev[i]) * gruWnH[i][j];
                                            n[j] = tanh(vn);
                                            hs[t + 1][j] = (1.0 - z[j]) * n[j] + z[j] * hPrev[j];
                                        }
                                        rsAll[t] = r; zsAll[t] = z; nsAll[t] = n;
                                    }

                                    double[] hLast = hs[seqLen];
                                    double   logit = bOut;
                                    for (int i = 0; i < GRU_H; i++) logit += hLast[i] * wOut[i];
                                    double p    = sigmoid(logit);
                                    double dOut = p - y;

                                    gBOut[0] += dOut;
                                    for (int i = 0; i < GRU_H; i++) gWOut[i] += dOut * hLast[i];

                                    double[] dH = new double[GRU_H];
                                    for (int i = 0; i < GRU_H; i++) dH[i] = dOut * wOut[i];

                                    for (int t = seqLen - 1; t >= 0; t--) {
                                        double[] hPrev  = hs[t];
                                        double[] r      = rsAll[t];
                                        double[] z      = zsAll[t];
                                        double[] n      = nsAll[t];

                                        double[] dN     = new double[GRU_H];
                                        double[] dZ     = new double[GRU_H];
                                        double[] dR     = new double[GRU_H];
                                        double[] dHPrev = new double[GRU_H];

                                        for (int j = 0; j < GRU_H; j++) {
                                            dN[j] = dH[j] * (1.0 - z[j]) * (1.0 - n[j] * n[j]);
                                            dZ[j] = dH[j] * (hPrev[j] - n[j]) * z[j] * (1.0 - z[j]);
                                            dHPrev[j] += dH[j] * z[j];
                                        }

                                        for (int j = 0; j < GRU_H; j++) {
                                            double drj = 0.0;
                                            for (int k = 0; k < GRU_H; k++) drj += dN[k] * gruWnH[j][k] * hPrev[j];
                                            dR[j] = drj * r[j] * (1.0 - r[j]);
                                        }

                                        for (int j = 0; j < GRU_H; j++) {
                                            gGruBr[j] += dR[j];
                                            for (int i = 0; i < TICK_IN; i++) gGruWrX[i][j] += dR[j] * raw[t * TICK_IN + i];
                                            for (int i = 0; i < GRU_H; i++)  gGruWrH[i][j] += dR[j] * hPrev[i];

                                            gGruBz[j] += dZ[j];
                                            for (int i = 0; i < TICK_IN; i++) gGruWzX[i][j] += dZ[j] * raw[t * TICK_IN + i];
                                            for (int i = 0; i < GRU_H; i++)  gGruWzH[i][j] += dZ[j] * hPrev[i];

                                            gGruBn[j] += dN[j];
                                            for (int i = 0; i < TICK_IN; i++) gGruWnX[i][j] += dN[j] * raw[t * TICK_IN + i];
                                            for (int i = 0; i < GRU_H; i++)  gGruWnH[i][j] += dN[j] * r[i] * hPrev[i];
                                        }

                                        for (int i = 0; i < GRU_H; i++) {
                                            for (int j = 0; j < GRU_H; j++) {
                                                dHPrev[i] += dR[j] * gruWrH[i][j];
                                                dHPrev[i] += dZ[j] * gruWzH[i][j];
                                                dHPrev[i] += dN[j] * gruWnH[i][j] * r[j];
                                            }
                                        }

                                        dH = dHPrev;
                                    }
                                }
                            })).join();

                    for (Gradient workerGradient : workerGradients) batchGradient.add(workerGradient);

                    double[][] gGruWrX = batchGradient.gruWrX; double[][] gGruWrH = batchGradient.gruWrH; double[] gGruBr = batchGradient.gruBr;
                    double[][] gGruWzX = batchGradient.gruWzX; double[][] gGruWzH = batchGradient.gruWzH; double[] gGruBz = batchGradient.gruBz;
                    double[][] gGruWnX = batchGradient.gruWnX; double[][] gGruWnH = batchGradient.gruWnH; double[] gGruBn = batchGradient.gruBn;
                    double[]   gWOut   = batchGradient.wOut;
                    double[]   gBOut   = batchGradient.bOut;

                    double inv = 1.0 / batchSize;
                    gBOut[0] *= inv;
                    for (int i = 0; i < GRU_H; i++) gWOut[i] *= inv;
                    for (int i = 0; i < TICK_IN; i++) for (int j = 0; j < GRU_H; j++) { gGruWrX[i][j] *= inv; gGruWzX[i][j] *= inv; gGruWnX[i][j] *= inv; }
                    for (int i = 0; i < GRU_H; i++)  for (int j = 0; j < GRU_H; j++) { gGruWrH[i][j] *= inv; gGruWzH[i][j] *= inv; gGruWnH[i][j] *= inv; }
                    for (int j = 0; j < GRU_H; j++) { gGruBr[j] *= inv; gGruBz[j] *= inv; gGruBn[j] *= inv; }

                    adamStep++;
                    double bc1 = 1.0 - Math.pow(BETA1, adamStep);
                    double bc2 = 1.0 - Math.pow(BETA2, adamStep);

                    mBOut = BETA1 * mBOut + (1 - BETA1) * gBOut[0];
                    vBOut = BETA2 * vBOut + (1 - BETA2) * gBOut[0] * gBOut[0];
                    bOut -= LR * (mBOut / bc1) / (Math.sqrt(vBOut / bc2) + EPS);

                    for (int i = 0; i < GRU_H; i++) {
                        double gw = gWOut[i] + LAMBDA * wOut[i];
                        mWOut[i] = BETA1 * mWOut[i] + (1 - BETA1) * gw;
                        vWOut[i] = BETA2 * vWOut[i] + (1 - BETA2) * gw * gw;
                        wOut[i] -= LR * (mWOut[i] / bc1) / (Math.sqrt(vWOut[i] / bc2) + EPS);
                    }

                    adamUpdateMatrix(gGruWrX, gruWrX, mGruWrX, vGruWrX, TICK_IN, GRU_H, bc1, bc2);
                    adamUpdateMatrix(gGruWrH, gruWrH, mGruWrH, vGruWrH, GRU_H,   GRU_H, bc1, bc2);
                    adamUpdateVector(gGruBr,  gruBr,  mGruBr,  vGruBr,  GRU_H,           bc1, bc2);
                    adamUpdateMatrix(gGruWzX, gruWzX, mGruWzX, vGruWzX, TICK_IN, GRU_H, bc1, bc2);
                    adamUpdateMatrix(gGruWzH, gruWzH, mGruWzH, vGruWzH, GRU_H,   GRU_H, bc1, bc2);
                    adamUpdateVector(gGruBz,  gruBz,  mGruBz,  vGruBz,  GRU_H,           bc1, bc2);
                    adamUpdateMatrix(gGruWnX, gruWnX, mGruWnX, vGruWnX, TICK_IN, GRU_H, bc1, bc2);
                    adamUpdateMatrix(gGruWnH, gruWnH, mGruWnH, vGruWnH, GRU_H,   GRU_H, bc1, bc2);
                    adamUpdateVector(gGruBn,  gruBn,  mGruBn,  vGruBn,  GRU_H,           bc1, bc2);
                }

                double trainLoss = computeLoss(trainFeatures, trainLabels);

                if (!valFeatures.isEmpty()) {
                    double valLoss = computeLoss(valFeatures, valLabels);
                    if (logger != null)
                        logger.info("[LocalModel] Epoch " + (epoch + 1)
                                + " trainLoss=" + String.format("%.6f", trainLoss)
                                + " valLoss=" + String.format("%.6f", valLoss));
                    if (valLoss < bestValLoss - 1e-6) {
                        bestValLoss   = valLoss;
                        noImprovCount = 0;
                        bestEpoch     = epoch + 1;
                        snapshotWeights(bestGruWrX, bestGruWrH, bestGruBr,
                                bestGruWzX, bestGruWzH, bestGruBz,
                                bestGruWnX, bestGruWnH, bestGruBn, bestWOut);
                        bestBOut = bOut;
                    } else if (++noImprovCount >= PATIENCE) {
                        if (logger != null)
                            logger.info("[LocalModel] Early stopping at epoch " + (epoch + 1)
                                    + ", best epoch=" + bestEpoch);
                        break;
                    }
                } else {
                    if (logger != null)
                        logger.info("[LocalModel] Epoch " + (epoch + 1)
                                + " trainLoss=" + String.format("%.6f", trainLoss));
                }
            }
        } finally {
            trainingPool.shutdownNow();
        }

        if (!valFeatures.isEmpty() && bestEpoch > 0) {
            restoreWeights(bestGruWrX, bestGruWrH, bestGruBr,
                    bestGruWzX, bestGruWzH, bestGruBz,
                    bestGruWnX, bestGruWnH, bestGruBn, bestWOut);
            bOut = bestBOut;
        }

//        double bestThreshold = findOptimalThreshold(featuresList, labels);
        setOptimalThreshold(0.5);
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

            writeMatrix(dos, gruWrX, TICK_IN, GRU_H); writeMatrix(dos, gruWrH, GRU_H, GRU_H); writeVector(dos, gruBr, GRU_H);
            writeMatrix(dos, gruWzX, TICK_IN, GRU_H); writeMatrix(dos, gruWzH, GRU_H, GRU_H); writeVector(dos, gruBz, GRU_H);
            writeMatrix(dos, gruWnX, TICK_IN, GRU_H); writeMatrix(dos, gruWnH, GRU_H, GRU_H); writeVector(dos, gruBn, GRU_H);
            writeVector(dos, wOut, GRU_H); dos.writeDouble(bOut);

            writeVector(dos, featMean, IN);
            writeVector(dos, featStd,  IN);

            writeMatrix(dos, mGruWrX, TICK_IN, GRU_H); writeMatrix(dos, mGruWrH, GRU_H, GRU_H); writeVector(dos, mGruBr, GRU_H);
            writeMatrix(dos, mGruWzX, TICK_IN, GRU_H); writeMatrix(dos, mGruWzH, GRU_H, GRU_H); writeVector(dos, mGruBz, GRU_H);
            writeMatrix(dos, mGruWnX, TICK_IN, GRU_H); writeMatrix(dos, mGruWnH, GRU_H, GRU_H); writeVector(dos, mGruBn, GRU_H);
            writeVector(dos, mWOut, GRU_H); dos.writeDouble(mBOut);

            writeMatrix(dos, vGruWrX, TICK_IN, GRU_H); writeMatrix(dos, vGruWrH, GRU_H, GRU_H); writeVector(dos, vGruBr, GRU_H);
            writeMatrix(dos, vGruWzX, TICK_IN, GRU_H); writeMatrix(dos, vGruWzH, GRU_H, GRU_H); writeVector(dos, vGruBz, GRU_H);
            writeMatrix(dos, vGruWnX, TICK_IN, GRU_H); writeMatrix(dos, vGruWnH, GRU_H, GRU_H); writeVector(dos, vGruBn, GRU_H);
            writeVector(dos, vWOut, GRU_H); dos.writeDouble(vBOut);
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

            readMatrix(dis, gruWrX, TICK_IN, GRU_H); readMatrix(dis, gruWrH, GRU_H, GRU_H); readVector(dis, gruBr, GRU_H);
            readMatrix(dis, gruWzX, TICK_IN, GRU_H); readMatrix(dis, gruWzH, GRU_H, GRU_H); readVector(dis, gruBz, GRU_H);
            readMatrix(dis, gruWnX, TICK_IN, GRU_H); readMatrix(dis, gruWnH, GRU_H, GRU_H); readVector(dis, gruBn, GRU_H);
            readVector(dis, wOut, GRU_H); bOut = dis.readDouble();

            readVector(dis, featMean, IN);
            readVector(dis, featStd,  IN);

            readMatrix(dis, mGruWrX, TICK_IN, GRU_H); readMatrix(dis, mGruWrH, GRU_H, GRU_H); readVector(dis, mGruBr, GRU_H);
            readMatrix(dis, mGruWzX, TICK_IN, GRU_H); readMatrix(dis, mGruWzH, GRU_H, GRU_H); readVector(dis, mGruBz, GRU_H);
            readMatrix(dis, mGruWnX, TICK_IN, GRU_H); readMatrix(dis, mGruWnH, GRU_H, GRU_H); readVector(dis, mGruBn, GRU_H);
            readVector(dis, mWOut, GRU_H); mBOut = dis.readDouble();

            readMatrix(dis, vGruWrX, TICK_IN, GRU_H); readMatrix(dis, vGruWrH, GRU_H, GRU_H); readVector(dis, vGruBr, GRU_H);
            readMatrix(dis, vGruWzX, TICK_IN, GRU_H); readMatrix(dis, vGruWzH, GRU_H, GRU_H); readVector(dis, vGruBz, GRU_H);
            readMatrix(dis, vGruWnX, TICK_IN, GRU_H); readMatrix(dis, vGruWnH, GRU_H, GRU_H); readVector(dis, vGruBn, GRU_H);
            readVector(dis, vWOut, GRU_H); vBOut = dis.readDouble();

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public MLOut compare(List<TickData> a, List<TickData> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty())
            return new MLOut(0.0, new String[]{"unknown"});

        NamedFeatureBuilder builderA = extractFeaturesInternal(a);
        NamedFeatureBuilder builderB = extractFeaturesInternal(b);
        if (builderA == null || builderB == null)
            return new MLOut(0.0, new String[]{"unknown"});

        double[] fA     = builderA.toArray();
        double[] fB     = builderB.toArray();
        String[] fNames = builderA.names();
        int      len    = Math.min(fA.length, fB.length);

        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < len; i++) {
            dot   += fA[i] * fB[i];
            normA += fA[i] * fA[i];
            normB += fB[i] * fB[i];
        }
        double cosineSim = (normA < 1e-10 || normB < 1e-10)
                ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
        cosineSim = Math.max(0.0, cosineSim);

        double euclidDist = 0;
        for (int i = 0; i < len; i++) { double d = fA[i] - fB[i]; euclidDist += d * d; }
        euclidDist = Math.sqrt(euclidDist);
        double euclidSimilarity = 1.0 / (1.0 + euclidDist / Math.sqrt(len));
        double score            = 0.5 * cosineSim + 0.5 * euclidSimilarity;

        double[] absDiff = new double[len];
        for (int i = 0; i < len; i++) absDiff[i] = Math.abs(fA[i] - fB[i]);

        Integer[] indices = new Integer[len];
        for (int i = 0; i < len; i++) indices[i] = i;
        Arrays.sort(indices, (x, y) -> Double.compare(absDiff[y], absDiff[x]));

        String[] bestNames = new String[len];
        for (int i = 0; i < len; i++) bestNames[i] = fNames[indices[i]].toLowerCase();

        return new MLOut(score, bestNames);
    }

    private void fitNormaliser(List<double[]> all) {
        Arrays.fill(featMean, 0.0);
        Arrays.fill(featStd,  1.0);
        normReady = true;
    }

    private void initWeights(Random r) {
        heInit(r, gruWrX, TICK_IN, GRU_H); heInit(r, gruWrH, GRU_H, GRU_H);
        heInit(r, gruWzX, TICK_IN, GRU_H); heInit(r, gruWzH, GRU_H, GRU_H);
        heInit(r, gruWnX, TICK_IN, GRU_H); heInit(r, gruWnH, GRU_H, GRU_H);
        double sO = Math.sqrt(2.0 / GRU_H);
        for (int i = 0; i < GRU_H; i++) wOut[i] = (r.nextDouble() - 0.5) * 2.0 * sO;
    }

    private void heInit(Random r, double[][] w, int fanIn, int fanOut) {
        double s = Math.sqrt(2.0 / fanIn);
        for (int i = 0; i < fanIn; i++) for (int j = 0; j < fanOut; j++) w[i][j] = (r.nextDouble() - 0.5) * 2.0 * s;
    }

    private void resetAdam() {
        adamStep = 0; mBOut = 0.0; vBOut = 0.0;
        zeroMatrix(mGruWrX, TICK_IN, GRU_H); zeroMatrix(vGruWrX, TICK_IN, GRU_H);
        zeroMatrix(mGruWrH, GRU_H,   GRU_H); zeroMatrix(vGruWrH, GRU_H,   GRU_H);
        zeroMatrix(mGruWzX, TICK_IN, GRU_H); zeroMatrix(vGruWzX, TICK_IN, GRU_H);
        zeroMatrix(mGruWzH, GRU_H,   GRU_H); zeroMatrix(vGruWzH, GRU_H,   GRU_H);
        zeroMatrix(mGruWnX, TICK_IN, GRU_H); zeroMatrix(vGruWnX, TICK_IN, GRU_H);
        zeroMatrix(mGruWnH, GRU_H,   GRU_H); zeroMatrix(vGruWnH, GRU_H,   GRU_H);
        Arrays.fill(mGruBr, 0.0); Arrays.fill(vGruBr, 0.0);
        Arrays.fill(mGruBz, 0.0); Arrays.fill(vGruBz, 0.0);
        Arrays.fill(mGruBn, 0.0); Arrays.fill(vGruBn, 0.0);
        Arrays.fill(mWOut,  0.0); Arrays.fill(vWOut,  0.0);
    }

    private void zeroMatrix(double[][] m, int r, int c) { for (int i = 0; i < r; i++) Arrays.fill(m[i], 0.0); }

    private void snapshotWeights(double[][] bWrX, double[][] bWrH, double[] bBr,
                                 double[][] bWzX, double[][] bWzH, double[] bBz,
                                 double[][] bWnX, double[][] bWnH, double[] bBn,
                                 double[] bWOut) {
        copyMatrix(gruWrX, bWrX, TICK_IN, GRU_H); copyMatrix(gruWrH, bWrH, GRU_H, GRU_H); System.arraycopy(gruBr, 0, bBr, 0, GRU_H);
        copyMatrix(gruWzX, bWzX, TICK_IN, GRU_H); copyMatrix(gruWzH, bWzH, GRU_H, GRU_H); System.arraycopy(gruBz, 0, bBz, 0, GRU_H);
        copyMatrix(gruWnX, bWnX, TICK_IN, GRU_H); copyMatrix(gruWnH, bWnH, GRU_H, GRU_H); System.arraycopy(gruBn, 0, bBn, 0, GRU_H);
        System.arraycopy(wOut, 0, bWOut, 0, GRU_H);
    }

    private void restoreWeights(double[][] bWrX, double[][] bWrH, double[] bBr,
                                double[][] bWzX, double[][] bWzH, double[] bBz,
                                double[][] bWnX, double[][] bWnH, double[] bBn,
                                double[] bWOut) {
        copyMatrix(bWrX, gruWrX, TICK_IN, GRU_H); copyMatrix(bWrH, gruWrH, GRU_H, GRU_H); System.arraycopy(bBr, 0, gruBr, 0, GRU_H);
        copyMatrix(bWzX, gruWzX, TICK_IN, GRU_H); copyMatrix(bWzH, gruWzH, GRU_H, GRU_H); System.arraycopy(bBz, 0, gruBz, 0, GRU_H);
        copyMatrix(bWnX, gruWnX, TICK_IN, GRU_H); copyMatrix(bWnH, gruWnH, GRU_H, GRU_H); System.arraycopy(bBn, 0, gruBn, 0, GRU_H);
        System.arraycopy(bWOut, 0, wOut, 0, GRU_H);
    }

    private void copyMatrix(double[][] src, double[][] dst, int r, int c) {
        for (int i = 0; i < r; i++) System.arraycopy(src[i], 0, dst[i], 0, c);
    }

    private void adamUpdateMatrix(double[][] g, double[][] w, double[][] m, double[][] v,
                                  int rows, int cols, double bc1, double bc2) {
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double gw = g[i][j] + LAMBDA * w[i][j];
                m[i][j] = BETA1 * m[i][j] + (1 - BETA1) * gw;
                v[i][j] = BETA2 * v[i][j] + (1 - BETA2) * gw * gw;
                w[i][j] -= LR * (m[i][j] / bc1) / (Math.sqrt(v[i][j] / bc2) + EPS);
            }
        }
    }

    private void adamUpdateVector(double[] g, double[] w, double[] m, double[] v,
                                  int len, double bc1, double bc2) {
        for (int i = 0; i < len; i++) {
            double gw = g[i] + LAMBDA * w[i];
            m[i] = BETA1 * m[i] + (1 - BETA1) * gw;
            v[i] = BETA2 * v[i] + (1 - BETA2) * gw * gw;
            w[i] -= LR * (m[i] / bc1) / (Math.sqrt(v[i] / bc2) + EPS);
        }
    }

    private static final class Gradient {
        final double[][] gruWrX = new double[TICK_IN][GRU_H];
        final double[][] gruWrH = new double[GRU_H][GRU_H];
        final double[] gruBr = new double[GRU_H];
        final double[][] gruWzX = new double[TICK_IN][GRU_H];
        final double[][] gruWzH = new double[GRU_H][GRU_H];
        final double[] gruBz = new double[GRU_H];
        final double[][] gruWnX = new double[TICK_IN][GRU_H];
        final double[][] gruWnH = new double[GRU_H][GRU_H];
        final double[] gruBn = new double[GRU_H];
        final double[] wOut = new double[GRU_H];
        final double[] bOut = new double[1];

        void add(Gradient other) {
            addMatrix(gruWrX, other.gruWrX);
            addMatrix(gruWrH, other.gruWrH);
            addVector(gruBr, other.gruBr);
            addMatrix(gruWzX, other.gruWzX);
            addMatrix(gruWzH, other.gruWzH);
            addVector(gruBz, other.gruBz);
            addMatrix(gruWnX, other.gruWnX);
            addMatrix(gruWnH, other.gruWnH);
            addVector(gruBn, other.gruBn);
            addVector(wOut, other.wOut);
            bOut[0] += other.bOut[0];
        }

        private static void addMatrix(double[][] target, double[][] source) {
            for (int row = 0; row < target.length; row++) {
                addVector(target[row], source[row]);
            }
        }

        private static void addVector(double[] target, double[] source) {
            for (int index = 0; index < target.length; index++) {
                target[index] += source[index];
            }
        }
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
        if (logger != null) logger.info("Starting threshold search...");
        if (featuresList.isEmpty()) return 0.5;

        double bestThreshold = 0.5;
        double bestScore = -1.0;
        double betaSq = FB_BETA * FB_BETA;

        int n = featuresList.size();
        double[] preds = new double[n];

        for (int i = 0; i < n; i++) {
            preds[i] = predictFromFeatures(featuresList.get(i));
        }

        for (int t = 50; t <= 90; t += 5) {
            if (logger != null) logger.info("Threshold step " + t);

            double threshold = t / 100.0;
            int tp = 0, fp = 0, fn = 0;

            for (int i = 0; i < n; i++) {
                boolean predPos = preds[i] >= threshold;
                boolean actPos = labels.get(i) >= 0.5;

                if (predPos && actPos) tp++;
                else if (predPos) fp++;
                else if (actPos) fn++;
            }

            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);

            double score = (precision + recall) < 1e-8 ? 0.0
                    : (1 + betaSq) * precision * recall / (betaSq * precision + recall);

            if (score > bestScore) {
                bestScore = score;
                bestThreshold = threshold;
            }
        }

        if (logger != null) {
            logger.info("[LocalModel] Best threshold=" + bestThreshold
                    + " F-beta(b=" + FB_BETA + ")=" + bestScore);
        }

        return bestThreshold;
    }

    private void writeMatrix(DataOutputStream dos, double[][] m, int r, int c) throws IOException {
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) dos.writeDouble(m[i][j]);
    }

    private void writeVector(DataOutputStream dos, double[] v, int len) throws IOException {
        for (int i = 0; i < len; i++) dos.writeDouble(v[i]);
    }

    private void readMatrix(DataInputStream dis, double[][] m, int r, int c) throws IOException {
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) m[i][j] = dis.readDouble();
    }

    private void readVector(DataInputStream dis, double[] v, int len) throws IOException {
        for (int i = 0; i < len; i++) v[i] = dis.readDouble();
    }

    private void shuffleArray(int[] arr, Random r) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1); int tmp = arr[j]; arr[j] = arr[i]; arr[i] = tmp;
        }
    }

    private static int trainingThreadCount(int threads) {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(threads, processors - 1));
    }

    private double sigmoid(double x) {
        if (x > 20) return 1.0; if (x < -20) return 0.0;
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double sigmoidDeriv(double preActivation) {
        double s = sigmoid(preActivation);
        return s * (1.0 - s);
    }

    private double tanh(double x) {
        if (x > 20) return 1.0; if (x < -20) return -1.0;
        double e2 = Math.exp(2.0 * x);
        return (e2 - 1.0) / (e2 + 1.0);
    }

    private double tanhDeriv(double preActivation) {
        double t = tanh(preActivation);
        return 1.0 - t * t;
    }
}