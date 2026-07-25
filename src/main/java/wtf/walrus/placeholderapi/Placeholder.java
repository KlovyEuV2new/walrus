package wtf.walrus.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import wtf.walrus.Main;
import wtf.walrus.checks.CheckType;
import wtf.walrus.data.AIPlayerData;
import wtf.walrus.data.MiningPlayerData;
import wtf.walrus.hologram.NametagManager;
import wtf.walrus.ml.managers.VerdictManager;
import wtf.walrus.util.FastMath;

import java.util.UUID;

public class Placeholder extends PlaceholderExpansion {

    public Placeholder() {
        this.register();
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return null;
        Main plugin = Main.instance;
        UUID uuid = player.getUniqueId();

        AIPlayerData aid = plugin.getAiCheck().getOrCreatePlayerData(player);
        MiningPlayerData mid = plugin.getMiningCheck().getOrCreatePlayerData(player);

        VerdictManager verdictManager = plugin.getVerdictManager();
        CheckType lv = verdictManager.getLastVerdict(uuid);

        switch (params) {
            case "vl":
                return String.valueOf(plugin.getViolationManager().getViolationLevel(uuid));
            case "alerts":
                return String.valueOf(plugin.getAlertManager().hasAlertsEnabled(player));

            case "aim_buffer":
                return String.valueOf(FastMath.format(aid.getBuffer(), 1));
            case "aim_prob":
                return String.valueOf(FastMath.format(aid.getLastProbability(), 4));
            case "aim_avg":
                return String.valueOf(FastMath.format(aid.getAverageProbability(), 4));
            case "aim_color":
                return String.valueOf(NametagManager.getInfoColor(aid.getLastProbability()));

            case "mine_buffer":
                return String.valueOf(FastMath.format(mid.getBuffer(), 1));
            case "mine_prob":
                return String.valueOf(FastMath.format(mid.getLastProbability(), 4));
            case "mine_avg":
                return String.valueOf(FastMath.format(mid.getAverageProbability(), 4));
            case "mine_color":
                return String.valueOf(NametagManager.getInfoColor(mid.getLastProbability()));

            case "current_buffer":
                return String.valueOf(lv.equals(CheckType.AIM)
                        ? FastMath.format(aid.getBuffer(), 1) : FastMath.format(mid.getBuffer(), 1)
                );
            case "current_prob":
                return String.valueOf(lv.equals(CheckType.AIM)
                        ? FastMath.format(aid.getLastProbability(), 4) : FastMath.format(mid.getLastProbability(), 4)
                );
            case "current_avg":
                return String.valueOf(
                        lv.equals(CheckType.AIM)
                                ? FastMath.format(aid.getAverageProbability(), 4)
                                : FastMath.format(mid.getAverageProbability(), 4)
                );
            case "current_color":
                return String.valueOf(NametagManager.getInfoColor(lv.equals(CheckType.AIM)
                        ? aid.getLastProbability() : mid.getLastProbability())
                );
        }

        return null;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "walrusac";
    }

    @Override
    public @NotNull String getAuthor() {
        return "KlovyEuV2";
    }

    @Override
    public @NotNull String getVersion() {
        return Main.instance.getDescription().getVersion();
    }
}
