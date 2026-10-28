package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.storage.Action;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class BlockListener implements Listener {
    private final SecureProtectPlugin plugin;
    private final Recorder recorder;

    public BlockListener(SecureProtectPlugin plugin, Recorder recorder) {
        this.plugin = plugin;
        this.recorder = recorder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        recorder.record(e.getPlayer(), b, Action.BREAK, b.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        recorder.record(e.getPlayer(), b, Action.PLACE, b.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Block b = e.getClickedBlock();
        if (plugin.settings().interactions().contains(b.getType())) {
            recorder.record(e.getPlayer(), b, Action.INTERACT, b.getType().name(), 1);
        }
    }
}
