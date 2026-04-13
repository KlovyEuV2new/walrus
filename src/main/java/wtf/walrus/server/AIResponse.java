/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file contains code derived from:
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package wtf.walrus.server;

import wtf.walrus.ml.MLOut;

public class AIResponse {
    private final MLOut output;
    private final String error;
    private final String model;

    public AIResponse(MLOut out) {
        this(out, null, null);
    }

    public AIResponse(MLOut out, String error) {
        this(out, error, null);
    }

    public AIResponse(MLOut out, String error, String model) {
        this.output = out;
        this.error = error;
        this.model = model;
    }

    public MLOut getOutput() {
        return output;
    }

    public double getProbability() {
        return output.prob();
    }

    public String getError() {
        return error;
    }

    public String getModel() {
        return model != null ? model : "unknown";
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    public static AIResponse fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            String trimmed = json.trim();

            int errorIndex = trimmed.indexOf("\"error\"");
            if (errorIndex != -1) {
                int colonIndex = trimmed.indexOf(':', errorIndex);
                if (colonIndex != -1) {
                    int start = trimmed.indexOf('"', colonIndex + 1);
                    if (start != -1) {
                        int end = trimmed.indexOf('"', start + 1);
                        if (end != -1) {
                            String errorMsg = trimmed.substring(start + 1, end);
                            return new AIResponse(null, errorMsg);
                        }
                    }
                }
            }

            int probIndex = trimmed.indexOf("\"probability\"");
            if (probIndex == -1) {
                return null;
            }
            int colonIndex = trimmed.indexOf(':', probIndex);
            if (colonIndex == -1) {
                return null;
            }
            int start = colonIndex + 1;
            while (start < trimmed.length() && Character.isWhitespace(trimmed.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < trimmed.length()) {
                char c = trimmed.charAt(end);
                if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            String probStr = trimmed.substring(start, end);
            double probability = Double.parseDouble(probStr);

            String modelName = null;
            int modelIndex = trimmed.indexOf("\"model\"");
            if (modelIndex != -1) {
                int modelColonIndex = trimmed.indexOf(':', modelIndex);
                if (modelColonIndex != -1) {
                    int modelStart = trimmed.indexOf('"', modelColonIndex + 1);
                    if (modelStart != -1) {
                        int modelEnd = trimmed.indexOf('"', modelStart + 1);
                        if (modelEnd != -1) {
                            modelName = trimmed.substring(modelStart + 1, modelEnd);
                        }
                    }
                }
            }

            return new AIResponse(new MLOut(probability, new String[]{"unknown"}), null, modelName);
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        return "{\"probability\":" + output.prob() + "}";
    }

    @Override
    public String toString() {
        return "AIResponse{probability=" + output.prob() + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        AIResponse that = (AIResponse) obj;
        return Double.compare(that.output.prob(), output.prob()) == 0;
    }

    @Override
    public int hashCode() {
        long temp = Double.doubleToLongBits(output.prob());
        return (int) (temp ^ (temp >>> 32));
    }
}