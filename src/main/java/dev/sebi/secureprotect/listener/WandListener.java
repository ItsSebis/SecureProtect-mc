package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.inspect.Containers;
import dev.sebi.secureprotect.storage.Action;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** WorldEdit-style wand: left-click shows a block's history, right-click its container log or the block on the clicked face. */
public final class WandListener implements Listener {
    private static final long COOLDOWN_NANOS = 200_000_000L;

    private final SecureProtectPlugin plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public WandListener(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean holdsWand(Player player) {
        return player.getInventory().getItemInMainHand().getType() == plugin.settings().wand()
                && player.hasPermission("secureprotect.inspect");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block clicked = e.getClickedBlock();
        if (clicked == null || e.getItem() == null || e.getItem().getType() != plugin.settings().wand()) {
            return;
        }
        Player player = e.getPlayer();
        if (!player.hasPermission("secureprotect.inspect")) {
            return;
        }
        e.setCancelled(true);
        long now = System.nanoTime();
        Long prev = lastUse.put(player.getUniqueId(), now);
        if (prev != null && now - prev < COOLDOWN_NANOS) {
            return;
        }
        switch (e.getAction()) {
            case LEFT_CLICK_BLOCK -> plugin.inspector().inspect(player, clicked, Action.BLOCK);
            case RIGHT_CLICK_BLOCK -> {
                Block container = Containers.canonical(clicked);
                if (container != null) {
                    plugin.inspector().inspect(player, container, Action.CONTAINER);
                } else {
                    plugin.inspector().inspect(player, clicked.getRelative(e.getBlockFace()), Action.BLOCK);
                }
            }
            default -> {
            }
        }
    }

    /** Safety net so the wand never breaks a block, whatever other plugins do to the interact event. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (holdsWand(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastUse.remove(e.getPlayer().getUniqueId());
        plugin.inspector().forget(e.getPlayer().getUniqueId());
    }
}
