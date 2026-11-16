package fr.ivalona.sellchest;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;

public final class SellChestPlugin extends JavaPlugin {

    private Messages messages;
    private ShopPrices prices;
    private SellManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        messages = new Messages(this);
        prices = new ShopPrices(this);
        manager = new SellManager(this);
        reloadAll();

        getServer().getPluginManager().registerEvents(new SellChestListener(this), this);
        manager.trackLoadedChunks();

        PluginCommand command = getCommand("sellchest");
        if (command != null) {
            SellChestCommand executor = new SellChestCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Les plugins d'économie s'enregistrent parfois après nous : on vérifie au 1er tick
        Bukkit.getScheduler().runTask(this, () -> {
            if (Bukkit.getServicesManager().getRegistration(Economy.class) == null) {
                getLogger().warning("Vault est présent mais aucune économie n'est enregistrée !");
            }
        });
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    public void reloadAll() {
        reloadConfig();
        prices.reload();
        manager.reload();
    }

    public Messages messages() { return messages; }
    public ShopPrices prices() { return prices; }
    public SellManager manager() { return manager; }
}
