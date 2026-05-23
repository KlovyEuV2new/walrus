package wtf.walrus.npc.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UUIDUtil {
    public static UUID get(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }
}
