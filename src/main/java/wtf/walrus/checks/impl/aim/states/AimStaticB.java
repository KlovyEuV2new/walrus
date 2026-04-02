// check from SnowGrim
package wtf.walrus.checks.impl.aim.states;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "AimStaticB", maxBuffer = 20, decay = 120, ap = 5)
public class AimStaticB extends Check implements PacketCheck {
    private float lastDx, lastDy;

    public AimStaticB(WalrusPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (isRotationUpdate(event.getPacketType())) {

            float dx = Math.abs(player.rotationData.getDeltaYaw());
            float dy = Math.abs(player.rotationData.getDeltaPitch());

            float ax = Math.abs(lastDx - dx);
            float ay = Math.abs(lastDy - dy);

            if (player.hasAttackedSince(500)) {
                if (player.rotationData.isCinematic()) return;

                boolean invalidX = (ax < -0.0f && ax % -0.5f == -0.0f || ax > 0.0f && ax % 0.5f == 0.0f);
                boolean invalidY = (ay < -0.0f && ay % -0.5f == -0.0f || ay > 0.0f && ay % 0.5f == 0.0f);

                if (invalidX || invalidY) {
                    addBuffer();
                    if (getBuffer() > getMaxBuffer()) {
                        flagAndAlert(String.format("ax=%.5f, ay=%.5f, buffer=%.1f", ax, ay, getBuffer()));
                    }
                } else if (getBuffer() > 0) {
                    removeBuffer();
                }
            }

            lastDx = dx;
            lastDy = dy;
        }
    }
}