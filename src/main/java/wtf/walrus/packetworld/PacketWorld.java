package wtf.walrus.packetworld;

import com.github.retrooper.packetevents.protocol.world.Difficulty;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PacketWorld {
    private String name;
    private Difficulty difficulty;
    private DimensionType type;

    private final Map<Long, BaseChunk[]> chunks = new ConcurrentHashMap<>();

    private long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public void load(int x, int z, BaseChunk[] sections) {
        chunks.put(key(x, z), sections);
    }

    public void unload(int x, int z) {
        chunks.remove(key(x, z));
    }

    public void sync(WrapperPlayServerJoinGame p) {
        name = p.getWorldName();
        difficulty = p.getDifficulty();
        type = p.getDimensionType();
    }

    public void sync(WrapperPlayServerRespawn p) {
        name = p.getWorldName().orElse(null) != null ? p.getWorldName().get() : null;
        difficulty = p.getDifficulty();
        type = p.getDimensionType();
    }

    public BaseChunk[] getSection(int x, int z) {
        return chunks.get(key(x, z));
    }

    public WrappedBlockState getBlockAt(int x, int y, int z) {
        BaseChunk[] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null) return null;
        int i = y >> 4;
        if (i < 0 || i >= sections.length || sections[i] == null) return null;
        return sections[i].get(x & 15, y & 15, z & 15);
    }

    public String getName() {
        return name;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public DimensionType getType() {
        return type;
    }
}