package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DonutSMP-style spawners:
 *  - placed spawners never spawn mobs, they fill up with drops + XP instead
 *  - right-click to open the menu (collect, sell into Petals, take XP, upgrade with Shards)
 *  - right-click with the same spawner type to stack them
 *  - Silk Touch picks the whole stack back up (owner only)
 */
public final class SpawnerManager implements Listener {

    static final TextColor PINK = TextColor.color(0xFF69B4);

    /** One placed spawner. */
    public static final class Data {
        final String key;
        final String world;
        final int x, y, z;
        EntityType type;
        int stack = 1;
        UUID owner;
        int speedLevel;
        int storageLevel;
        int xp;
        int progress;
        final Map<Material, Integer> drops = new EnumMap<>(Material.class);

        Data(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = key(world, x, y, z);
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        int storedItems() {
            int total = 0;
            for (int n : drops.values()) total += n;
            return total;
        }
    }

    /** One loot line from config, e.g. "BONE 0 2" or "IRON_INGOT 1 1 5". */
    private record Loot(Material material, int min, int max, double chance) { }

    private final BlossomSettings plugin;
    private final File file;
    private final NamespacedKey typeKey;
    private final Map<String, Data> spawners = new HashMap<>();
    private final Map<EntityType, List<Loot>> loot = new EnumMap<>(EntityType.class);
    private BukkitTask task;
    private boolean dirty;
    private int ticks;

    public SpawnerManager(BlossomSettings plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
        this.typeKey = new NamespacedKey(plugin, "spawner_type");
    }

    static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public Data get(Block block) {
        return block.getType() == Material.SPAWNER ? spawners.get(key(block)) : null;
    }

    Data byKey(String key) {
        return spawners.get(key);
    }

    /* ---------------- Settings ---------------- */

    private boolean enabled() {
        return plugin.getConfig().getBoolean("spawners.enabled", true);
    }

    int maxStack() {
        return Math.max(1, plugin.getConfig().getInt("spawners.max-stack", 64));
    }

    /** Seconds between drops for this spawner (speed upgrades make it shorter). */
    int intervalSeconds(Data d) {
        int base = Math.max(1, plugin.getConfig().getInt("spawners.base-interval-seconds", 30));
        double mult = d.speedLevel <= 0 ? 1.0 : listValue("spawners.upgrades.speed-multipliers", d.speedLevel, 1.0);
        return Math.max(1, (int) Math.round(base * mult));
    }

    int itemCapacity(Data d) {
        double mult = d.storageLevel <= 0 ? 1.0 : listValue("spawners.upgrades.storage-multipliers", d.storageLevel, 1.0);
        return (int) Math.min(Integer.MAX_VALUE / 2, plugin.getConfig().getLong("spawners.base-storage", 512) * d.stack * mult);
    }

    int xpCapacity(Data d) {
        double mult = d.storageLevel <= 0 ? 1.0 : listValue("spawners.upgrades.storage-multipliers", d.storageLevel, 1.0);
        return (int) Math.min(Integer.MAX_VALUE / 2, plugin.getConfig().getLong("spawners.base-xp-storage", 1000) * d.stack * mult);
    }

    int maxLevel(String which) {
        return plugin.getConfig().getLongList("spawners.upgrades." + which + "-costs").size();
    }

    /** Shards to go from level-1 to level. */
    long upgradeCost(String which, int level) {
        List<Long> costs = plugin.getConfig().getLongList("spawners.upgrades." + which + "-costs");
        return level >= 1 && level <= costs.size() ? costs.get(level - 1) : -1;
    }

    double listValue(String path, int level, double fallback) {
        List<Double> values = plugin.getConfig().getDoubleList(path);
        return level >= 1 && level <= values.size() ? values.get(level - 1) : fallback;
    }

    private void loadLoot() {
        loot.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("spawners.loot");
        if (section == null) return;
        for (String typeName : section.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown mob in spawners.loot: " + typeName);
                continue;
            }
            List<Loot> lines = new ArrayList<>();
            for (String line : section.getStringList(typeName)) {
                Loot l = parseLoot(line);
                if (l == null) {
                    plugin.getLogger().warning("Bad loot line for " + typeName + ": " + line);
                } else {
                    lines.add(l);
                }
            }
            loot.put(type, lines);
        }
    }

