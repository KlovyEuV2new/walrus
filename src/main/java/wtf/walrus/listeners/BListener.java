package wtf.walrus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import wtf.walrus.Main;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.DamageVerdict;
import wtf.walrus.player.WalrusPlayer;

import java.util.UUID;

public class BListener implements Listener {
    private final Main plugin;

    public BListener(Main plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // sync join player
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        WalrusPlayer walrusPlayer = WalrusPlayer.get(uuid);
        if (walrusPlayer != null) walrusPlayer.setPlayer(player);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getPluginConfig().isDamageVerdict()) return;

        Entity damager = event.getDamager();
        if (!(damager instanceof Player player)) return;

        AIPlayerData data = plugin.getAiCheck().getOrCreatePlayerData(player);
        DamageVerdict damageVerdict = data.getDamageVerdict();
        if (damageVerdict != null) {
            if (data.ticksSinceVerdict <= 100) {
                event.setDamage(event.getDamage() * Math.max(0, 1 -damageVerdict.prob()));
            }
        }
    }
}
