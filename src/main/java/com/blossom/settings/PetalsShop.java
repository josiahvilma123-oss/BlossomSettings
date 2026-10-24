package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /ps (/petalsshop): 24-hour tools on top, spawners below, paid with Shards. */
public final class PetalsShop implements Listener {

    private static final TextColor PINK = TextColor.color(0xFF69B4);
    private static final int BALANCE_SLOT = 4;
    private static final int TOOLS_LABEL_SLOT = 9;
    private static final int SPAWNERS_LABEL_SLOT = 27;

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlossomSettings plugin;
    private final Map<Integer, ShopItem> layout = new HashMap<>();

    public PetalsShop(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    /** Lays the shop out again. Items priced 0 in config.yml are hidden. */
    private void buildLayout() {
        layout.clear();
        List<ShopItem> tools = forSale(ShopItem.Category.TOOL);
        List<ShopItem> spawners = forSale(ShopItem.Category.SPAWNER);
        place(tools, 9);
        int firstRow = (spawners.size() + 1) / 2;
        place(spawners.subList(0, firstRow), 27);
        place(spawners.subList(firstRow, spawners.size()), 36);
    }

    private List<ShopItem> forSale(ShopItem.Category category) {
        List<ShopItem> out = new ArrayList<>();
        for (ShopItem item : ShopItem.byCategory(category)) {
            if (plugin.price(item) > 0) out.add(item);
        }
        return out;
    }

    /** Centers items in the 7 middle slots of a row. */
    private void place(List<ShopItem> items, int rowStart) {
        int offset = (7 - items.size()) / 2;
        for (int i = 0; i < items.size(); i++) {
            layout.put(rowStart + 1 + offset + i, items.get(i));
        }
    }

    public void open(Player player) {
        buildLayout();
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("✿ Petal Shop", PINK).decoration(TextDecoration.BOLD, true));
        holder.inventory = inv;

        ItemStack filler = new ItemStack(Material.PINK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(BALANCE_SLOT, balanceItem(player));
        inv.setItem(TOOLS_LABEL_SLOT, label(Material.CLOCK, "24-Hour Tools", PetalTools.AMETHYST));
        inv.setItem(SPAWNERS_LABEL_SLOT, label(Material.SPAWNER, "Spawners", NamedTextColor.GREEN));

        for (Map.Entry<Integer, ShopItem> e : layout.entrySet()) {
            inv.setItem(e.getKey(), displayItem(e.getValue()));
        }
        player.openInventory(inv);
    }

    /* ---------------- Items ---------------- */

    private ItemStack label(Material icon, String text, TextColor color) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✦ " + text, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack balanceItem(Player player) {
        ItemStack item = new ItemStack(Material.PRISMARINE_CRYSTALS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Your Petals: ", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✿ " + ShardsManager.format(plugin.shards().get(player.getUniqueId())), ShardsCommand.AQUA)));
        meta.lore(List.of(
                Component.text("Earn Petals by playing, killing", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("mobs and players, and from /daily", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** The item a player actually receives (no price text on it). */
    private ItemStack giveItem(ShopItem s) {
        if (s.category == ShopItem.Category.TOOL) {
            return plugin.tools().createTool(s);
        }
        return plugin.spawners().createItem(s.entity, 1);
    }

    /** What shows in the menu: the item plus its price. */
    private ItemStack displayItem(ShopItem s) {
        ItemStack item = giveItem(s);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Price: ", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✿ " + ShardsManager.format(plugin.price(s)), ShardsCommand.AQUA)));
        lore.add(Component.text("Click to buy", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /* ---------------- Clicking ---------------- */

    private boolean isOurs(Inventory top) {
        return top.getHolder() instanceof ShopHolder;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurs(top)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() != top) return;

        ShopItem item = layout.get(e.getSlot());
        if (item == null) return;
        buy(player, item, top);
    }

    private void buy(Player player, ShopItem item, Inventory top) {
        long price = plugin.price(item);
        if (price <= 0) return;

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Component.text("Your inventory is full. Make some room first.", NamedTextColor.RED));
            return;
        }

        long balance = plugin.shards().get(player.getUniqueId());
        if (balance < price) {
            player.sendMessage(Component.text("You need " + ShardsManager.format(price - balance) + " more for that.",
                    NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }

        if (!plugin.shards().withdraw(player.getUniqueId(), price)) {
            player.sendMessage(Component.text("Something went wrong taking your Petals. Try again.", NamedTextColor.RED));
            return;
        }
        player.getInventory().addItem(giveItem(item));
        plugin.shards().save();

        top.setItem(BALANCE_SLOT, balanceItem(player));
        player.sendMessage(Component.text("Bought ", NamedTextColor.GREEN)
                .append(Component.text(item.displayName, NamedTextColor.WHITE))
                .append(Component.text(" for " + ShardsManager.format(price) + ".", NamedTextColor.GREEN)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (isOurs(e.getView().getTopInventory())) {
            e.setCancelled(true);
        }
    }
}
