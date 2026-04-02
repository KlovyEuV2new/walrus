/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.config;

public final class PunishmentEntry {

    private final double minProb;
    private final String command;

    public PunishmentEntry(double minProb, String command) {
        this.minProb = minProb;
        this.command = command;
    }

    public double getMinProb() {
        return minProb;
    }

    public String getCommand() {
        return command;
    }

    public static PunishmentEntry parse(String raw) {
        if (raw == null) return new PunishmentEntry(0.0, null);
        int sep = raw.indexOf("||");
        if (sep > 0) {
            try {
                double p = Double.parseDouble(raw.substring(0, sep));
                return new PunishmentEntry(p, raw.substring(sep + 2));
            } catch (NumberFormatException ignored) {}
        }
        return new PunishmentEntry(0.0, raw);
    }
}