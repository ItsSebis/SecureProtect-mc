package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import dev.sebi.secureprotect.storage.RawEntry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Builds entries on the main thread (no I/O) and hands them to the queue. */
public final class Recorder {
    private final SecureProtectPlugin plugin;

    public Recorder(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, Block block, Action action, String material, int amount) {
        String world = block.getWorld().getName();
        if (plugin.settings().ignoredWorlds().contains(world)) {
            return;
        }
        plugin.storage().log(new RawEntry(System.currentTimeMillis() / 1000, player.getUniqueId(), player.getName(),
                world, block.getX(), block.getY(), block.getZ(), action, material, amount));
    }
}
