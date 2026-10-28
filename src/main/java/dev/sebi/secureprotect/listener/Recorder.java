package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import dev.sebi.secureprotect.storage.RawEntry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Supplier;

/** Builds entries on the main thread (no I/O) and hands them to the queue. */
public final class Recorder {
    private final SecureProtectPlugin plugin;

    public Recorder(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, Block block, Action action, String material, int amount) {
        record(player, block, action, material, null, amount);
    }

    /** {@code template} is a single-item copy; its full data is serialized later on the writer thread. */
    public void recordItem(Player player, Block block, Action action, ItemStack template, int amount) {
        Supplier<byte[]> data = template.hasItemMeta() ? () -> serialize(template) : null;
        record(player, block, action, template.getType().name(), data, amount);
    }

    private void record(Player player, Block block, Action action, String material, Supplier<byte[]> data, int amount) {
        String world = block.getWorld().getName();
        if (plugin.settings().ignoredWorlds().contains(world)) {
            return;
        }
        plugin.storage().log(new RawEntry(System.currentTimeMillis() / 1000, player.getUniqueId(), player.getName(),
                world, block.getX(), block.getY(), block.getZ(), action, material, data, amount));
    }

    private byte[] serialize(ItemStack item) {
        try {
            return item.serializeAsBytes();
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not serialize an item, logging it as plain " + item.getType() + ": " + t);
            return new byte[0];
        }
    }
}
