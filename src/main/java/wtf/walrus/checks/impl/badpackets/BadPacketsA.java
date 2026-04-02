// check from GrimAC (aka BadPacketsX)

package wtf.walrus.checks.impl.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.CheckData;
import wtf.walrus.checks.types.PacketCheck;
import wtf.walrus.player.WalrusPlayer;

@CheckData(name = "BadPacketsA", decay = 90, ap = 1)
public class BadPacketsA extends Check implements PacketCheck {
    private boolean sprint, sneak;
    private int flags = 0;

    public BadPacketsA(WalrusPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.inVehicle) {
            sprint = sneak = false;
            return;
        }

        if (isTickPacket(event.getPacketType())) {
            sprint = sneak = false;
        }

        if (isFlying(event.getPacketType())) {
            flags = 0;
        }

        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction wrapper = new WrapperPlayClientEntityAction(event);

            switch (wrapper.getAction()) {
                case START_SPRINTING:
                case STOP_SPRINTING:
                    if (sprint) {
                        addBuffer();
                        if (flagAndAlert(String.format("sprint, f=%d", flags))) {
                            flags++;
                        }
                    } else reward();
                    sprint = true;
                    break;

//                case START_SNEAKING:
//                case STOP_SNEAKING:
//                    if (sneak) {
//                        addBuffer();
//                        if (flagAndAlert(String.format("sneak, f=%d", flags))) {
//                            flags++;
//                        }
//                    } else reward();
//                    sneak = true;
//                    break;
            }
        }
    }
}