package com.blossom.settings;

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class FeatureListener implements Listener {

    private final BlossomSettings plugin;

    public FeatureListener(BlossomSettings plugin) {
        this.plugin = plugin;
    }

    /* ---------------- Mob spawns ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!(e.getEntity() instanceof Enemy)) return;
        if (e.getEntity() instanceof Phantom) return; // handled by the phantom toggle

        Location loc = e.getLocation();
        double radius = plugin.getConfig().getDouble("mob-spawns.check-radius", 64);
        double radiusSq = radius * radius;

        boolean anyoneNearby = false;
        boolean someoneWantsMobs = false;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) > radiusSq) continue;
            anyoneNearby = true;
            if (plugin.settings().get(p.getUniqueId(), Setting.MOB_SPAWNS)) {
                someoneWantsMobs = true;
                break;
            }
        }

        if (anyoneNearby && !someoneWantsMobs) {
            e.setCancelled(true);
        }
    }

    /* ---------------- Phantoms ---------------- */

    @EventHandler(ignoreCancelled = true)
    public void onPhantomPreSpawn(PhantomPreSpawnEvent e) {
        if (e.getSpawningEntity() instanceof Player p
                && !plugin.settings().get(p.getUniqueId(), Setting.PHANTOM_SPAWNING)) {
            e.setCancelled(true);
        }
    }

    /* ---------------- Night vision ---------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.applyNightVision(p);
        // give the client a moment before showing/hiding nametags
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.nametags().refreshViewer(p);
        }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.applyNightVision(p));
    }

    /** Drinking milk clears effects, so put night vision back if it's enabled. */
    @EventHandler
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityPotionEffectEvent.Cause.MILK) return;
        if (!plugin.settings().get(p.getUniqueId(), Setting.NIGHT_VISION)) return;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.applyNightVision(p));
    }

    /* ---------------- Cleanup ---------------- */

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.nametags().remove(e.getPlayer().getUniqueId());
    }
}
