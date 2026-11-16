package fr.ivalona.sellchest;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SellChestCommand implements TabExecutor {

    private final SellChestPlugin plugin;

    public SellChestCommand(SellChestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sellchest.admin")) {
            sender.sendMessage(plugin.messages().get("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.messages().get("usage"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(plugin.messages().get("reloaded", "%count%", String.valueOf(plugin.prices().size())));
            }
            case "give" -> give(sender, args);
            default -> sender.sendMessage(plugin.messages().get("usage"));
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            target = sender instanceof Player p ? p : null;
        }
        if (target == null) {
            sender.sendMessage(plugin.messages().get("player-not-found"));
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.messages().get("usage"));
                return;
            }
        }

        int remaining = amount;
        while (remaining > 0) {
            int size = Math.min(64, remaining);
            remaining -= size;
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(plugin.manager().createItem(size));
            for (ItemStack left : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
        }

        sender.sendMessage(plugin.messages().get("given", "%amount%", String.valueOf(amount), "%player%", target.getName()));
        if (sender != target) {
            target.sendMessage(plugin.messages().get("received", "%amount%", String.valueOf(amount)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("sellchest.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("give", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("1", "8", "64"));
        }
        return out;
    }
}
