package wtf.walrus.checks.impl.aim;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "AimB", maxBuffer = 5, decay = 90, ap = 1, description = "Detect Aura Rotation toggling")
public class AimB extends Check implements PacketCheck {

    private double lastX;

    public AimB(WalrusPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isRotationUpdate(event.getPacketType())) return;

        if (player.hasAttackedSince(500)) {
            double dx = Math.abs(player.rotationData.getDeltaYaw());
            double acelX = Math.abs(dx - lastX);
            boolean motionXZ = player.deltaXZ() > 0.06;

            if (Math.abs(dx) > 170.0 && lastX < 50 && acelX > 100 && motionXZ) {
                String deltax = String.format("%.5f", dx);
                String ldeltax = String.format("%.5f", lastX);
                String acelStr = String.format("%.5f", acelX);
                flagAndAlert("dx: " + deltax + " ldx: " + ldeltax + " ax: " + acelStr);
            }

            lastX = dx;
        }
    }
}