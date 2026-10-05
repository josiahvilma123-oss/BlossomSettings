package com.blossom.settings;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class BlossomSettings extends JavaPlugin implements CommandExecutor {

    private SettingsManager settings;
    private SettingsMenu menu;
    private MoneyNametags nametags;
    private FeatureListener features;
    private Economy economy;
    private boolean warnedNoEconomy = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new SettingsManager(this);
        settings.load();

        menu = new SettingsMenu(this);
        nametags = new MoneyNametags(this);

        getServer().getPluginManager().registerEvents(menu, this);
        features = new FeatureListener(this);
        getServer().getPluginManager().registerEvents(features, this);

        if (getCommand("settings") != null) {
            getCommand("settings").setExecutor(this);
        }

        nametags.start();

        // Re-apply night vision for anyone already online (e.g. after /reload)
        for (Player p : getServer().getOnlinePlayers()) {
            applyNightVision(p);
        }
    }

    @Override
    public void onDisable() {
        if (nametags != null) nametags.stop();
        if (settings != null) settings.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("blossomsettings.reload")) {
                sender.sendMessage("You don't have permission to do that.");
                return true;
            }
            reloadConfig();
            features.loadConfig();
            nametags.reload();
            sender.sendMessage("BlossomSettings config reloaded.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the settings menu.");
            return true;
        }
        menu.open(player);
        return true;
    }

    /** Called by the menu after a toggle flips. */
    public void onToggled(Player player, Setting setting) {
        switch (setting) {
            case NIGHT_VISION -> applyNightVision(player);
            case MONEY_NAMETAGS -> nametags.refreshViewer(player);
            default -> { }
        }
    }

    public void applyNightVision(Player p) {
        boolean on = settings.get(p.getUniqueId(), Setting.NIGHT_VISION);
        if (on) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0,
                    false, false, false));
        } else {
            PotionEffect current = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
            // only strip ours (infinite), never a real potion the player drank
            if (current != null && current.isInfinite()) {
                p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
    }

    /** Looks up Vault's economy lazily, since the economy plugin may load after us. */
    public Economy economy() {
        if (economy != null) return economy;
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            warnNoEconomy("Vault is not installed");
            return null;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            warnNoEconomy("no economy plugin is registered with Vault");
            return null;
        }
        economy = rsp.getProvider();
        return economy;
    }

    private void warnNoEconomy(String reason) {
        if (!warnedNoEconomy) {
            warnedNoEconomy = true;
            getLogger().warning("Money nametags are waiting: " + reason + ".");
        }
    }

    public SettingsManager settings() {
        return settings;
    }

    public MoneyNametags nametags() {
        return nametags;
    }
}
