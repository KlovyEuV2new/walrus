/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 * Licensed under GPL-3.0
 */
package wtf.walrus.ml.managers;

import wtf.walrus.Main;
import wtf.walrus.data.TickData;
import wtf.walrus.ml.Model;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public class TrainingDataManager {

    private static final int    SEQUENCE_SIZE  = 40;
    private static final double CHEAT_MAJORITY = 0.60;

    private final File       dataDir, mlsDir;
    private final Logger     logger;
    private final Model model;

    public TrainingDataManager(File pluginDataFolder, Model model, Logger logger) {
        this.mlsDir  = new File(pluginDataFolder, "mls");
        this.dataDir = new File(pluginDataFolder, "mls/data");
        this.model   = model;
        this.logger  = logger;
        dataDir.mkdirs();
    }

    public static void putDefaultModel(Main plugin, File pluginDataFolder) {
        File mlsDir = new File(pluginDataFolder, "mls");
        if (mlsDir.exists()) return;
        mlsDir.mkdirs();

        String resourceList;
        try (InputStream list = plugin.getClass().getResourceAsStream("/mls/default/files.list")) {
            if (list == null) {
                plugin.getLogger().warning("[Walrus] /mls/default/files.list not found in resources");
                return;
            }
            resourceList = new String(list.readAllBytes());
        } catch (IOException e) {
            plugin.getLogger().severe("[Walrus] Failed to read files.list: " + e.getMessage());
            return;
        }

        for (String name : resourceList.split("\\r?\\n")) {
            name = name.trim();
            if (name.isEmpty() || !name.endsWith(".bin")) continue;
            File dest = new File(mlsDir, name);
            try (InputStream in = plugin.getClass().getResourceAsStream("/mls/default/" + name)) {
                if (in == null) {
                    plugin.getLogger().warning("[Walrus] Resource not found: /mls/default/" + name);
                    continue;
                }
                try (OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192]; int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                plugin.getLogger().info("[Walrus] Default model copied: " + dest.getAbsolutePath());
            } catch (IOException e) {
                plugin.getLogger().severe("[Walrus] Failed to copy " + name + ": " + e.getMessage());
            }
        }
    }

    public TrainingResult trainModel(int epochs) {
        return trainModel(epochs, List.of(model));
    }

    public TrainingResult trainModel(int epochs, List<Model> models) {
        TrainingSet data = loadAll();
        if (data.features.isEmpty())
            return new TrainingResult(false, 0, 0, 0, 0, 0, 0, 0, 0, "No training data found");

        List<Integer> cheatIdx = new ArrayList<>();
        List<Integer> legitIdx = new ArrayList<>();
        for (int i = 0; i < data.labels.size(); i++) {
            if (data.labels.get(i) > 0.5) cheatIdx.add(i);
            else                           legitIdx.add(i);
        }
        Collections.shuffle(cheatIdx);
        Collections.shuffle(legitIdx);

        List<double[]> trainX = new ArrayList<>(), testX = new ArrayList<>();
        List<Double>   trainY = new ArrayList<>(), testY = new ArrayList<>();

        addSplit(cheatIdx, data, trainX, trainY, testX, testY, 0.8);
        addSplit(legitIdx, data, trainX, trainY, testX, testY, 0.8);

        logger.info(String.format(
                "[MLSAC] Dataset: total=%d (cheat=%d legit=%d), train=%d test=%d",
                data.features.size(), cheatIdx.size(), legitIdx.size(),
                trainX.size(), testX.size()));

        long start = System.currentTimeMillis();
        for (Model m : models) m.trainBatch(trainX, trainY, epochs);
        long elapsed = System.currentTimeMillis() - start;

        Model primary = models.get(0);
        double thresh = primary.getOptimalThreshold();
        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (int i = 0; i < testX.size(); i++) {
            double pred  = primary.predictFromFeatures(testX.get(i)) >= thresh ? 1.0 : 0.0;
            double truth = testY.get(i);
            if      (pred == 1 && truth == 1) tp++;
            else if (pred == 0 && truth == 0) tn++;
            else if (pred == 1 && truth == 0) fp++;
            else                              fn++;
        }

        double accuracy  = testX.isEmpty() ? 0 : (double)(tp + tn) / testX.size() * 100;
        double precision = (tp + fp) == 0  ? 0 : (double) tp / (tp + fp);
        double recall    = (tp + fn) == 0  ? 0 : (double) tp / (tp + fn);
        double f1        = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double auc       = computeAUC(testX, testY, primary);

        logger.info(String.format(
                "[MLSAC] Results — Acc=%.1f%% P=%.2f R=%.2f F1=%.2f AUC=%.3f | "
                        + "TP=%d TN=%d FP=%d FN=%d | threshold=%.2f | time=%dms",
                accuracy, precision, recall, f1, auc, tp, tn, fp, fn, thresh, elapsed));

        return new TrainingResult(true, trainX.size() + testX.size(), testX.size(),
                accuracy, precision, recall, f1, auc, elapsed, null);
    }

    public TrainingSet loadAll() {
        List<double[]> features = new ArrayList<>();
        List<Double>   labels   = new ArrayList<>();
        File[] csvFiles = dataDir.listFiles((d, n) -> n.endsWith(".csv"));
        if (csvFiles == null) return new TrainingSet(features, labels);
        Arrays.sort(csvFiles);
        for (File csv : csvFiles) {
            try { loadCsvFile(csv, features, labels); } catch (Exception ignored) {}
        }
        return new TrainingSet(features, labels);
    }

    private void loadCsvFile(File csv, List<double[]> featureList, List<Double> labelList)
            throws IOException {
        List<TickData> buffer = new ArrayList<>(SEQUENCE_SIZE);
        List<Double>   lblBuf = new ArrayList<>(SEQUENCE_SIZE);

        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String  line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] p = line.split(",");
                if (p.length < 9) continue;

                double lbl;
                String s = p[0].trim().toUpperCase();
                if      (s.equals("CHEAT") || s.equals("1")) lbl = 1.0;
                else if (s.equals("LEGIT") || s.equals("0")) lbl = 0.0;
                else continue;

                buffer.add(new TickData(
                        Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                        Float.parseFloat(p[3]), Float.parseFloat(p[4]),
                        Float.parseFloat(p[5]), Float.parseFloat(p[6]),
                        Float.parseFloat(p[7]), Float.parseFloat(p[8])));
                lblBuf.add(lbl);

                if (buffer.size() >= SEQUENCE_SIZE) {
                    double cheatRatio = lblBuf.stream().mapToDouble(Double::doubleValue).sum() / SEQUENCE_SIZE;
                    if (cheatRatio >= CHEAT_MAJORITY) {
                        featureList.add(model.extractFeatures(new ArrayList<>(buffer)));
                        labelList.add(1.0);
                    } else if (cheatRatio < (1.0 - CHEAT_MAJORITY)) {
                        featureList.add(model.extractFeatures(new ArrayList<>(buffer)));
                        labelList.add(0.0);
                    }
                    buffer.clear();
                    lblBuf.clear();
                }
            }
        }
    }

    private double computeAUC(List<double[]> xs, List<Double> ys, Model model) {
        int n = xs.size();
        if (n == 0) return 0.5;
        List<double[]> pairs = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            pairs.add(new double[]{model.predictFromFeatures(xs.get(i)), ys.get(i)});
        pairs.sort((a, b) -> Double.compare(b[0], a[0]));
        long positives = ys.stream().filter(l -> l > 0.5).count();
        long negatives  = n - positives;
        if (positives == 0 || negatives == 0) return 0.5;
        double auc=0, prevFpr=0, prevTpr=0;
        long fp=0, tp=0;
        for (double[] pair : pairs) {
            if (pair[1] > 0.5) tp++; else fp++;
            double tpr = (double) tp / positives;
            double fpr = (double) fp / negatives;
            auc += (fpr - prevFpr) * (tpr + prevTpr) / 2.0;
            prevFpr = fpr; prevTpr = tpr;
        }
        return auc;
    }

    private void addSplit(List<Integer> indices, TrainingSet data,
                          List<double[]> trainX, List<Double> trainY,
                          List<double[]> testX,  List<Double> testY, double trainRatio) {
        int split = (int)(indices.size() * trainRatio);
        for (int i = 0; i < indices.size(); i++) {
            int idx = indices.get(i);
            if (i < split) { trainX.add(data.features.get(idx)); trainY.add(data.labels.get(idx)); }
            else           { testX.add(data.features.get(idx));  testY.add(data.labels.get(idx)); }
        }
    }

    public static class TrainingSet {
        public final List<double[]> features;
        public final List<Double>   labels;
        TrainingSet(List<double[]> f, List<Double> l) { features = f; labels = l; }
    }

    public static class TrainingResult {
        public final boolean success;
        public final int     samples, testSamples;
        public final double  accuracy, precision, recall, f1, auc;
        public final long    elapsedMs;
        public final String  error;

        TrainingResult(boolean success, int samples, int testSamples,
                       double accuracy, double precision, double recall,
                       double f1, double auc, long elapsedMs, String error) {
            this.success=success; this.samples=samples; this.testSamples=testSamples;
            this.accuracy=accuracy; this.precision=precision; this.recall=recall;
            this.f1=f1; this.auc=auc; this.elapsedMs=elapsedMs; this.error=error;
        }

        @Override public String toString() {
            if (!success) return "Training failed: " + error;
            return String.format("Samples=%d | Acc=%.1f%% | P=%.2f R=%.2f F1=%.2f AUC=%.3f | %dms",
                    samples, accuracy, precision, recall, f1, auc, elapsedMs);
        }
    }
}