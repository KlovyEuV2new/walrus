package wtf.walrus.ml;

import wtf.walrus.checks.impl.ai.AICheck;
import wtf.walrus.data.TickData;

import java.io.File;
import java.io.IOException;
import java.util.List;

public abstract class Model {

    private final String name;
    private boolean trained;
    private double  optimalThreshold;

    public int sequence;

    public Model(String name) {
        this.name             = name;
        this.trained          = false;
        this.optimalThreshold = 0.5;
    }

    public abstract double   predict(List<TickData> ticks);
    public abstract double   predictFromFeatures(double[] raw);
    public abstract double[] extractFeatures(List<TickData> ticks);
    public abstract void     trainBatch(List<double[]> featuresList, List<Double> labels, int epochs);

    public abstract void    save(File dir) throws IOException;
    public abstract boolean load(File dir);

    public void reload(AICheck aiCheck) {
        this.sequence = aiCheck.getSequence();
    }

    public String getName()             { return name; }
    public boolean isTrained()          { return trained; }
    public double getOptimalThreshold() { return optimalThreshold; }

    protected void setTrained(boolean trained)                  { this.trained = trained; }
    protected void setOptimalThreshold(double optimalThreshold) { this.optimalThreshold = optimalThreshold; }
}