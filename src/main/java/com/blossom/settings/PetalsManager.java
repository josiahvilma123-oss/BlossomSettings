package com.blossom.settings;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Petals ARE the server money now: every balance lives in BlossomEconomy (through Vault),
 * so /bal, /pay, /shop, /ah and /petalsshop all use the same Petals.
 */
public final class PetalsManager {

    private final BlossomSettings plugin;
    private final File oldFile;

    public PetalsManager(BlossomSettings plugin) {
        this.plugin = plugin;
        this.oldFile = new File(plugin.getDataFolder(), "petals.yml");
    }

    /** Moves Petals from the old separate petals.yml into everyone's main balance, once. */
    public void load() {
        if (!oldFile.exists()) return;
        // Wait until every plugin (and the economy) has started
        Bukkit.getScheduler().runTask(plugin, this::migrate);
    }

    private void migrate() {
        Economy eco = plugin.economy();
        if (eco == null) {
            plugin.getLogger().warning("Could not move old Petals yet: no economy found. Will try on next restart.");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(oldFile);
        int moved = 0;
        for (String key : yaml.getKeys(false)) {
            try {
                long amount = yaml.getLong(key);
                if (amount > 0) {
                    eco.depositPlayer(Bukkit.getOfflinePlayer(UUID.fromString(key)), amount);
                    moved++;
                }
            } catch (IllegalArgumentException ignored) {
                // not a UUID, skip it
            }
        }
        File done = new File(plugin.getDataFolder(), "petals-old-already-moved.yml");
        if (!oldFile.renameTo(done)) {
            oldFile.delete();
        }
        plugin.getLogger().info("Moved old Petals for " + moved + " players into their main balance.");
    }

    /** Nothing to do: BlossomEconomy saves balances itself. */
    public void save() {
    }

    private OfflinePlayer player(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    public long get(UUID id) {
        Economy eco = plugin.economy();
        return eco == null ? 0 : (long) Math.floor(eco.getBalance(player(id)));
    }

    public void set(UUID id, long amount) {
        Economy eco = plugin.economy();
        if (eco == null) return;
        double now = eco.getBalance(player(id));
        long target = Math.max(0, amount);
        if (target > now) {
            eco.depositPlayer(player(id), target - now);
        } else if (target < now) {
            eco.withdrawPlayer(player(id), now - target);
        }
    }

    public long add(UUID id, long amount) {
        Economy eco = plugin.economy();
        if (eco == null) return 0;
        if (amount > 0) {
            eco.depositPlayer(player(id), amount);
        } else if (amount < 0) {
            double take = Math.min(-amount, eco.getBalance(player(id)));
            if (take > 0) eco.withdrawPlayer(player(id), take);
        }
        return get(id);
    }

    /** Takes the amount only if the player has enough. Returns false otherwise. */
    public boolean withdraw(UUID id, long amount) {
        Economy eco = plugin.economy();
        if (eco == null || !eco.has(player(id), amount)) return false;
        return eco.withdrawPlayer(player(id), amount).transactionSuccess();
    }

    /** "1,250 Petals" (uses the same look as /bal). */
    public String format(long amount) {
        Economy eco = plugin.economy();
        return eco == null ? amount + " Petals" : eco.format(amount);
    }
}
