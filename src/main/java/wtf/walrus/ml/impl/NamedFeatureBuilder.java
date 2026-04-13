package wtf.walrus.ml.impl;

import java.util.ArrayList;

final class NamedFeatureBuilder {

    private ArrayList<String> namesBuf;
    private ArrayList<Double> valuesBuf;

    private final String[] frozenNames;
    private final double[] values;
    private int cursor;

    NamedFeatureBuilder() {
        this.frozenNames = null;
        this.values      = null;
        this.namesBuf    = new ArrayList<>();
        this.valuesBuf   = new ArrayList<>();
    }

    NamedFeatureBuilder(String[] frozenNames) {
        this.frozenNames = frozenNames;
        this.values      = new double[frozenNames.length];
        this.namesBuf    = null;
        this.valuesBuf   = null;
    }

    void add(String name, double value) {
        namesBuf.add(name);
        valuesBuf.add(value);
    }

    void add(double value) {
        values[cursor++] = value;
    }

    String[] names() {
        if (frozenNames != null) return frozenNames;
        return namesBuf.toArray(new String[0]);
    }

    double[] toArray() {
        if (frozenNames != null) return values;
        double[] out = new double[valuesBuf.size()];
        for (int i = 0; i < out.length; i++) out[i] = valuesBuf.get(i);
        return out;
    }

    int size() {
        return frozenNames != null ? frozenNames.length : namesBuf.size();
    }
}