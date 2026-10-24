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
 * /shards                                         your balance
 * /shards check <player>                          (admin)
 * /shards give|take|set <player> <amount>         (admin, also used by /daily)
 */
public final class ShardsCommand implements CommandExecutor, TabCompleter {

    static final TextColor AQUA = TextColor.color(0xFF69B4); // petal pink
    private static final String ADMIN = "blossomsettings.shards.admin";

    private final BlossomSettings plugin;

    public ShardsCommand(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /petals <check|give|take|set> <player> [amount]");
                return true;
            }
            player.sendMessage(Component.text("You have ", NamedTextColor.GRAY)
                    .append(Component.text("✿ " + ShardsManager.format(plugin.shards().get(player.getUniqueId())), AQUA))
                    .append(Component.text(". Spend them at /ps", NamedTextColor.GRAY)));
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("check", "give", "take", "set").contains(sub) || args.length < 2
                || (!sub.equals("check") && args.length < 3)) {
            sender.sendMessage(Component.text("Usage: /petals <check|give|take|set> <player> [amount]", NamedTextColor.RED));
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
            sender.sendMessage(Component.text(name + " has " + ShardsManager.format(plugin.shards().get(target.getUniqueId())) + ".",
                    NamedTextColor.GRAY));
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
            case "give" -> plugin.shards().add(target.getUniqueId(), amount);
            case "take" -> plugin.shards().add(target.getUniqueId(), -amount);
            default -> plugin.shards().set(target.getUniqueId(), amount);
        }
        plugin.shards().save();
        if (sub.equals("give") && target instanceof Player online && sender != online) {
            online.sendMessage(Component.text("+ ✿ " + ShardsManager.format(amount), AQUA));
        }
        sender.sendMessage(Component.text(name + " now has " + ShardsManager.format(plugin.shards().get(target.getUniqueId())) + ".",
                NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(ADMIN)) return out;
        if (args.length == 1) {
            for (String s : List.of("check", "give", "take", "set")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
