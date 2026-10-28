package dev.sebi.secureprotect.inspect;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import dev.sebi.secureprotect.storage.HistoryPage;
import dev.sebi.secureprotect.storage.HistoryRow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.kyori.adventure.text.Component.text;

/** Runs lookups off the main thread and renders the result for the player. */
public final class InspectService {
    private record Query(String world, int x, int y, int z, Set<Action> actions) {
    }

    private static final DateTimeFormatter ABSOLUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SecureProtectPlugin plugin;
    private final Map<UUID, Query> last = new ConcurrentHashMap<>();

    public InspectService(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    public void inspect(Player player, Block block, Set<Action> actions) {
        Query q = new Query(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), actions);
        last.put(player.getUniqueId(), q);
        show(player, q, 1);
    }

    public void page(Player player, int page) {
        Query q = last.get(player.getUniqueId());
        if (q == null) {
            player.sendMessage(text("Nothing to page through yet. Click a block with the wand or use /sp lookup.", NamedTextColor.RED));
            return;
        }
        show(player, q, page);
    }

    public void forget(UUID player) {
        last.remove(player);
    }

    private void show(Player player, Query q, int page) {
        int size = plugin.settings().pageSize();
        int p = Math.max(1, page);
        plugin.storage().query(q.world(), q.x(), q.y(), q.z(), q.actions(), size, (p - 1) * size)
                .whenComplete((result, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        plugin.getLogger().warning("Lookup failed: " + err);
                        player.sendMessage(text("Lookup failed, see the console.", NamedTextColor.RED));
                        return;
                    }
                    render(player, q, result, p, size);
                }));
    }

    private void render(Player player, Query q, HistoryPage result, int page, int size) {
        String where = q.x() + " " + q.y() + " " + q.z();
        if (result.total() == 0) {
            player.sendMessage(text("No history at " + where + ".", NamedTextColor.GRAY));
            return;
        }
        int pages = (result.total() + size - 1) / size;
        if (page > pages) {
            player.sendMessage(text("There are only " + pages + " page(s).", NamedTextColor.RED));
            return;
        }
        player.sendMessage(text("--- History at " + where + " (" + result.total() + " entries) ---", NamedTextColor.GOLD));
        long now = System.currentTimeMillis() / 1000;
        for (HistoryRow row : result.rows()) {
            player.sendMessage(line(row, now));
        }
        Component nav = text("Page " + page + "/" + pages + " ", NamedTextColor.GRAY);
        if (page > 1) {
            nav = nav.append(button("[<]", page - 1));
        }
        if (page < pages) {
            nav = nav.append(text(" ")).append(button("[>]", page + 1));
        }
        player.sendMessage(nav);
    }

    private static Component button(String label, int page) {
        return text(label, NamedTextColor.AQUA, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/sp page " + page));
    }

    private Component line(HistoryRow row, long now) {
        Component when = text(relative(now - row.time()) + " ago", NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(text(ABSOLUTE.format(Instant.ofEpochSecond(row.time())))));
        Component item = item(row.material());
        Component what = switch (row.action()) {
            case BREAK -> text(" broke ", NamedTextColor.RED).append(item);
            case PLACE -> text(" placed ", NamedTextColor.GREEN).append(item);
            case INTERACT -> text(" used ", NamedTextColor.YELLOW).append(item);
            case TAKE -> text(" took " + row.amount() + " ", NamedTextColor.RED).append(item);
            case PUT -> text(" put in " + row.amount() + " ", NamedTextColor.GREEN).append(item);
        };
        return when.append(text(" " + row.user(), NamedTextColor.WHITE)).append(what.colorIfAbsent(NamedTextColor.WHITE));
    }

    /** Translated on the client, so it reads "Block of Diamond" rather than DIAMOND_BLOCK. */
    private static Component item(String materialName) {
        Material m = Material.matchMaterial(materialName);
        return m != null ? Component.translatable(m, NamedTextColor.WHITE) : text(materialName.toLowerCase(Locale.ROOT), NamedTextColor.WHITE);
    }

    static String relative(long seconds) {
        long s = Math.max(0, seconds);
        long d = s / 86_400, h = s % 86_400 / 3_600, m = s % 3_600 / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s % 60 + "s";
        }
        return s + "s";
    }
}
