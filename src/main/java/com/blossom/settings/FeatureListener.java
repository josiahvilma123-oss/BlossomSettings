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

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class FeatureListener implements Listener {

    private final BlossomSettings plugin;

    private final Set<CreatureSpawnEvent.SpawnReason> reasons =
            EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    private double radiusSq = 128 * 128;

    public FeatureListener(BlossomSettings plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /** Reads the mob-spawns section of config.yml (also called by /settings reload). */
    public void loadConfig() {
        reasons.clear();
        for (String name : plugin.getConfig().getStringList("mob-spawns.cancel-reasons")) {
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown spawn reason in config: " + name);
            }
        }
        if (reasons.isEmpty()) reasons.add(CreatureSpawnEvent.SpawnReason.NATURAL);
        double r = plugin.getConfig().getDouble("mob-spawns.radius", 128);
        radiusSq = r * r;
    }

    /* ---------------- Mob spawns ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent e) {
        if (!reasons.contains(e.getSpawnReason())) return;
        if (!(e.getEntity() instanceof Enemy)) return;
        if (e.getEntity() instanceof Phantom) return; // handled by the phantom toggle

        Location loc = e.getLocation();

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