    /** "BONE 0 2" = 0 to 2 bones. "IRON_INGOT 1 1 5" = 1 iron ingot, 5% of the time. */
    static Loot parseLoot(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) return null;
        Material m = Material.matchMaterial(parts[0]);
        if (m == null || !m.isItem()) return null;
        try {
            int min = Integer.parseInt(parts[1]);
            int max = Integer.parseInt(parts[2]);
            double chance = parts.length >= 4 ? Double.parseDouble(parts[3].replace("%", "")) : 100;
            if (min < 0 || max < min) return null;
            return new Loot(m, min, max, chance);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /* ---------------- Start / stop / saving ---------------- */

    public void start() {
        loadLoot();
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void reload() {
        loadLoot();
    }

    public void stop() {
        if (task != null) task.cancel();
        dirty = true;
        save();
    }

    private void load() {
        spawners.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String k : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(k);
            String[] p = k.split(";");
            if (s == null || p.length != 4) continue;
            try {
                Data d = new Data(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                d.type = EntityType.valueOf(s.getString("type", "PIG"));
                d.stack = Math.max(1, s.getInt("stack", 1));
                String owner = s.getString("owner");
                d.owner = owner == null ? null : UUID.fromString(owner);
                d.speedLevel = s.getInt("speed", 0);
                d.storageLevel = s.getInt("storage", 0);
                d.xp = s.getInt("xp", 0);
                ConfigurationSection drops = s.getConfigurationSection("drops");
                if (drops != null) {
                    for (String m : drops.getKeys(false)) {
                        Material mat = Material.matchMaterial(m);
                        if (mat != null) d.drops.put(mat, drops.getInt(m));
                    }
                }
                spawners.put(d.key, d);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping broken spawner entry " + k);
            }
        }
        plugin.getLogger().info("Loaded " + spawners.size() + " spawners.");
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Data d : spawners.values()) {
            ConfigurationSection s = yaml.createSection(d.key);
            s.set("type", d.type.name());
            s.set("stack", d.stack);
            s.set("owner", d.owner == null ? null : d.owner.toString());
            s.set("speed", d.speedLevel);
            s.set("storage", d.storageLevel);
            s.set("xp", d.xp);
            for (Map.Entry<Material, Integer> e : d.drops.entrySet()) {
                s.set("drops." + e.getKey().name(), e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save spawners.yml: " + ex.getMessage());
        }
    }

    void markDirty() {
        dirty = true;
    }

    /* ---------------- Making drops ---------------- */

    private void tick() {
        if (!enabled()) return;
        List<String> gone = new ArrayList<>();
        for (Data d : spawners.values()) {
            World w = Bukkit.getWorld(d.world);
            // Only works while someone is around (the chunk is loaded), like normal spawners
            if (w == null || !w.isChunkLoaded(d.x >> 4, d.z >> 4)) continue;
            if (w.getBlockAt(d.x, d.y, d.z).getType() != Material.SPAWNER) {
                gone.add(d.key); // removed some other way (WorldEdit, /setblock...)
                continue;
            }
            d.progress++;
            if (d.progress < intervalSeconds(d)) continue;
            d.progress = 0;
            generate(d);
        }
        if (!gone.isEmpty()) {
            gone.forEach(spawners::remove);
            dirty = true;
        }
        if (++ticks % 60 == 0) save(); // every minute
    }

    private void generate(Data d) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int room = itemCapacity(d) - d.storedItems();
        List<Loot> table = loot.getOrDefault(d.type, List.of());
        for (int mob = 0; mob < d.stack && room > 0; mob++) {
            for (Loot l : table) {
                if (random.nextDouble() * 100 >= l.chance()) continue;
                int amount = l.min() == l.max() ? l.min() : random.nextInt(l.min(), l.max() + 1);
                amount = Math.min(amount, room);
                if (amount <= 0) continue;
                d.drops.merge(l.material(), amount, Integer::sum);
                room -= amount;
            }
        }
        int xpEach = Math.max(0, plugin.getConfig().getInt("spawners.xp-per-mob", 3));
        d.xp = Math.min(xpCapacity(d), d.xp + xpEach * d.stack);
        dirty = true;
    }

    /* ---------------- Spawner items ---------------- */

    public ItemStack createItem(EntityType type, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, Math.min(64, amount)));
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        if (meta.getBlockState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(type);
            meta.setBlockState(spawner);
        }
        meta.displayName(Component.text(nice(type) + " Spawner", PINK)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                gray("Collects drops and XP for you"),
                gray("Right-click it to open the menu"),
                gray("Right-click it with another " + nice(type)),
                gray("Spawner to stack them")));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    /** ZOMBIE -> "Zombie", IRON_GOLEM -> "Iron Golem" */
    static String nice(EntityType type) {
        StringBuilder sb = new StringBuilder();
        for (String word : type.name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** Which mob a spawner item holds (our tag first, then the vanilla block data). */
    public EntityType typeOf(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return item != null && item.getType() == Material.SPAWNER ? EntityType.PIG : null;
        }
        ItemMeta meta = item.getItemMeta();
        String tagged = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (tagged != null) {
            try {
                return EntityType.valueOf(tagged);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) {
            return cs.getSpawnedType();
        }
        return EntityType.PIG;
    }

    private boolean canManage(Player p, Data d) {
        return d.owner == null || d.owner.equals(p.getUniqueId())
                || p.hasPermission("blossomsettings.spawners.admin");
    }

    private String ownerName(Data d) {
        if (d.owner == null) return "nobody";
        String name = Bukkit.getOfflinePlayer(d.owner).getName();
        return name == null ? "someone" : name;
    }

    /* ---------------- Events ---------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!enabled() || e.getBlockPlaced().getType() != Material.SPAWNER) return;
        EntityType type = typeOf(e.getItemInHand());
        Block block = e.getBlockPlaced();
        Data d = new Data(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        d.type = type;
        d.owner = e.getPlayer().getUniqueId();
        spawners.put(d.key, d);
        dirty = true;
        // Players without OP can't place spawners with mob data, so set the mob ourselves
        Bukkit.getScheduler().runTask(plugin, () -> setBlockType(block, type));
        e.getPlayer().sendMessage(Component.text("Placed a " + nice(type) + " Spawner. Right-click it to open it.", PINK));
    }

    private void setBlockType(Block block, EntityType type) {
        if (block.getType() != Material.SPAWNER) return;
        BlockState state = block.getState();
        if (state instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            cs.update(true, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!enabled() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;
        Data d = get(block);
        if (d == null) return;
        if (e.useInteractedBlock() == Event.Result.DENY) return; // a claim plugin said no

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        // Let people sneak-place normal blocks against a spawner
        if (p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.SPAWNER && !hand.getType().isAir()) {
            return;
        }
        e.setCancelled(true);

        if (!canManage(p, d)) {
            p.sendMessage(Component.text("This spawner belongs to " + ownerName(d) + ".", NamedTextColor.RED));
            return;
        }

        if (hand.getType() == Material.SPAWNER) {
            stack(p, d, hand);
            return;
        }
        SpawnerMenu.open(plugin, p, d);
    }

    private void stack(Player p, Data d, ItemStack hand) {
        EntityType type = typeOf(hand);
        if (type != d.type) {
            p.sendMessage(Component.text("You can only stack " + nice(d.type) + " Spawners on this one.", NamedTextColor.RED));
            return;
        }
        int room = maxStack() - d.stack;
        if (room <= 0) {
            p.sendMessage(Component.text("This spawner is full (" + maxStack() + " max).", NamedTextColor.RED));
            return;
        }
        int add = Math.min(room, p.isSneaking() ? hand.getAmount() : 1);
        hand.setAmount(hand.getAmount() - add);
        d.stack += add;
        dirty = true;
        p.sendMessage(Component.text("Stacked! This spawner is now x" + d.stack + ".", PINK));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
    }

    /** Placed (managed) spawners never spawn mobs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent e) {
        if (!enabled() || e.getSpawner() == null) return;
        if (spawners.containsKey(key(e.getSpawner().getBlock()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!enabled() || block.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean silk = tool.containsEnchantment(Enchantment.SILK_TOUCH);
        boolean needsSilk = plugin.getConfig().getBoolean("spawners.needs-silk-touch", true);
        Data d = get(block);

        if (d == null) {
            // A natural dungeon spawner: Silk Touch lets you take it
            if (silk && block.getState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) {
                e.setExpToDrop(0);
                give(p, createItem(cs.getSpawnedType(), 1), block.getLocation());
            }
            return;
        }

        if (!canManage(p, d)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This spawner belongs to " + ownerName(d) + ".", NamedTextColor.RED));
            return;
        }
        if (needsSilk && !silk && p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Use a Silk Touch pickaxe to pick up this spawner.", NamedTextColor.RED));
            return;
        }

        e.setExpToDrop(0);
        Location loc = block.getLocation();
        int left = d.stack;
        while (left > 0) {
            int n = Math.min(64, left);
            give(p, createItem(d.type, n), loc);
            left -= n;
        }
        for (Map.Entry<Material, Integer> drop : d.drops.entrySet()) {
            int amount = drop.getValue();
            while (amount > 0) {
                int n = Math.min(drop.getKey().getMaxStackSize(), amount);
                give(p, new ItemStack(drop.getKey(), n), loc);
                amount -= n;
            }
        }
        if (d.xp > 0) p.giveExp(d.xp);
        spawners.remove(d.key);
        dirty = true;
        save();
        p.sendMessage(Component.text("Picked up " + d.stack + "x " + nice(d.type) + " Spawner.", PINK));
    }

    /** Puts an item in the player's inventory, dropping whatever doesn't fit. */
    static void give(Player p, ItemStack item, Location fallback) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(fallback == null ? p.getLocation() : fallback, left);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }
}
