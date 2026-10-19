package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** The menu you get when you right-click a placed spawner. */
public final class SpawnerMenu implements Listener {

    private static final int INFO = 4;
    private static final int COLLECT = 11;
    private static final int SELL = 13;
    private static final int XP = 15;
    private static final int SPEED = 21;
    private static final int STORAGE = 23;

    private static final class Holder implements InventoryHolder {
        private final String key;
        private Inventory inventory;

        Holder(String key) {
            this.key = key;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlossomSettings plugin;

    public SpawnerMenu(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    public static void open(BlossomSettings plugin, Player player, SpawnerManager.Data d) {
        Holder holder = new Holder(d.key);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("✿ " + SpawnerManager.nice(d.type) + " Spawner x" + d.stack, SpawnerManager.PINK)
                        .decoration(TextDecoration.BOLD, true));
        holder.inventory = inv;
        render(plugin, inv, d, player);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.3f);
    }

    private static void render(BlossomSettings plugin, Inventory inv, SpawnerManager.Data d, Player player) {
        SpawnerManager sm = plugin.spawners();
        ItemStack filler = item(Material.PINK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        int stored = d.storedItems();
        inv.setItem(INFO, item(Material.SPAWNER, "&d&l" + SpawnerManager.nice(d.type) + " Spawner &fx" + d.stack, List.of(
                "&7Makes drops every &f" + sm.intervalSeconds(d) + "s",
                "&7Speed: &fLevel " + d.speedLevel + "/" + sm.maxLevel("speed"),
                "&7Storage: &fLevel " + d.storageLevel + "/" + sm.maxLevel("storage"),
                "",
                "&7Stack more by right-clicking",
                "&7this spawner with the same type")));

        List<String> dropLore = new ArrayList<>();
        dropLore.add("&7Stored: &f" + String.format("%,d", stored) + " &7/ &f" + String.format("%,d", sm.itemCapacity(d)) + " &7items");
        int shown = 0;
        for (Map.Entry<Material, Integer> e : d.drops.entrySet()) {
            if (shown++ >= 6) {
                dropLore.add("&8...and more");
                break;
            }
            dropLore.add("&8• &f" + e.getValue() + "x " + pretty(e.getKey()));
        }
        dropLore.add("");
        dropLore.add("&#FFB6C1Click to collect");
        inv.setItem(COLLECT, item(Material.CHEST, "&a&lCollect Drops", dropLore));

        double worth = Seller.quote(d);
        inv.setItem(SELL, item(Material.SUNFLOWER, "&6&lSell Drops", List.of(
                worth >= 0 ? "&7Worth about &a" + money(plugin, worth) : "&cSelling needs BlossomEconomy",
                "",
                "&#FFB6C1Click to sell everything")));

        inv.setItem(XP, item(Material.EXPERIENCE_BOTTLE, "&e&lCollect XP", List.of(
                "&7Stored: &f" + String.format("%,d", d.xp) + " &7/ &f" + String.format("%,d", sm.xpCapacity(d)) + " &7XP",
                "",
                "&#FFB6C1Click to take it")));

        inv.setItem(SPEED, upgradeItem(plugin, Material.SUGAR, "Speed", "speed", d.speedLevel,
                "&7Drops come faster", player));
        inv.setItem(STORAGE, upgradeItem(plugin, Material.BARREL, "Storage", "storage", d.storageLevel,
                "&7Holds more drops and XP", player));
    }

    private static ItemStack upgradeItem(BlossomSettings plugin, Material icon, String name, String which,
                                         int level, String what, Player player) {
        SpawnerManager sm = plugin.spawners();
        int max = sm.maxLevel(which);
        if (level >= max) {
            return item(icon, "&b&l" + name + " Upgrade", List.of(what, "", "&aMaxed out! &7(Level " + level + ")"));
        }
        long cost = sm.upgradeCost(which, level + 1);
        long have = plugin.shards().get(player.getUniqueId());
        return item(icon, "&b&l" + name + " Upgrade", List.of(
                what,
                "&7Level &f" + level + " &7→ &f" + (level + 1),
                "&7Cost: &b✦ " + ShardsManager.format(cost),
                "",
                have >= cost ? "&#FFB6C1Click to upgrade" : "&cYou have ✦ " + ShardsManager.format(have)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        SpawnerManager.Data d = plugin.spawners().byKey(holder.key);
        if (d == null) {
            p.closeInventory();
            return;
        }
        switch (e.getRawSlot()) {
            case COLLECT -> collect(p, d);
            case SELL -> sell(p, d);
            case XP -> {
                if (d.xp > 0) {
                    p.giveExp(d.xp);
                    p.sendMessage(Component.text("Took " + d.xp + " XP.", SpawnerManager.PINK));
                    d.xp = 0;
                    plugin.spawners().markDirty();
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1f);
                }
            }
            case SPEED -> upgrade(p, d, "speed");
            case STORAGE -> upgrade(p, d, "storage");
            default -> {
                return;
            }
        }
        render(plugin, e.getView().getTopInventory(), d, p);
    }

    private void collect(Player p, SpawnerManager.Data d) {
        int taken = 0;
        Iterator<Map.Entry<Material, Integer>> it = d.drops.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Material, Integer> e = it.next();
            int amount = e.getValue();
            while (amount > 0) {
                int n = Math.min(e.getKey().getMaxStackSize(), amount);
                Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(e.getKey(), n));
                int notAdded = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                taken += n - notAdded;
                amount -= n - notAdded;
                if (notAdded > 0) break; // inventory full
            }
            if (amount <= 0) {
                it.remove();
            } else {
                e.setValue(amount);
                p.sendMessage(Component.text("Your inventory is full. The rest is still in the spawner.", NamedTextColor.RED));
                break;
            }
        }
        if (taken > 0) {
            plugin.spawners().markDirty();
            p.sendMessage(Component.text("Collected " + taken + " items.", SpawnerManager.PINK));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1f);
        }
    }

