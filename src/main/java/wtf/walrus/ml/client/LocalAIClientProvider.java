package wtf.walrus.ml.client;

import wtf.walrus.ml.Model;
import wtf.walrus.ml.impl.LocalModel;
import wtf.walrus.ml.managers.TrainingDataManager;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class LocalAIClientProvider {

    private final File   mlsDir;
    private final File   dataDir;
    private final Logger logger;

    private List<Model>         models;
    private LocalAIClient client;
    private TrainingDataManager trainingDataManager;
    private ScheduledExecutorService scheduler;

    public LocalAIClientProvider(File pluginDataFolder, Logger logger) {
        this.logger  = logger;
        this.mlsDir  = new File(pluginDataFolder, "mls");
        this.dataDir = new File(mlsDir, "data");
        mlsDir.mkdirs();
        dataDir.mkdirs();
    }

    public void initialize() {
        if (this.client != null) return;
        Model localModel = new LocalModel(logger);
        this.models = Arrays.asList(localModel);

        for (Model m : models) {
            File f = modelFile(m);
            if (m.load(f)) {
                logger.info("[LocalML] Model '" + m.getName() + "' loaded from " + f.getAbsolutePath());
            } else {
                logger.info("[LocalML] No model '" + m.getName() + "' — will train on first /walrus train");
            }
        }

        this.trainingDataManager = new TrainingDataManager(
                mlsDir.getParentFile(), models.get(0), logger);

        this.client = new LocalAIClient(models);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "walrus-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                client::cleanupStaleStates, 1, 1, TimeUnit.MINUTES);

        logger.info("[LocalML] Local AI client ready with " + models.size() + " model(s)");
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown())
            scheduler.shutdownNow();
    }

    public void saveModels() {
        for (Model m : models) {
            try {
                m.save(modelFile(m));
                logger.info("[LocalML] Model '" + m.getName() + "' saved → " + modelFile(m).getAbsolutePath());
            } catch (Exception e) {
                logger.warning("[LocalML] Failed to save model '" + m.getName() + "': " + e.getMessage());
            }
        }
    }

    public String trainAndSave(int epochs) {
        TrainingDataManager.TrainingResult result =
                trainingDataManager.trainModel(epochs, models);
        if (result.success) {
            saveModels();
            return "§aTraining complete! §f" + result;
        } else {
            return "§cTraining failed: " + result.error;
        }
    }

    private File modelFile(Model m) {
        return new File(mlsDir, m.getName() + ".bin");
    }

    public File getMlsDir() { return mlsDir; }

    public LocalAIClient       getClient()              { return client; }
    public List<Model>    getModels()              { return models; }
    public Model          getModel()               { return models.get(0); }
    public TrainingDataManager getTrainingDataManager() { return trainingDataManager; }
    public File                getDataDir()             { return dataDir; }
}