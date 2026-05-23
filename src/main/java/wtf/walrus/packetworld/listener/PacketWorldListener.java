package wtf.walrus.packetworld.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import wtf.walrus.packetworld.PacketWorld;
import wtf.walrus.player.WalrusPlayer;

public class PacketWorldListener extends PacketListenerAbstract {
    public final WalrusPlayer player;
    public final PacketWorld packetWorld;

    public PacketWorldListener(WalrusPlayer player, PacketWorld packetWorld) {
        this.player = player;
        this.packetWorld = packetWorld;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketSend(PacketSendEvent e) {
        if (!e.getUser().equals(player.user)) return;

        if (e.getPacketType().equals(PacketType.Play.Server.JOIN_GAME)) {
            WrapperPlayServerJoinGame packet = new WrapperPlayServerJoinGame(e);
            packetWorld.sync(packet);

        } else if (e.getPacketType().equals(PacketType.Play.Server.RESPAWN)) {
            WrapperPlayServerRespawn packet = new WrapperPlayServerRespawn(e);
            packetWorld.sync(packet);

        } else if (e.getPacketType().equals(PacketType.Play.Server.CHUNK_DATA)) {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(e);
            Column column = packet.getColumn();
            packetWorld.load(column.getX(), column.getZ(), column.getChunks());

        } else if (e.getPacketType().equals(PacketType.Play.Server.UNLOAD_CHUNK)) {
            WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(e);
            packetWorld.unload(packet.getChunkX(), packet.getChunkZ());

        } else if (e.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(e);
            Vector3i pos = packet.getBlockPosition();
            BaseChunk[] sections = packetWorld.getSection(pos.getX() >> 4, pos.getZ() >> 4);
            if (sections == null) return;
            int i = pos.getY() >> 4;
            if (i >= 0 && i < sections.length && sections[i] != null) {
                sections[i].set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, packet.getBlockId());
            }

        } else if (e.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(e);
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                int x = block.getX(), y = block.getY(), z = block.getZ();
                BaseChunk[] sections = packetWorld.getSection(x >> 4, z >> 4);
                if (sections == null) continue;
                int i = y >> 4;
                if (i >= 0 && i < sections.length && sections[i] != null) {
                    sections[i].set(x & 15, y & 15, z & 15, block.getBlockId());
                }
            }

        }
    }
}
