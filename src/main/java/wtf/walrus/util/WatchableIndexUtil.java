package wtf.walrus.util;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WatchableIndexUtil {
    public static @Nullable EntityData<?> getIndex(@NotNull List<EntityData<?>> objects, int index) {
        for (EntityData<?> object : objects) {
            if (object.getIndex() == index) return object;
        }

        return null;
    }
}
