package com.blossom.settings;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shard balances (the /ps currency), stored in plugins/BlossomSettings/shards.yml */
public final class ShardsManager {

    private final BlossomSettings plugin;
    private final File file;
    private final Map<UUID, Long> balances = new HashMap<>();
    private boolean dirty;

    public ShardsManager(BlossomSettings plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shards.yml");
    }

    public void load() {
        balances.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), yaml.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // not a UUID, skip it
            }
        }
    }

    /** Saves only if something changed since the last save. */
    public void save() {
        if (!dirty) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            yaml.set(e.getKey().toString(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save shards.yml: " + ex.getMessage());
        }
    }

    public long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public void set(UUID id, long amount) {
        balances.put(id, Math.max(0, amount));
        dirty = true;
    }

    public long add(UUID id, long amount) {
        long now = Math.max(0, get(id) + amount);
        balances.put(id, now);
        dirty = true;
        return now;
    }

    /** Takes the amount only if the player has enough. Returns false otherwise. */
    public boolean withdraw(UUID id, long amount) {
        if (amount < 0 || get(id) < amount) return false;
        balances.put(id, get(id) - amount);
        dirty = true;
        save();
        return true;
    }

    /** "1,250 Petals" (the /ps currency, saved in shards.yml) */
    public static String format(long amount) {
        return String.format(java.util.Locale.US, "%,d", amount) + (amount == 1 ? " Petal" : " Petals");
    }
}
