/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 */

package wtf.walrus.util;

import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;

public class GeyserUtil {

    private static boolean geyserAvailable = false;

    static {
        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserAvailable = true;
        } catch (ClassNotFoundException e) {
            geyserAvailable = false;
        }
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (!geyserAvailable) {
            return false;
        }

        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.isBedrockPlayer(uuid);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isGeyserAvailable() {
        return geyserAvailable;
    }
}
