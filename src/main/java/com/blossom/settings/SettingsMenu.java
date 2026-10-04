package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.util.List;

public final class SettingsMenu implements Listener {

    /** Marks our inventory so we can recognise it in click events. */
    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlossomSettings plugin;

    public SettingsMenu(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    private static int slotOf(Setting s) {
        return 10 + s.ordinal() * 2; // 10, 12, 14, 16
    }

    public void open(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Settings"));
        holder.inventory = inv;

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        for (Setting s : Setting.values()) {
            inv.setItem(slotOf(s), buildItem(player, s));
        }
        player.openInventory(inv);
    }

    private ItemStack buildItem(Player player, Setting s) {
        boolean on = plugin.settings().get(player.getUniqueId(), s);
        ItemStack item = new ItemStack(s.icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(
                Component.text(s.label + ": ", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(on
                                ? Component.text("ON", NamedTextColor.GREEN)
                                : Component.text("OFF", NamedTextColor.RED)));
        meta.lore(List.of(
                Component.text("Click to toggle", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isOurs(Inventory top) {
        return top.getHolder() instanceof MenuHolder;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurs(top)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() != top) return;

        for (Setting s : Setting.values()) {
            if (e.getSlot() == slotOf(s)) {
                plugin.settings().toggle(player.getUniqueId(), s);
                plugin.onToggled(player, s);
                top.setItem(slotOf(s), buildItem(player, s));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (isOurs(e.getView().getTopInventory())) {
            e.setCancelled(true);
        }
    }
}
