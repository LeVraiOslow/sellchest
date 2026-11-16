package fr.ivalona.sellchest;

import net.kyori.adventure.text.Component;

public final class Messages {

    private final SellChestPlugin plugin;

    public Messages(SellChestPlugin plugin) {
        this.plugin = plugin;
    }

    /** get("given", "%amount%", "3", "%player%", "Steve") */
    public Component get(String key, String... replacements) {
        String raw = plugin.getConfig().getString("messages." + key, "&cMessage manquant : " + key);
        raw = raw.replace("%prefix%", plugin.getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return Text.parse(raw);
    }
}
