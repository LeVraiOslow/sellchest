package fr.ivalona.sellchest;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SellManager {

    private static final class Pending {
        double total;
        int items;
    }

    private final SellChestPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey itemKey;

    private final Set<Location> scheduled = new HashSet<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    private DecimalFormat format;
    private double multiplier = 1.0;
    private BukkitTask notifyTask;
    private boolean economyWarned = false;

    public SellManager(SellChestPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.itemKey = new NamespacedKey(plugin, "sellchest_item");
    }

    // ------------------------------------------------------------------ config

    public void reload() {
        format = new DecimalFormat(plugin.getConfig().getString("currency.pattern", "#,##0.##"),
                DecimalFormatSymbols.getInstance(Locale.US));
        multiplier = plugin.getConfig().getDouble("shop.sell-multiplier", 1.0);

        if (notifyTask != null) notifyTask.cancel();
        long period = Math.max(1, plugin.getConfig().getLong("notify.interval-seconds", 30)) * 20L;
        notifyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushNotifications, period, period);
    }

    public void shutdown() {
        if (notifyTask != null) notifyTask.cancel();
        flushNotifications();
    }

    public String money(double amount) {
        return plugin.getConfig().getString("currency.display", "%amount%$")
                .replace("%amount%", format.format(amount));
    }

    // ------------------------------------------------------------------ item

    public ItemStack createItem(int amount) {
        ItemStack stack = new ItemStack(Material.CHEST, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.item(plugin.getConfig().getString("item.name", "&6&lCoffre de vente")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("item.lore")) lore.add(Text.item(line));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isSellChestItem(ItemStack stack) {
        if (stack == null || stack.getType() != Material.CHEST || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ propriétaire

    public void setOwner(Chest chest, UUID owner) {
        chest.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        chest.update();
    }

    private UUID readOwner(Chest chest) {
        String raw = chest.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Propriétaire du SellChest à ce bloc, ou null si ce n'est pas un SellChest. */
    public UUID ownerOf(Block block) {
        if (block.getType() != Material.CHEST) return null;
        if (block.getState(false) instanceof Chest chest) return readOwner(chest);
        return null;
    }

    /** Propriétaire pour un holder d'inventaire (un DoubleChest n'est jamais un SellChest). */
    public UUID ownerOf(InventoryHolder holder) {
        if (holder instanceof Chest chest) return readOwner(chest);
        return null;
    }

    // ------------------------------------------------------------------ vente

    /** Programme une vente au prochain tick (dédoublonnée par coffre). */
    public void scheduleSweep(Block block) {
        Location loc = block.getLocation();
        if (!scheduled.add(loc)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            scheduled.remove(loc);
            if (loc.getWorld() == null
                    || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
            sweep(loc.getBlock());
        });
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    /** Vend tout ce qui est vendable dans le SellChest et crédite son propriétaire. */
    public void sweep(Block block) {
        if (block.getType() != Material.CHEST) return;
        if (!(block.getState(false) instanceof Chest chest)) return;
        UUID owner = readOwner(chest);
        if (owner == null) return;

        Inventory inv = chest.getBlockInventory();
        // Sécurité : jamais de vente dans un double coffre
        if (chest.getInventory().getSize() != inv.getSize()) return;

        Economy eco = economy();
        if (eco == null) {
            if (!economyWarned) {
                economyWarned = true;
                plugin.getLogger().warning("Aucune économie Vault trouvée : les SellChests ne vendent rien.");
            }
            return;
        }

        ItemStack[] contents = inv.getContents();
        List<Integer> slots = new ArrayList<>();
        double total = 0;
        int items = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty() || isSellChestItem(stack)) continue;
            double price = plugin.prices().priceOf(stack);
            if (price <= 0) continue;
            total += price * stack.getAmount();
            items += stack.getAmount();
            slots.add(i);
        }
        if (slots.isEmpty()) return;

        int digits = eco.fractionalDigits();
        if (digits < 0) digits = 2;
        total = BigDecimal.valueOf(total * multiplier).setScale(digits, RoundingMode.HALF_UP).doubleValue();
        if (total <= 0) return;

        OfflinePlayer ownerPlayer = Bukkit.getOfflinePlayer(owner);
        EconomyResponse response = eco.depositPlayer(ownerPlayer, total);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Dépôt refusé pour " + owner + " : " + response.errorMessage);
            return; // les items restent dans le coffre
        }

        for (int slot : slots) inv.setItem(slot, null);

        Pending p = pending.computeIfAbsent(owner, k -> new Pending());
        p.total += total;
        p.items += items;
    }

    // ------------------------------------------------------------------ notifications

    private void flushNotifications() {
        String mode = plugin.getConfig().getString("notify.mode", "CHAT").toUpperCase(Locale.ROOT);
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue; // reste en attente jusqu'à sa connexion
            Pending p = entry.getValue();
            String[] ph = {"%total%", money(p.total), "%items%", String.valueOf(p.items)};
            switch (mode) {
                case "ACTIONBAR" -> player.sendActionBar(plugin.messages().get("sold-actionbar", ph));
                case "NONE" -> { }
                default -> player.sendMessage(plugin.messages().get("sold-chat", ph));
            }
            it.remove();
        }
    }
}
