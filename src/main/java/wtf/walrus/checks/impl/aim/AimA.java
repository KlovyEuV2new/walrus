// check from StormGrim (aka AimB)
package wtf.walrus.checks.impl.aim;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "AimA", description = "invalid sensitivity", decay = 120)
public class AimA extends Check implements PacketCheck {
    public AimA(WalrusPlayer player) {
        super(player);
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        if (isRotationUpdate(event.getPacketType())) {
            if (player.hasAttackedSince(150L)) {
                final boolean tooLowSensitivity = player.rotationData.hasTooLowSensitivity();
                final double finalSensitivity = player.rotationData.getFinalSensitivity();
                final double deltaPitch = player.rotationData.getDeltaPitch();
                String pitch = String.format("%.5f", deltaPitch);
                //      String sens = String.format("%.5f", deltaPitch);
                String info = "send: " + finalSensitivity + " pitch: " + pitch;
                if (player.rotationData.usingCinematicCamera()) return; // invalid
                if (!player.rotationData.hasValidSensitivityNormalized() && !tooLowSensitivity) {
                    addBuffer();
                    flagAndAlert(info);
                } else {
                    reward();
                }
            }
        }
    }
}