    private void sell(Player p, SpawnerManager.Data d) {
        Economy eco = plugin.economy();
        if (eco == null || d.drops.isEmpty()) return;
        double total = Seller.sellAll(d);
        if (total < 0) {
            p.sendMessage(Component.text("Selling needs BlossomEconomy to be running.", NamedTextColor.RED));
            return;
        }
        if (total > 0) {
            eco.depositPlayer(p, total);
            plugin.spawners().markDirty();
            p.sendMessage(Component.text("Sold spawner drops for " + eco.format(total) + ".", SpawnerManager.PINK));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        } else {
            p.sendMessage(Component.text("Nothing in here can be sold.", NamedTextColor.RED));
        }
    }

    private void upgrade(Player p, SpawnerManager.Data d, String which) {
        SpawnerManager sm = plugin.spawners();
        int level = which.equals("speed") ? d.speedLevel : d.storageLevel;
        if (level >= sm.maxLevel(which)) return;
        long cost = sm.upgradeCost(which, level + 1);
        if (cost < 0 || !plugin.shards().withdraw(p.getUniqueId(), cost)) {
            p.sendMessage(Component.text("You need ✦ " + ShardsManager.format(cost) + " for that.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (which.equals("speed")) d.speedLevel++;
        else d.storageLevel++;
        sm.markDirty();
        sm.save();
        p.sendMessage(Component.text("Upgraded " + which + " to level " + (level + 1) + "!", SpawnerManager.PINK));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    /* ---------------- helpers ---------------- */

    private static String money(BlossomSettings plugin, double amount) {
        Economy eco = plugin.economy();
        return eco == null ? String.valueOf((long) amount) : eco.format(amount);
    }

    static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(color(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(color(l));
        meta.lore(lines);
        it.setItemMeta(meta);
        return it;
    }

    private static Component color(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                .character('&').hexCharacter('#').hexColors().build()
                .deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Talks to BlossomEconomy's market (by name, so there's no hard dependency). */
    static final class Seller {
        private Seller() {
        }

        private static Object market() {
            try {
                Plugin be = Bukkit.getPluginManager().getPlugin("BlossomEconomy");
                if (be == null || !be.isEnabled()) return null;
                return be.getClass().getMethod("getMarket").invoke(be);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Total value of everything stored, or -1 if BlossomEconomy isn't there. */
        static double quote(SpawnerManager.Data d) {
            Object market = market();
            if (market == null) return -1;
            try {
                Method canSell = market.getClass().getMethod("canSell", ItemStack.class);
                Method quote = market.getClass().getMethod("quoteSell", ItemStack.class);
                double total = 0;
                for (Map.Entry<Material, Integer> e : d.drops.entrySet()) {
                    ItemStack stack = new ItemStack(e.getKey(), e.getValue());
                    if (Boolean.TRUE.equals(canSell.invoke(market, stack))) {
                        total += (double) quote.invoke(market, stack);
                    }
                }
                return total;
            } catch (Throwable t) {
                return -1;
            }
        }

        /** Sells everything sellable, removes it from storage, returns the money (-1 = couldn't sell). */
        static double sellAll(SpawnerManager.Data d) {
            Object market = market();
            if (market == null) return -1;
            try {
                Method canSell = market.getClass().getMethod("canSell", ItemStack.class);
                Method sell = market.getClass().getMethod("sell", ItemStack.class);
                double total = 0;
                Iterator<Map.Entry<Material, Integer>> it = d.drops.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Material, Integer> e = it.next();
                    ItemStack stack = new ItemStack(e.getKey(), e.getValue());
                    if (Boolean.TRUE.equals(canSell.invoke(market, stack))) {
                        double value = (double) sell.invoke(market, stack);
                        if (value > 0) {
                            total += value;
                            it.remove();
                        }
                    }
                }
                return total;
            } catch (Throwable t) {
                return -1;
            }
        }
    }
}
