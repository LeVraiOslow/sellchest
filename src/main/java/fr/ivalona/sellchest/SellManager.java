package fr.ivalona.sellchest;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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

    private record ChunkKey(UUID world, long key) { }

    private final Set<Location> scheduled = new HashSet<>();
    /** Tous les SellChests des chunks chargés, regroupés par chunk. */
    private final Map<ChunkKey, Set<Location>> tracked = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    private DecimalFormat format;
    private double multiplier = 1.0;
    private BukkitTask notifyTask;
    private BukkitTask sweepTask;
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

        // Vente automatique : filet de sécurité qui vend tout item arrivé par n'importe quel moyen
        if (sweepTask != null) sweepTask.cancel();
        if (plugin.getConfig().getBoolean("auto-sell.enabled", true)) {
            long ticks = Math.max(1, plugin.getConfig().getLong("auto-sell.interval-ticks", 10));
            sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepAll, ticks, ticks);
        }
    }

    public void shutdown() {
        if (notifyTask != null) notifyTask.cancel();
        if (sweepTask != null) sweepTask.cancel();
        flushNotifications();
    }

    // ------------------------------------------------------------------ suivi des SellChests

    private ChunkKey keyOf(Location loc) {
        return new ChunkKey(loc.getWorld().getUID(), Chunk.getChunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
    }

    /** Enregistre un SellChest (pose ou chargement de chunk). */
    public void track(Block block) {
        Location loc = block.getLocation();
        tracked.computeIfAbsent(keyOf(loc), k -> new HashSet<>()).add(loc);
    }

    /** Scanne un chunk chargé et enregistre les SellChests qu'il contient. */
    public void trackChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getChunkKey());
        Set<Location> found = new HashSet<>();
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Chest chest && readOwner(chest) != null) {
                found.add(state.getLocation());
            }
        }
        if (found.isEmpty()) tracked.remove(key);
        else tracked.put(key, found);
    }

    public void untrackChunk(Chunk chunk) {
        tracked.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getChunkKey()));
    }

    /** À l'activation du plugin : enregistre les SellChests des chunks déjà chargés. */
    public void trackLoadedChunks() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) trackChunk(chunk);
        }
    }

    /** Vend le contenu de tous les SellChests suivis. */
    private void sweepAll() {
        for (Set<Location> set : tracked.values()) {
            set.removeIf(loc -> loc.getWorld() == null || !sweep(loc.getBlock()));
        }
        tracked.values().removeIf(Set::isEmpty);
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
    /** @return false si ce bloc n'est (plus) pas un SellChest. */
    public boolean sweep(Block block) {
        if (block.getType() != Material.CHEST) return false;
        if (!(block.getState(false) instanceof Chest chest)) return false;
        UUID owner = readOwner(chest);
        if (owner == null) return false;

        Inventory inv = chest.getBlockInventory();
        // Sécurité : jamais de vente dans un double coffre
        if (chest.getInventory().getSize() != inv.getSize()) return true;

        Economy eco = economy();
        if (eco == null) {
            if (!economyWarned) {
                economyWarned = true;
                plugin.getLogger().warning("Aucune économie Vault trouvée : les SellChests ne vendent rien.");
            }
            return true;
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
        if (slots.isEmpty()) return true;

        int digits = eco.fractionalDigits();
        if (digits < 0) digits = 2;
        total = BigDecimal.valueOf(total * multiplier).setScale(digits, RoundingMode.HALF_UP).doubleValue();
        if (total <= 0) return true;

        OfflinePlayer ownerPlayer = Bukkit.getOfflinePlayer(owner);
        EconomyResponse response = eco.depositPlayer(ownerPlayer, total);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Dépôt refusé pour " + owner + " : " + response.errorMessage);
            return true; // les items restent dans le coffre
        }

        for (int slot : slots) inv.setItem(slot, null);

        // Mode NONE : aucune notification, on n'accumule donc rien (ni message, ni mémoire inutile).
        if ("NONE".equalsIgnoreCase(plugin.getConfig().getString("notify.mode", "NONE"))) return true;

        Pending p = pending.computeIfAbsent(owner, k -> new Pending());
        p.total += total;
        p.items += items;
        return true;
    }

    // ------------------------------------------------------------------ notifications

    private void flushNotifications() {
        String mode = plugin.getConfig().getString("notify.mode", "NONE").toUpperCase(Locale.ROOT);
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
