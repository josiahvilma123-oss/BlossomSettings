package com.blossom.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Gives Shards for playtime (not AFK) and kills. */
public final class PetalsEarner implements Listener {

    private static final TextColor AQUA = TextColor.color(0xFF69B4);

    private final BlossomSettings plugin;
    private final NamespacedKey noPayKey;
    private final Map<UUID, Integer> minutes = new HashMap<>();
    private final Map<UUID, Location> lastLocation = new HashMap<>();
    private final Map<String, Long> killCooldowns = new HashMap<>();
    private BukkitTask task;

    public PetalsEarner(BlossomSettings plugin) {
        this.plugin = plugin;
        this.noPayKey = new NamespacedKey(plugin, "no_pay");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::minuteTick, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void minuteTick() {
        long nowMs = System.currentTimeMillis();
        killCooldowns.values().removeIf(t -> nowMs - t > 3_600_000L);

        int need = Math.max(1, plugin.getConfig().getInt("earn-petals.playtime.minutes", 10));
        long amount = plugin.getConfig().getLong("earn-petals.playtime.amount", 25);
        if (amount <= 0) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Location now = p.getLocation();
            Location prev = lastLocation.put(id, now);

            // AFK check: a minute only counts if the player actually moved
            boolean moved = prev == null
                    || !prev.getWorld().equals(now.getWorld())
                    || prev.distanceSquared(now) > 2.0;
            if (!moved) continue;

            int m = minutes.merge(id, 1, Integer::sum);
            if (m >= need) {
                minutes.put(id, 0);
                give(p, amount);
            }
        }
    }

    private void give(Player p, long amount) {
        plugin.shards().add(p.getUniqueId(), amount);
        p.sendActionBar(Component.text("+ ✿ " + ShardsManager.format(amount), AQUA));
    }

    /** Mobs from spawners, eggs and commands don't pay, so farms can't print Shards. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        switch (e.getSpawnReason()) {
            case SPAWNER, TRIAL_SPAWNER, SPAWNER_EGG, CUSTOM, COMMAND ->
                    e.getEntity().getPersistentDataContainer()
                            .set(noPayKey, PersistentDataType.BYTE, (byte) 1);
            default -> { }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        if (victim instanceof Player v) {
            if (v.getUniqueId().equals(killer.getUniqueId())) return;
            long amount = plugin.getConfig().getLong("earn-petals.kill-player.amount", 30);
            long cooldownMs = plugin.getConfig()
                    .getLong("earn-petals.kill-player.same-victim-cooldown-minutes", 30) * 60_000L;
            String key = killer.getUniqueId() + ":" + v.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = killCooldowns.get(key);
            if (last != null && now - last < cooldownMs) return;
            killCooldowns.put(key, now);
            if (amount > 0) give(killer, amount);
        } else if (victim instanceof Enemy) {
            if (victim.getPersistentDataContainer().has(noPayKey, PersistentDataType.BYTE)) return;
            long amount = plugin.getConfig().getLong("earn-petals.kill-hostile-mob.amount", 2);
            if (amount > 0) give(killer, amount);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        minutes.remove(id);
        lastLocation.remove(id);
        plugin.shards().save();
    }
}
