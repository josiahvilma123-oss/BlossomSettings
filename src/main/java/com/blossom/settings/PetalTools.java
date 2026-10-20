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
 *  - Blossom Pickaxe:  mines a 3x3 area
 *  - Petal Pickaxe:    Haste + auto-smelts what it mines
 *  - Blossom Axe:      chops wood faster (block break speed bonus)
 */
public final class PetalTools implements Listener {

    private static final Set<Material> NEVER_BREAK = java.util.EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.END_PORTAL,
            Material.NETHER_PORTAL, Material.END_GATEWAY, Material.REINFORCED_DEEPSLATE,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.LIGHT, Material.SPAWNER);

    public static final TextColor AMETHYST = TextColor.color(0xFF69B4); // blossom pink
    private static final TextColor LIGHT_PINK = TextColor.color(0xFFB6C1);

    private final BlossomSettings plugin;
    private final NamespacedKey toolKey;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<Material, ItemStack> smelts = new HashMap<>();
    private final NamespacedKey expiresKey;
    private BukkitTask expiryTask;

    public PetalTools(BlossomSettings plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "petal_tool");
        this.expiresKey = new NamespacedKey(plugin, "expires_at");
    }

    public void start() {
        loadSmelts();
        // Every 30 seconds: update the "time left" line and remove tools that ran out
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expiryTick, 40L, 600L);
    }

    public void stop() {
        if (expiryTask != null) expiryTask.cancel();
    }

    private long lifetimeMs() {
        return Math.max(1, plugin.getConfig().getLong("tools.lifetime-hours", 24)) * 3_600_000L;
    }

    /* ---------------- Item creation / detection ---------------- */

    /** Makes a fresh tool. Its 24-hour timer starts now. No enchants: players add their own. */
    public ItemStack createTool(ShopItem s) {
        Material base = s == ShopItem.AMETHYST_AXE ? Material.NETHERITE_AXE : Material.NETHERITE_PICKAXE;
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✿ " + s.displayName + " ✿", AMETHYST)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, s.name());
        long expires = System.currentTimeMillis() + lifetimeMs();
        meta.getPersistentDataContainer().set(expiresKey, PersistentDataType.LONG, expires);
        meta.lore(lore(s, expires));
        item.setItemMeta(meta);
        if (s == ShopItem.AMETHYST_AXE) addAxeSpeed(item);
        return item;
    }

    private double axeBonus() {
        return Math.max(0, plugin.getConfig().getDouble("tools.axe-speed-bonus", 0.5));
    }

    /**
     * Makes the axe break blocks faster. Adding any attribute replaces the item's normal
     * attack stats, so the netherite axe's damage (10) and speed (1.0) are added back too.
     */
    private void addAxeSpeed(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta.hasAttributeModifiers() && meta.getAttributeModifiers(org.bukkit.attribute.Attribute.BLOCK_BREAK_SPEED) != null
                && !meta.getAttributeModifiers(org.bukkit.attribute.Attribute.BLOCK_BREAK_SPEED).isEmpty()) {
            return; // already has it
        }
        org.bukkit.inventory.EquipmentSlotGroup hand = org.bukkit.inventory.EquipmentSlotGroup.MAINHAND;
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.BLOCK_BREAK_SPEED, new org.bukkit.attribute.AttributeModifier(
                new NamespacedKey(plugin, "axe_speed"), axeBonus(),
                org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR, hand));
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                new NamespacedKey(plugin, "axe_damage"), 9.0,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, hand));
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                new NamespacedKey(plugin, "axe_attack_speed"), -3.0,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, hand));
        item.setItemMeta(meta);
    }

    private List<Component> lore(ShopItem s, long expires) {
        List<Component> lore = new ArrayList<>();
        if (s == ShopItem.AMETHYST_SMELTER) {
            lore.add(line("✦ Auto-smelts what you mine"));
            lore.add(line("✦ Ores, cobblestone, sand and more"));
        } else if (s == ShopItem.AMETHYST_AXE) {
            lore.add(line("✦ Chops " + Math.round(axeBonus() * 100) + "% faster"));
        } else {
            lore.add(line("✦ Mines a 3x3 area"));
        }
        lore.add(line("✦ Enchant it however you like"));
        lore.add(Component.empty());
        long left = expires - System.currentTimeMillis();
        lore.add(Component.text("⌚ Disappears in " + timeLeft(left), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    private static String timeLeft(long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : Math.max(1, minutes) + "m";
    }

    private static Component line(String text) {
        return Component.text(text, LIGHT_PINK).decoration(TextDecoration.ITALIC, false);
    }

    /** Returns which Blossom tool this is, or null for any other item. */
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

    /* ---------------- 24-hour timer ---------------- */

    /** True if this tool has run out. Tools from before the timer existed get 24 hours from now. */
    private boolean expired(ItemStack item, ShopItem kind) {
        ItemMeta meta = item.getItemMeta();
        Long expires = meta.getPersistentDataContainer().get(expiresKey, PersistentDataType.LONG);
        if (expires == null) {
            expires = System.currentTimeMillis() + lifetimeMs();
            meta.getPersistentDataContainer().set(expiresKey, PersistentDataType.LONG, expires);
        }
        if (System.currentTimeMillis() >= expires) {
            return true;
        }
        meta.lore(lore(kind, expires));
        item.setItemMeta(meta);
        return false;
    }

    private void expiryTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            checkInventory(p);
        }
    }

    private void checkInventory(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            ShopItem kind = toolOf(item);
            if (kind == null) continue;
            if (expired(item, kind)) {
                p.getInventory().setItem(slot, null);
                p.sendMessage(Component.text("Your " + kind.displayName + " ran out after 24 hours.", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            } else {
                if (kind == ShopItem.AMETHYST_AXE) addAxeSpeed(item);
                p.getInventory().setItem(slot, item);
            }
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
        if (expired(tool, kind)) {
            e.setCancelled(true);
            p.getInventory().setItemInMainHand(null);
            p.sendMessage(Component.text("Your " + kind.displayName + " ran out after 24 hours.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return;
        }

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

        // Only the Blossom Pickaxe breaks 3x3 (the axe just chops faster)
        if (kind != ShopItem.AMETHYST_PICKAXE) return;

        // 3x3: work out which face the player is mining
        RayTraceResult hit = p.rayTraceBlocks(6.0, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlockFace() == null || !origin.equals(hit.getHitBlock())) return;

        busy.add(p.getUniqueId());
        try {
            breakArea(p, tool, origin, hit.getHitBlockFace());
        } finally {
            busy.remove(p.getUniqueId());
        }
    }

    /**
     * DonutSMP-style 3x3: the 3x3 square turns with the way you're looking.
     *  - looking down or up:      a flat 3x3 of floor / ceiling
     *  - looking forward/sideways: a 3x3 wall
     */
    private List<Block> area(Block origin, BlockFace face) {
        List<Block> out = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue; // the game already breaks the middle one
                out.add(switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a);
                });
            }
        }
        return out;
    }

    private void breakArea(Player p, ItemStack tool, Block origin, BlockFace face) {
        for (Block target : area(origin, face)) {
            {
                Material type = target.getType();
                if (type.isAir() || type.getHardness() < 0 || target.isLiquid()) continue;
                if (NEVER_BREAK.contains(type)) continue; // bedrock, portals, barriers...
                if (target.getState(false) instanceof TileState) continue; // chests, spawners, signs...
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
                target.getWorld().spawnParticle(Particle.BLOCK, loc, 6, 0.25, 0.25, 0.25, target.getBlockData());
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
