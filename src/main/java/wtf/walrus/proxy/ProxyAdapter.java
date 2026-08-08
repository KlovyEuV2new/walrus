package wtf.walrus.proxy;

import org.bukkit.entity.Player;

public interface ProxyAdapter {

    void connect(Player player, String server);
}