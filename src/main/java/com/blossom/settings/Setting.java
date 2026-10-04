package com.blossom.settings;

import org.bukkit.Material;

public enum Setting {
    MOB_SPAWNS("Mob Spawns", true, Material.ZOMBIE_HEAD),
    PHANTOM_SPAWNING("Phantom Spawning", true, Material.PHANTOM_MEMBRANE),
    NIGHT_VISION("Night Vision", false, Material.GOLDEN_CARROT),
    MONEY_NAMETAGS("Money Nametags", true, Material.GOLD_INGOT);

    public final String label;
    public final boolean defaultValue;
    public final Material icon;

    Setting(String label, boolean defaultValue, Material icon) {
        this.label = label;
        this.defaultValue = defaultValue;
        this.icon = icon;
    }
}
