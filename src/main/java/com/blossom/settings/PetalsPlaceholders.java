package com.blossom.settings;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %blossomsettings_petals%           -> 1250
 * %blossomsettings_petals_short%     -> 1.3K
 * %blossomsettings_petals_formatted% -> 1,250
 * %blossomsettings_shards%           -> 340
 * %blossomsettings_shards_formatted% -> 1,340
 */
public final class PetalsPlaceholders extends PlaceholderExpansion {

    private final BlossomSettings plugin;

    public PetalsPlaceholders(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "blossomsettings";
    }

    @Override
    public String getAuthor() {
        return "BlossomSMP";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "0";
        }
        String p = params.toLowerCase();
        if (p.startsWith("shards")) {
            long shards = plugin.shards().get(player.getUniqueId());
            return p.equals("shards_formatted") ? String.format(java.util.Locale.US, "%,d", shards) : String.valueOf(shards);
        }
        long petals = plugin.petals().get(player.getUniqueId());
        return switch (p) {
            case "petals" -> String.valueOf(petals);
            case "petals_short" -> MoneyNametags.formatMoney(petals);
            case "petals_formatted" -> String.format(java.util.Locale.US, "%,d", petals);
            default -> null;
        };
    }
}
