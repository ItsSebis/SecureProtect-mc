package dev.sebi.secureprotect.command;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

public final class SpCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("lookup", "near", "at", "page", "wand", "status", "purge", "reload");

    private final SecureProtectPlugin plugin;

    public SpCommand(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "lookup", "near", "at", "page", "wand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(text("Players only.", NamedTextColor.RED));
                } else if (!sender.hasPermission("secureprotect.inspect")) {
                    noPermission(sender);
                } else {
                    playerCommand(player, sub, args);
                }
            }
            case "status", "purge", "reload" -> {
                if (!sender.hasPermission("secureprotect.admin")) {
                    noPermission(sender);
                } else {
                    adminCommand(sender, sub, args);
                }
            }
            default -> sender.sendMessage(text("/sp <lookup|near [radius]|at <x> <y> <z>|page <n>|wand|status|purge <days>|reload>", NamedTextColor.GRAY));
        }
        return true;
    }

    private void playerCommand(Player player, String sub, String[] args) {
        switch (sub) {
            case "wand" -> {
                player.getInventory().addItem(new ItemStack(plugin.settings().wand()));
                player.sendMessage(text("Left-click a block: its history. Right-click: container log, or the block on the clicked face."
                        + " Sneak + right-click in the air: the nearest changed spot you are looking at.", NamedTextColor.GOLD));
            }
            case "lookup" -> {
                Block target = player.getTargetBlockExact(5);
                if (target == null) {
                    player.sendMessage(text("Look at a block within 5 blocks.", NamedTextColor.RED));
                    return;
                }
                plugin.inspector().inspect(player, target, Action.ALL);
            }
            case "near" -> {
                int max = plugin.settings().nearMaxRadius();
                Integer radius = args.length > 1 ? parse(args[1]) : Integer.valueOf(Math.min(5, max));
                if (radius == null || radius < 1 || radius > max) {
                    player.sendMessage(text("/sp near [radius 1-" + max + "]", NamedTextColor.RED));
                    return;
                }
                plugin.inspector().near(player, radius);
            }
            case "at" -> {
                Integer x = args.length > 3 ? parse(args[1]) : null;
                Integer y = args.length > 3 ? parse(args[2]) : null;
                Integer z = args.length > 3 ? parse(args[3]) : null;
                if (x == null || y == null || z == null) {
                    player.sendMessage(text("/sp at <x> <y> <z>", NamedTextColor.RED));
                    return;
                }
                plugin.inspector().inspect(player, player.getWorld().getBlockAt(x, y, z), Action.ALL);
            }
            default -> {
                Integer page = args.length > 1 ? parse(args[1]) : null;
                if (page == null) {
                    player.sendMessage(text("/sp page <number>", NamedTextColor.RED));
                    return;
                }
                plugin.inspector().page(player, page);
            }
        }
    }

    private void adminCommand(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage(text("Config reloaded. Flush interval and queue limit need a restart.", NamedTextColor.GREEN));
            }
            case "status" -> plugin.storage().stats().whenComplete((s, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    sender.sendMessage(text("Status failed: " + err, NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(text("Entries: " + s.rows() + ", file: " + s.fileBytes() / 1024 / 1024 + " MB, queued: "
                        + s.queued() + ", dropped: " + s.dropped(), NamedTextColor.GOLD));
            }));
            default -> {
                Integer days = args.length > 1 ? parse(args[1]) : null;
                if (days == null || days < 0) {
                    sender.sendMessage(text("/sp purge <days>  (deletes entries older than that)", NamedTextColor.RED));
                    return;
                }
                plugin.storage().purgeOlderThan(days).whenComplete((n, err) -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(err != null ? text("Purge failed: " + err, NamedTextColor.RED)
                                : text("Purged " + n + " entries older than " + days + " days.", NamedTextColor.GREEN))));
            }
        }
    }

    private static void noPermission(CommandSender sender) {
        sender.sendMessage(text("You do not have permission.", NamedTextColor.RED));
    }

    private static Integer parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
    }
}
