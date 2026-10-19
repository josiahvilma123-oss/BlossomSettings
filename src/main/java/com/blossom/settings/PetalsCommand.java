package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /petalsshop           opens the shop
 * /petals               shows your balance
 * /petals check <player>, give|take|set <player> <amount>   (admins)
 */
public final class PetalsCommand implements CommandExecutor, TabCompleter {

    private static final TextColor PINK = TextColor.color(0xFF69B4);
    private static final String ADMIN = "blossomsettings.petals.admin";

    private final BlossomSettings plugin;
    private final PetalsShop shop;

    public PetalsCommand(BlossomSettings plugin, PetalsShop shop) {
        this.plugin = plugin;
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("petalsshop")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the shop.");
                return true;
            }
            shop.open(player);
            return true;
        }

        // /petals
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /petals <check|give|take|set> <player> [amount]");
                return true;
            }
            player.sendMessage(Component.text("You have ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.petals().format(plugin.petals().get(player.getUniqueId())), PINK))
                    .append(Component.text(". Spend them at /petalsshop", NamedTextColor.GRAY)));
            return true;
        }

        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /petals " + sub + " <player>"
                    + (sub.equals("check") ? "" : " <amount>"), NamedTextColor.RED));
            return true;
        }

        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found. They must have joined before.", NamedTextColor.RED));
            return true;
        }
        String name = target.getName() != null ? target.getName() : args[1];

        if (sub.equals("check")) {
            sender.sendMessage(Component.text(name + " has "
                    + plugin.petals().format(plugin.petals().get(target.getUniqueId())) + ".", NamedTextColor.GRAY));
            return true;
        }

        if (!(sub.equals("give") || sub.equals("take") || sub.equals("set"))) {
            sender.sendMessage(Component.text("Usage: /petals <check|give|take|set> <player> [amount]",
                    NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /petals " + sub + " <player> <amount>", NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Amount must be a whole number.", NamedTextColor.RED));
            return true;
        }
        if (amount < 0) {
            sender.sendMessage(Component.text("Amount can't be negative.", NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "give" -> plugin.petals().add(target.getUniqueId(), amount);
            case "take" -> plugin.petals().add(target.getUniqueId(), -amount);
            default -> plugin.petals().set(target.getUniqueId(), amount);
        }
        plugin.petals().save();
        sender.sendMessage(Component.text(name + " now has "
                + plugin.petals().format(plugin.petals().get(target.getUniqueId())) + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("petals") || !sender.hasPermission(ADMIN)) return out;

        if (args.length == 1) {
            for (String s : List.of("check", "give", "take", "set")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
