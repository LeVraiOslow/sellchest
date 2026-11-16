package fr.ivalona.sellchest;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Lit les prix de vente directement dans les fichiers de NexShop :
 * plugins/NexShop/categories/&lt;catégorie&gt;/*.yml (hors category.yml), section "shop-items".
 */
public final class ShopPrices {

    private final SellChestPlugin plugin;
    private volatile Map<Material, Double> prices = Map.of();
    private volatile boolean strictVanilla = true;

    public ShopPrices(SellChestPlugin plugin) {
        this.plugin = plugin;
    }

    public int size() {
        return prices.size();
    }

    public void reload() {
        String folderName = plugin.getConfig().getString("shop.folder", "NexShop");
        File folder = new File(plugin.getDataFolder().getParentFile(), folderName);
        File categories = new File(folder, "categories");

        if (!categories.isDirectory()) {
            plugin.getLogger().warning("Dossier de catégories introuvable : " + categories.getPath()
                    + " -> aucun item ne sera vendable.");
            prices = Map.of();
            return;
        }

        // strict-vanilla-match : auto = valeur de NexShop
        String strictSetting = plugin.getConfig().getString("shop.strict-vanilla-match", "auto");
        if (strictSetting.equalsIgnoreCase("auto")) {
            File shopConfig = new File(folder, "config.yml");
            strictVanilla = !shopConfig.isFile()
                    || YamlConfiguration.loadConfiguration(shopConfig).getBoolean("sell.strict-vanilla-match", true);
        } else {
            strictVanilla = Boolean.parseBoolean(strictSetting);
        }

        Map<Material, Double> map = new EnumMap<>(Material.class);
        File[] dirs = categories.listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparing(File::getName));
            for (File dir : dirs) {
                File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml")
                        && !n.equalsIgnoreCase("category.yml"));
                if (files == null) continue;
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    load(file, map);
                }
            }
        }
        prices = map;
        plugin.getLogger().info(map.size() + " items vendables chargés depuis " + folderName
                + " (strict-vanilla-match=" + strictVanilla + ").");
    }

    private void load(File file, Map<Material, Double> map) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = yaml.getConfigurationSection("shop-items");
        if (items == null) return;

        for (String key : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;

            String raw = s.getString("material");
            // Items custom (nexo:xxx, etc.) non gérés
            if (raw == null || raw.contains(":")) continue;
            Material material = Material.matchMaterial(raw.trim());
            if (material == null || !material.isItem()) continue;

            if (!s.getBoolean("sellable", true)) continue;
            if (!s.contains("sell-price")) continue;
            double price = s.getDouble("sell-price", -1);
            if (price <= 0) continue;

            // Item donné par commande (spawners...) : pas d'item physique à vendre
            List<String> commands = s.getStringList("commands");
            if (!commands.isEmpty() && !s.getBoolean("give-item", false)) continue;

            map.putIfAbsent(material, price);
        }
    }

    /** Prix unitaire de vente, ou -1 si l'item n'est pas vendable. */
    public double priceOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        Double price = prices.get(stack.getType());
        if (price == null) return -1;

        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            // Ne jamais détruire le contenu d'un shulker / bundle
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof Container c && !c.getInventory().isEmpty()) {
                return -1;
            }
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) return -1;
            if (strictVanilla && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants())) return -1;
        }
        if (strictVanilla && stack.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) return -1;
        return price;
    }
}
