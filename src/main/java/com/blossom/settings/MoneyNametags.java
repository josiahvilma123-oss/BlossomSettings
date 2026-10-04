package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Floats a "$ 3.9K" text display above each player. The displays are not
 * mounted on the player (that would break teleports); instead they follow the
 * player every couple of ticks with client-side smoothing. Each viewer only
 * sees them if their "Money Nametags" setting is ON, and nobody sees their own.
 */
public final class MoneyNametags {

    private final BlossomSettings plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, String> balances = new HashMap<>();
    private final Map<UUID, String> shownText = new HashMap<>();

    private BukkitTask task;
    private long runs = 0;
    private int interval = 2;
    private int refreshRuns = 10;
    private double heightOffset = 0.75;
    private String format = "<green>$</green> <white><balance></white>";

    public MoneyNametags(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    public void start() {
        FileConfiguration cfg = plugin.getConfig();
        interval = Math.max(1, cfg.getInt("money-nametags.update-interval-ticks", 2));
        int refreshTicks = Math.max(interval, cfg.getInt("money-nametags.balance-refresh-ticks", 20));
        refreshRuns = Math.max(1, refreshTicks / interval);
        heightOffset = cfg.getDouble("money-nametags.height-offset", 0.75);
        format = cfg.getString("money-nametags.format", format);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (TextDisplay d : displays.values()) {
            d.remove();
        }
        displays.clear();
        balances.clear();
        shownText.clear();
    }

    private void tick() {
        Economy eco = plugin.economy();
        if (eco == null) return;

        boolean refresh = (runs++ % refreshRuns) == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();

            if (p.isDead() || p.isInvisible() || p.getGameMode() == GameMode.SPECTATOR) {
                remove(id);
                continue;
            }

            TextDisplay d = displays.get(id);
            if (d != null && (!d.isValid() || !d.getWorld().equals(p.getWorld()))) {
                remove(id);
                d = null;
            }

            String bal = balances.get(id);
            if (bal == null || refresh) {
                bal = formatMoney(eco.getBalance(p));
                balances.put(id, bal);
            }

            if (d == null) {
                create(p, bal);
            } else {
                if (!bal.equals(shownText.get(id))) {
                    d.text(render(bal));
                    shownText.put(id, bal);
                }
                d.teleport(anchor(p));
            }
        }
    }

    private Location anchor(Player p) {
        // getHeight() follows the pose, so the tag drops when sneaking/swimming
        return p.getLocation().add(0, p.getHeight() + heightOffset, 0);
    }

    private Component render(String balance) {
        return mm.deserialize(format, Placeholder.unparsed("balance", balance));
    }

    private void create(Player owner, String balance) {
        TextDisplay display = owner.getWorld().spawn(anchor(owner), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false); // we show it per viewer below
            d.setBillboard(Display.Billboard.CENTER);
            d.setViewRange(0.3f);
            d.setTeleportDuration(Math.min(59, interval));
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.text(render(balance));
        });

        UUID id = owner.getUniqueId();
        displays.put(id, display);
        shownText.put(id, balance);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(id)) continue;
            if (plugin.settings().get(viewer.getUniqueId(), Setting.MONEY_NAMETAGS)) {
                viewer.showEntity(plugin, display);
            }
        }
    }

    /** Re-applies one viewer's setting to every existing display. */
    public void refreshViewer(Player viewer) {
        boolean wants = plugin.settings().get(viewer.getUniqueId(), Setting.MONEY_NAMETAGS);
        for (Map.Entry<UUID, TextDisplay> e : displays.entrySet()) {
            if (e.getKey().equals(viewer.getUniqueId())) continue;
            if (wants) {
                viewer.showEntity(plugin, e.getValue());
            } else {
                viewer.hideEntity(plugin, e.getValue());
            }
        }
    }

    public void remove(UUID id) {
        TextDisplay d = displays.remove(id);
        if (d != null) d.remove();
        balances.remove(id);
        shownText.remove(id);
    }

    /** 950 -> "950", 3900 -> "3.9K", 1250000 -> "1.3M" */
    static String formatMoney(double v) {
        if (v >= 1_000_000_000d) return trim(v / 1_000_000_000d) + "B";
        if (v >= 1_000_000d) return trim(v / 1_000_000d) + "M";
        if (v >= 1_000d) return trim(v / 1_000d) + "K";
        return String.valueOf((long) Math.max(0, Math.floor(v)));
    }

    private static String trim(double x) {
        String s = String.format(Locale.US, "%.1f", x);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
