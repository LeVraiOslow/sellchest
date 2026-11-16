package fr.ivalona.sellchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convertit les codes &a, &#RRGGBB et MiniMessage en Component. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    private Text() {}

    private static String tag(char c) {
        return switch (Character.toLowerCase(c)) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }

    public static String toMini(String input) {
        if (input == null) return "";
        String s = HEX.matcher(input).replaceAll("<reset><#$1>");
        Matcher m = LEGACY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(tag(m.group(1).charAt(0))));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static Component parse(String input) {
        return MINI.deserialize(toMini(input));
    }

    /** Pour noms / lores d'items : pas d'italique par défaut. */
    public static Component item(String input) {
        return parse(input).decoration(TextDecoration.ITALIC, false);
    }
}
