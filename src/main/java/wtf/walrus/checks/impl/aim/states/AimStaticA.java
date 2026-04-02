// check from SnowGrim
package wtf.walrus.checks.impl.aim.states;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "AimStaticA", maxBuffer = 20, decay = 120, ap = 5)
public class AimStaticA extends Check implements PacketCheck {

    public AimStaticA(WalrusPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (isRotationUpdate(event.getPacketType())) {

            if (player.hasAttackedSince(500)) {

                float dy = Math.abs(player.rotationData.getDeltaPitch());

                if (player.rotationData.isCinematic()) return;

                if (dy < -0.0f && dy % -0.5f == -0.0f || dy > 0.0f && dy % 0.5f == 0.0f) {
                    addBuffer();
                    if (getBuffer() > getMaxBuffer()) {
                        flagAndAlert("dy=" + dy + " buffer=" + getBuffer());
                    }
                } else if (getBuffer() > 0) {
                    removeBuffer();
                }
            }
        }
    }
}