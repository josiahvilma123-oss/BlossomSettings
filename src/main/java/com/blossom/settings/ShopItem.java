package com.blossom.settings;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/** Everything /petalsshop sells. Prices can be changed in config.yml. */
public enum ShopItem {
    // Custom tools
    // (the AMETHYST_ names are kept so tools players already own keep working)
    AMETHYST_PICKAXE(Category.TOOL, "blossom_pickaxe", "Blossom Pickaxe", 150000, null),
    AMETHYST_SMELTER(Category.TOOL, "petal_pickaxe", "Petal Pickaxe", 150000, null),
    AMETHYST_AXE(Category.TOOL, "blossom_axe", "Blossom Axe", 100000, null),

    // Spawners
    COW_SPAWNER(Category.SPAWNER, "cow_spawner", "Cow Spawner", 25000, EntityType.COW),
    PIG_SPAWNER(Category.SPAWNER, "pig_spawner", "Pig Spawner", 25000, EntityType.PIG),
    SHEEP_SPAWNER(Category.SPAWNER, "sheep_spawner", "Sheep Spawner", 25000, EntityType.SHEEP),
    ZOMBIE_SPAWNER(Category.SPAWNER, "zombie_spawner", "Zombie Spawner", 50000, EntityType.ZOMBIE),
    SKELETON_SPAWNER(Category.SPAWNER, "skeleton_spawner", "Skeleton Spawner", 50000, EntityType.SKELETON),
    SPIDER_SPAWNER(Category.SPAWNER, "spider_spawner", "Spider Spawner", 50000, EntityType.SPIDER),
    IRON_GOLEM_SPAWNER(Category.SPAWNER, "iron_golem_spawner", "Iron Golem Spawner", 100000, EntityType.IRON_GOLEM),
    BLAZE_SPAWNER(Category.SPAWNER, "blaze_spawner", "Blaze Spawner", 120000, EntityType.BLAZE),
    ENDERMAN_SPAWNER(Category.SPAWNER, "enderman_spawner", "Enderman Spawner", 120000, EntityType.ENDERMAN);

    public enum Category { TOOL, SPAWNER }

    public final Category category;
    public final String id;
    public final String displayName;
    public final long defaultPrice;
    public final EntityType entity;

    ShopItem(Category category, String id, String displayName, long defaultPrice, EntityType entity) {
        this.category = category;
        this.id = id;
        this.displayName = displayName;
        this.defaultPrice = defaultPrice;
        this.entity = entity;
    }

    public static List<ShopItem> byCategory(Category category) {
        List<ShopItem> out = new ArrayList<>();
        for (ShopItem s : values()) {
            if (s.category == category) out.add(s);
        }
        return out;
    }
}
