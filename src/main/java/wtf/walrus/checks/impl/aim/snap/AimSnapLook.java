package wtf.walrus.checks.impl.aim.snap;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "AimSnapLook", maxBuffer = 7, decay = 120, ap = 1, description = "Detect Aim Snap/Look")
public class AimSnapLook extends Check implements PacketCheck {

    public AimSnapLook(WalrusPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isRotationUpdate(event.getPacketType())) return;

        if (player.lastPacketWasTeleport || System.currentTimeMillis() - player.firstJoined < 5000
                || player.inVehicle) {
            return;
        }

        if (player.hasAttackedSince(100)) {
            float dx = player.rotationData.getDeltaYaw();
            float dy = player.rotationData.getDeltaPitch();
            float sx = player.rotationData.getSmoothnessYaw();
            float sy = player.rotationData.getSmoothnessPitch();

            if (dx > 5.0f && sx < -5.0f && player.deltaXZ() > 0.05) {
                if (addBuffer() > getMaxBuffer()) {
                    String info = String.format("dx=%.5f, dy=%.5f, sx=%.5f, sy=%.5f, buffer=%.2f",
                            dx, dy, sx, sy, getBuffer());
                    flagAndAlert(info);
                }
            } else if (getBuffer() > 0) {
                removeBuffer();
            }
        }
    }
}