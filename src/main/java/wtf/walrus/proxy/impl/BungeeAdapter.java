package wtf.walrus.proxy.impl;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import wtf.walrus.Main;
import wtf.walrus.proxy.ProxyAdapter;

public class BungeeAdapter implements ProxyAdapter {

    private final Main plugin;

    public BungeeAdapter(Main plugin) {
        this.plugin = plugin;

        plugin.getServer()
                .getMessenger()
                .registerOutgoingPluginChannel(
                        plugin,
                        "BungeeCord"
                );
    }

    @Override
    public void connect(Player player, String server) {

        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        out.writeUTF("Connect");
        out.writeUTF(server);

        player.sendPluginMessage(
                plugin,
                "BungeeCord",
                out.toByteArray()
        );
    }
}