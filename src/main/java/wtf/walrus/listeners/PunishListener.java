package wtf.walrus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import wtf.walrus.Main;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.DamageVerdict;
import wtf.walrus.data.MiningPlayerData;

import java.util.Random;

public class PunishListener implements Listener {
    private final Main plugin;
    private final Random random = new Random();

    public PunishListener(Main plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDig(BlockBreakEvent e) {
        if (!plugin.getPluginConfig().isDigVerdict()) return;

        Player player = e.getPlayer();
        MiningPlayerData data = plugin.getMiningCheck().getOrCreatePlayerData(player);
        DamageVerdict verdict = data.getDamageVerdict();
        if (verdict != null) {
            if (data.ticksSinceVerdict <= 100) {
                double r = random.nextDouble();
                if (r < verdict.prob()) e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.getPluginConfig().isDigVerdict()) return;

        Player player = e.getPlayer();
        MiningPlayerData data = plugin.getMiningCheck().getOrCreatePlayerData(player);
        DamageVerdict verdict = data.getDamageVerdict();
        if (verdict != null) {
            if (data.ticksSinceVerdict <= 100) {
                double r = random.nextDouble();
                if (r < verdict.prob()) e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
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
