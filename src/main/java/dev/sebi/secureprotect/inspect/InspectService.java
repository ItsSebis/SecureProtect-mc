package dev.sebi.secureprotect.inspect;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import dev.sebi.secureprotect.storage.HistoryPage;
import dev.sebi.secureprotect.storage.HistoryRow;
import dev.sebi.secureprotect.storage.NearPage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.util.Vector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import static net.kyori.adventure.text.Component.text;

/** Runs lookups off the main thread and renders the result for the player. */
public final class InspectService {
    private record Query(String world, int x, int y, int z, Set<Action> actions) {
    }

    /** Sampling step along the line of sight; small enough never to skip a block. */
    private static final double RAY_STEP = 0.2;

    private static final DateTimeFormatter ABSOLUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SecureProtectPlugin plugin;
    private final Highlighter highlighter;
    /** What /sp page re-runs for each player: the last lookup, with the page number as input. */
    private final Map<UUID, IntConsumer> last = new ConcurrentHashMap<>();

    public InspectService(SecureProtectPlugin plugin) {
        this.plugin = plugin;
        this.highlighter = new Highlighter(plugin);
    }

    public void inspect(Player player, Block block, Set<Action> actions) {
        Query q = new Query(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), actions);
        last.put(player.getUniqueId(), page -> show(player, q, page));
        highlighter.show(player, block);
        show(player, q, 1);
    }

    /**
     * Sneak + right-click in the air: finds the nearest spot with history along the line of sight, which
     * is how you inspect a block that is now air.
     */
    public void inspectAir(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        List<int[]> positions = new ArrayList<>();
        int range = plugin.settings().airRange();
        for (double d = 0; d <= range; d += RAY_STEP) {
            Block b = world.getBlockAt(eye.clone().add(dir.clone().multiply(d)));
            int[] pos = {b.getX(), b.getY(), b.getZ()};
            if (positions.isEmpty() || !Arrays.equals(positions.getLast(), pos)) {
                positions.add(pos);
            }
            if (!b.isPassable()) {
                break; // history behind a solid block is not what the player is looking at
            }
        }
        plugin.storage().firstWithHistory(world.getName(), positions).whenComplete((index, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        plugin.getLogger().warning("Lookup failed: " + err);
                        player.sendMessage(text("Lookup failed, see the console.", NamedTextColor.RED));
                    } else if (index < 0) {
                        player.sendMessage(text("Nothing logged along your line of sight.", NamedTextColor.GRAY));
                    } else {
                        int[] p = positions.get(index);
                        inspect(player, world.getBlockAt(p[0], p[1], p[2]), Action.ALL);
                    }
                }));
    }

    public void near(Player player, int radius) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int cx = loc.getBlockX(), cy = loc.getBlockY(), cz = loc.getBlockZ();
        last.put(player.getUniqueId(), page -> showNear(player, world.getName(), cx, cy, cz, radius, page));
        showNear(player, world.getName(), cx, cy, cz, radius, 1);
    }

    public void page(Player player, int page) {
        IntConsumer rerun = last.get(player.getUniqueId());
        if (rerun == null) {
            player.sendMessage(text("Nothing to page through yet. Click a block with the wand or use /sp lookup.", NamedTextColor.RED));
            return;
        }
        rerun.accept(page);
    }

    public void forget(UUID player) {
        last.remove(player);
        highlighter.stop(player);
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
        player.sendMessage(nav(page, pages));
    }

    private void showNear(Player player, String world, int cx, int cy, int cz, int radius, int page) {
        int size = plugin.settings().pageSize();
        int p = Math.max(1, page);
        plugin.storage().near(world, cx, cy, cz, radius, size, (p - 1) * size)
                .whenComplete((result, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        plugin.getLogger().warning("Lookup failed: " + err);
                        player.sendMessage(text("Lookup failed, see the console.", NamedTextColor.RED));
                        return;
                    }
                    renderNear(player, result, radius, p, size);
                }));
    }

    private void renderNear(Player player, NearPage result, int radius, int page, int size) {
        if (result.total() == 0) {
            player.sendMessage(text("No history within " + radius + " blocks.", NamedTextColor.GRAY));
            return;
        }
        int pages = (result.total() + size - 1) / size;
        if (page > pages) {
            player.sendMessage(text("There are only " + pages + " page(s).", NamedTextColor.RED));
            return;
        }
        player.sendMessage(text("--- " + result.total() + " changed spots within " + radius + " blocks ---", NamedTextColor.GOLD));
        long now = System.currentTimeMillis() / 1000;
        for (NearPage.Entry e : result.entries()) {
            Component pos = text("[" + e.x() + " " + e.y() + " " + e.z() + "]", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(text("Click to see the full history")))
                    .clickEvent(ClickEvent.runCommand("/sp at " + e.x() + " " + e.y() + " " + e.z()));
            Component more = e.count() > 1 ? text(" (+" + (e.count() - 1) + " more)", NamedTextColor.DARK_GRAY) : Component.empty();
            player.sendMessage(when(e.latest(), now).append(text(" ")).append(pos).append(describe(e.latest())).append(more));
        }
        player.sendMessage(nav(page, pages));
    }

    private static Component nav(int page, int pages) {
        Component nav = text("Page " + page + "/" + pages + " ", NamedTextColor.GRAY);
        if (page > 1) {
            nav = nav.append(button("[<]", page - 1));
        }
        if (page < pages) {
            nav = nav.append(text(" ")).append(button("[>]", page + 1));
        }
        return nav;
    }

    private static Component button(String label, int page) {
        return text(label, NamedTextColor.AQUA, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/sp page " + page));
    }

    private static Component when(HistoryRow row, long now) {
        return text(relative(now - row.time()) + " ago", NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(text(ABSOLUTE.format(Instant.ofEpochSecond(row.time())))));
    }

    private static Component line(HistoryRow row, long now) {
        return when(row, now).append(describe(row));
    }

    /** " Sebi took 64 Diamond", without the timestamp. */
    private static Component describe(HistoryRow row) {
        Component item = item(row);
        Component what = switch (row.action()) {
            case BREAK -> text(" broke ", NamedTextColor.RED).append(item);
            case PLACE -> text(" placed ", NamedTextColor.GREEN).append(item);
            case INTERACT -> text(" used ", NamedTextColor.YELLOW).append(item);
            case TAKE -> text(" took " + row.amount() + " ", NamedTextColor.RED).append(item);
            case PUT -> text(" put in " + row.amount() + " ", NamedTextColor.GREEN).append(item);
        };
        return text(" " + row.user(), NamedTextColor.WHITE).append(what);
    }

    /**
     * The item name (translated on the client, so it reads "Block of Diamond"), followed by its
     * enchantments in gray, with the normal item tooltip on hover.
     */
    private static Component item(HistoryRow row) {
        Material m = Material.matchMaterial(row.material());
        Component name = m != null ? Component.translatable(m, NamedTextColor.WHITE)
                : text(row.material().toLowerCase(Locale.ROOT), NamedTextColor.WHITE);
        if (row.itemData() == null || row.itemData().length == 0) {
            return name;
        }
        try {
            ItemStack stack = ItemStack.deserializeBytes(row.itemData());
            Component result = name.hoverEvent(stack.asHoverEvent());
            Component enchants = enchantments(stack);
            return enchants == null ? result : result.append(enchants);
        } catch (RuntimeException ex) {
            return name; // data from an incompatible version: fall back to the plain name
        }
    }

    private static Component enchantments(ItemStack stack) {
        Map<Enchantment, Integer> all = new java.util.LinkedHashMap<>(stack.getEnchantments());
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta stored) {
            all.putAll(stored.getStoredEnchants());
        }
        if (all.isEmpty()) {
            return null;
        }
        Component list = Component.empty();
        boolean first = true;
        for (Map.Entry<Enchantment, Integer> e : all.entrySet()) {
            list = list.append(first ? Component.empty() : text(", ")).append(e.getKey().displayName(e.getValue()));
            first = false;
        }
        return text(" (", NamedTextColor.GRAY).append(list.colorIfAbsent(NamedTextColor.GRAY)).append(text(")", NamedTextColor.GRAY));
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
