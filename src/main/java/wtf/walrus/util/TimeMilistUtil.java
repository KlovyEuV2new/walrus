package wtf.walrus.util;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import wtf.walrus.player.WalrusPlayer;


public class TimeMilistUtil {
    private WalrusPlayer player;
    private long lastTime;
    public TimeMilistUtil(WalrusPlayer player) {
        this.player = player;
        lastTime = System.currentTimeMillis();
    }
    public double getPassed() {
        return (System.currentTimeMillis() - lastTime);
    }
    public boolean hasNotPassed(int time) {
        return (getPassed() < time);
    }
    public void reset() {
        lastTime = System.currentTimeMillis();
    }
    public static boolean isTransaction(PacketTypeCommon packetType) {
        return packetType == PacketType.Play.Client.PONG || packetType == PacketType.Play.Client.WINDOW_CONFIRMATION;
    }
}
