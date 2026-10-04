package com.blossom.settings;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Stores each player's toggles in plugins/BlossomSettings/data.yml */
public final class SettingsManager {

    private final BlossomSettings plugin;
    private final File file;
    private final Map<UUID, EnumMap<Setting, Boolean>> data = new HashMap<>();

    public SettingsManager(BlossomSettings plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            EnumMap<Setting, Boolean> map = new EnumMap<>(Setting.class);
            for (Setting s : Setting.values()) {
                if (section.contains(s.name())) {
                    map.put(s, section.getBoolean(s.name()));
                }
            }
            data.put(id, map);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, EnumMap<Setting, Boolean>> e : data.entrySet()) {
            for (Map.Entry<Setting, Boolean> s : e.getValue().entrySet()) {
                yaml.set(e.getKey() + "." + s.getKey().name(), s.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    public boolean get(UUID id, Setting setting) {
        EnumMap<Setting, Boolean> map = data.get(id);
        if (map == null) return setting.defaultValue;
        return map.getOrDefault(setting, setting.defaultValue);
    }

    /** Flips the setting and returns the new value. */
    public boolean toggle(UUID id, Setting setting) {
        boolean now = !get(id, setting);
        data.computeIfAbsent(id, k -> new EnumMap<>(Setting.class)).put(setting, now);
        save();
        return now;
    }
}
