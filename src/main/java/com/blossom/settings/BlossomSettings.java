package com.blossom.settings;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

public final class BlossomSettings extends JavaPlugin implements CommandExecutor {

    private SettingsManager settings;
    private SettingsMenu menu;
    private MoneyNametags nametags;
    private FeatureListener features;
    private PetalsManager petals;
    private PetalsEarner earner;
    private PetalTools tools;
    private PetalsShop shop;
    private ShardsManager shards;
    private SpawnerManager spawners;
    private Economy economy;
    private boolean warnedNoEconomy = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Add any new settings (shards, spawners...) to an older config.yml without touching your changes
        getConfig().options().copyDefaults(true);
        saveConfig();

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

        // Petals: currency, earning, custom tools and the shop
        petals = new PetalsManager(this);
        petals.load();
        shards = new ShardsManager(this);
        shards.load();
        spawners = new SpawnerManager(this);
        getServer().getPluginManager().registerEvents(spawners, this);
        getServer().getPluginManager().registerEvents(new SpawnerMenu(this), this);
        ShardsCommand shardsCommand = new ShardsCommand(this);
        PluginCommand shardsCmd = getCommand("shards");
        if (shardsCmd != null) {
            shardsCmd.setExecutor(shardsCommand);
            shardsCmd.setTabCompleter(shardsCommand);
        }
        earner = new PetalsEarner(this);
        tools = new PetalTools(this);
        shop = new PetalsShop(this);
        getServer().getPluginManager().registerEvents(earner, this);
        getServer().getPluginManager().registerEvents(tools, this);
        getServer().getPluginManager().registerEvents(shop, this);

        PetalsCommand petalsCommand = new PetalsCommand(this, shop);
        for (String name : List.of("petals", "petalsshop")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(petalsCommand);
                cmd.setTabCompleter(petalsCommand);
            }
        }
        earner.start();
        tools.start();
        spawners.start();
        // Save balances every 5 minutes as a safety net
        getServer().getScheduler().runTaskTimer(this, () -> shards.save(), 6000L, 6000L);

        nametags.start();

        // %blossomsettings_petals% for TAB, holograms, etc.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PetalsPlaceholders(this).register();
            getLogger().info("Hooked into PlaceholderAPI (%blossomsettings_petals%).");
        }

        // Re-apply night vision for anyone already online (e.g. after /reload)
        for (Player p : getServer().getOnlinePlayers()) {
            applyNightVision(p);
        }
    }

    @Override
    public void onDisable() {
        if (nametags != null) nametags.stop();
        if (earner != null) earner.stop();
        if (tools != null) tools.stop();
        if (spawners != null) spawners.stop();
        if (shards != null) shards.save();
        if (settings != null) settings.save();
        if (petals != null) petals.save();
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
            spawners.reload();
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
            getLogger().warning("Petals are waiting for the economy: " + reason + ".");
        }
    }

    public SettingsManager settings() {
        return settings;
    }

    public MoneyNametags nametags() {
        return nametags;
    }

    public PetalsManager petals() {
        return petals;
    }

    public ShardsManager shards() {
        return shards;
    }

    public SpawnerManager spawners() {
        return spawners;
    }

    public PetalTools tools() {
        return tools;
    }

    /** Shop price in Petals, from config.yml shop-prices (falls back to the built-in default). */
    public long price(ShopItem item) {
        return getConfig().getLong("shard-prices." + item.id, item.defaultPrice);
    }
}
