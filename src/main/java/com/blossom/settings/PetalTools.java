package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The custom tools sold in /petalsshop.
 *  - Blossom Pickaxe:  Haste + mines a 3x3 area
 *  - Petal Pickaxe:    Haste + auto-smelts what it mines
 *  - Blossom Axe:      Haste + chops a 3x3 area
 */
public final class PetalTools implements Listener {

    public static final TextColor AMETHYST = TextColor.color(0xFF69B4); // blossom pink
    private static final TextColor LIGHT_PINK = TextColor.color(0xFFB6C1);

    private final BlossomSettings plugin;
    private final NamespacedKey toolKey;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<Material, ItemStack> smelts = new HashMap<>();
    private BukkitTask hasteTask;

    public PetalTools(BlossomSettings plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "petal_tool");
    }

    public void start() {
        loadSmelts();
        hasteTask = Bukkit.getScheduler().runTaskTimer(plugin, this::hasteTick, 20L, 20L);
    }

    public void stop() {
        if (hasteTask != null) hasteTask.cancel();
    }

    /* ---------------- Item creation / detection ---------------- */

    public ItemStack createTool(ShopItem s) {
        Material base = s == ShopItem.AMETHYST_AXE ? Material.NETHERITE_AXE : Material.NETHERITE_PICKAXE;
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("✿ " + s.displayName + " ✿", AMETHYST)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        int haste = Math.max(1, plugin.getConfig().getInt("petals-shop.haste-level", 2));
        List<Component> lore = new ArrayList<>();
        lore.add(line("✦ Haste " + roman(haste) + " while held"));
        if (s == ShopItem.AMETHYST_SMELTER) {
            lore.add(line("✦ Auto-smelts what you mine"));
            lore.add(line("✦ Ores, logs, sand and more"));
        } else if (s == ShopItem.AMETHYST_AXE) {
            lore.add(line("✦ Chops a 3x3 area"));
        } else {
            lore.add(line("✦ Mines a 3x3 area"));
        }
        meta.lore(lore);

        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, s.name());
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return Component.text(text, LIGHT_PINK).decoration(TextDecoration.ITALIC, false);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    /** Returns which Amethyst tool this is, or null for any other item. */
    public ShopItem toolOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return ShopItem.valueOf(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /* ---------------- Haste ---------------- */

    private void hasteTick() {
        int amp = Math.max(0, plugin.getConfig().getInt("petals-shop.haste-level", 2) - 1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (toolOf(p.getInventory().getItemInMainHand()) == null) continue;
            PotionEffect current = p.getPotionEffect(PotionEffectType.HASTE);
            if (current != null && current.getAmplifier() > amp) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, amp, true, false, false));
        }
    }

    /* ---------------- Breaking ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        ShopItem kind = toolOf(tool);
        if (kind == null) return;

        Block origin = e.getBlock();

        if (kind == ShopItem.AMETHYST_SMELTER) {
            if (e.isDropItems()) {
                List<ItemStack> smelted = smeltAll(origin.getDrops(tool, p));
                if (smelted != null) {
                    e.setDropItems(false);
                    Location loc = origin.getLocation().add(0.5, 0.5, 0.5);
                    for (ItemStack drop : smelted) {
                        origin.getWorld().dropItemNaturally(loc, drop);
                    }
                }
            }
            return;
        }

        // 3x3 tools: work out which face the player is mining
        RayTraceResult hit = p.rayTraceBlocks(6.0, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlockFace() == null || !origin.equals(hit.getHitBlock())) return;

        busy.add(p.getUniqueId());
        try {
            breakArea(p, tool, origin, hit.getHitBlockFace());
        } finally {
            busy.remove(p.getUniqueId());
        }
    }

    private void breakArea(Player p, ItemStack tool, Block origin, BlockFace face) {
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;

                Block target = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a);
                };

                Material type = target.getType();
                if (type.isAir() || type.getHardness() < 0 || target.isLiquid()) continue;
                if (target.getState() instanceof TileState) continue; // chests, spawners, signs...
                if (!target.isPreferredTool(tool)) continue;

                // Ask other plugins (GriefPrevention etc.) if this break is allowed
                BlockBreakEvent event = new BlockBreakEvent(target, p);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) continue;

                Collection<ItemStack> drops = target.getDrops(tool, p);
                Location loc = target.getLocation().add(0.5, 0.5, 0.5);
                if (event.isDropItems()) {
                    for (ItemStack drop : drops) {
                        target.getWorld().dropItemNaturally(loc, drop);
                    }
                }
                int xp = event.getExpToDrop();
                if (xp > 0) {
                    target.getWorld().spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(xp));
                }
                target.getWorld().spawnParticle(Particle.BLOCK, loc, 12, 0.25, 0.25, 0.25, target.getBlockData());
                target.setType(Material.AIR);

                if (wear(p, tool)) return; // tool broke
            }
        }
    }

    /** Uses up one durability point (respecting Unbreaking). Returns true if the tool broke. */
    private boolean wear(Player p, ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return false;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return false;

        damageable.setDamage(damageable.getDamage() + 1);
        tool.setItemMeta(damageable);
        p.getInventory().setItemInMainHand(tool);

        if (damageable.getDamage() >= tool.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return true;
        }
        return false;
    }

    /* ---------------- Auto-smelt ---------------- */

    private void loadSmelts() {
        smelts.clear();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof FurnaceRecipe furnace
                    && furnace.getInputChoice() instanceof RecipeChoice.MaterialChoice choice) {
                for (Material m : choice.getChoices()) {
                    smelts.putIfAbsent(m, furnace.getResult());
                }
            }
        }
    }

    /** Anything a furnace can cook gets smelted (raw ores, cobblestone, sand, logs...), except food. */
    private boolean smeltable(Material m) {
        return smelts.containsKey(m) && !m.isEdible();
    }

    /** Returns the smelted drop list, or null if nothing in it could be smelted. */
    private List<ItemStack> smeltAll(Collection<ItemStack> drops) {
        boolean changed = false;
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack drop : drops) {
            ItemStack result = smeltable(drop.getType()) ? smelts.get(drop.getType()) : null;
            if (result != null) {
                ItemStack copy = result.clone();
                copy.setAmount(result.getAmount() * drop.getAmount());
                out.add(copy);
                changed = true;
            } else {
                out.add(drop);
            }
        }
        return changed ? out : null;
    }
}
