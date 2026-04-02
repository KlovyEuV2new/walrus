package wtf.walrus.checks.manager;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.MutableClassToInstanceMap;
import wtf.walrus.checks.Check;
import wtf.walrus.checks.impl.aim.AimA;
import wtf.walrus.checks.impl.aim.AimB;
import wtf.walrus.checks.impl.aim.snap.AimSnapLook;
import wtf.walrus.checks.impl.aim.states.AimStaticA;
import wtf.walrus.checks.impl.aim.states.AimStaticB;
import wtf.walrus.checks.impl.badpackets.BadPacketsA;
import wtf.walrus.player.WalrusPlayer;

public class CheckManager {

    private final ClassToInstanceMap<Check> checks = MutableClassToInstanceMap.create();

    public CheckManager(WalrusPlayer player) {
        init(player);
    }

    private void init(WalrusPlayer player) {
        // aim
        register(new AimA(player));
        register(new AimB(player));

        // aim static
        register(new AimStaticA(player));
        register(new AimStaticB(player));

        // aim look
        register(new AimSnapLook(player));

        // badpackets
        register(new BadPacketsA(player));
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        for (Check check : checks.values()) {
            check.receivePacket(event);
        }
    }

    public void onPacketSend(PacketSendEvent event) {
        for (Check check : checks.values()) {
            check.sendPacket(event);
        }
    }

    public <T extends Check> void register(T instance) {
        checks.putInstance((Class<T>) instance.getClass(), instance);
    }

    public <T extends Check> T get(Class<T> clazz) {
        return checks.getInstance(clazz);
    }

    public <T extends Check> boolean has(Class<T> clazz) {
        return checks.getInstance(clazz) != null;
    }
}