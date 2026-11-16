package fr.ivalona.sellchest;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class SellChestListener implements Listener {

    private final SellChestPlugin plugin;
    private final SellManager manager;

    public SellChestListener(SellChestPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    // ================================================================ pose

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (placed.getType() != org.bukkit.Material.CHEST) return;

        Player player = event.getPlayer();
        boolean sellChest = manager.isSellChestItem(event.getItemInHand());

        // Aucun double coffre possible avec un SellChest (dans les deux sens)
        if (wouldFormDoubleChest(placed, sellChest)) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().get("no-double"));
            return;
        }
        if (!sellChest) return;

        if (!player.hasPermission("sellchest.use")) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().get("no-permission"));
            return;
        }

        if (placed.getState() instanceof Chest chest) {
            manager.setOwner(chest, player.getUniqueId());
            player.sendMessage(plugin.messages().get("placed"));
        }
    }

    private boolean wouldFormDoubleChest(Block placed, boolean placedIsSellChest) {
        BlockData data = placed.getBlockData();
        if (!(data instanceof org.bukkit.block.data.type.Chest chestData)) return false;

        if (placedIsSellChest && chestData.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return true;
        }

        BlockFace facing = chestData.getFacing();
        BlockFace[] sides = (facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
                ? new BlockFace[]{BlockFace.EAST, BlockFace.WEST}
                : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH};

        for (BlockFace side : sides) {
            Block neighbor = placed.getRelative(side);
            if (neighbor.getType() != org.bukkit.Material.CHEST) continue;
            if (!(neighbor.getBlockData() instanceof org.bukkit.block.data.type.Chest neighborData)) continue;
            if (neighborData.getFacing() != facing) continue;
            if (placedIsSellChest || manager.ownerOf(neighbor) != null) return true;
        }
        return false;
    }

    // ================================================================ destruction

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        UUID owner = manager.ownerOf(block);
        if (owner == null) return;

        Player player = event.getPlayer();
        if (plugin.getConfig().getBoolean("protection.owner-only-break", true)
                && !player.getUniqueId().equals(owner)
                && !player.hasPermission("sellchest.admin")) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().get("not-owner"));
            return;
        }

        // On gère nous-mêmes les drops : le SellChest (item spécial) + le contenu non vendu
        event.setDropItems(false);
        World world = block.getWorld();
        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);

        if (block.getState(false) instanceof Chest chest) {
            Inventory inv = chest.getBlockInventory();
            for (ItemStack stack : inv.getContents()) {
                if (stack != null && !stack.isEmpty()) world.dropItemNaturally(dropAt, stack);
            }
            inv.clear();
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            world.dropItemNaturally(dropAt, manager.createItem(1));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("protection.explosion-proof", true)) return;
        event.blockList().removeIf(b -> manager.ownerOf(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("protection.explosion-proof", true)) return;
        event.blockList().removeIf(b -> manager.ownerOf(b) != null);
    }

    // ================================================================ entrée d'items

    /** Entonnoirs, minecarts-entonnoirs, droppers... : l'item entre, puis est vendu au tick suivant. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getDestination().getHolder(false);
        if (holder instanceof Chest chest && manager.ownerOf(chest) != null) {
            manager.scheduleSweep(chest.getBlock());
        }
    }

    /** Joueurs : clic, shift-clic, drag, fermeture. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        check(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        check(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        check(event.getView().getTopInventory());
    }

    private void check(Inventory top) {
        InventoryHolder holder = top.getHolder(false);
        if (holder instanceof Chest chest && manager.ownerOf(chest) != null) {
            manager.scheduleSweep(chest.getBlock());
        }
    }
}
