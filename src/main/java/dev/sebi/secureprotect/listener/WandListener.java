package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.SecureProtectPlugin;
import dev.sebi.secureprotect.inspect.Containers;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WorldEdit-style wand: left-click shows a block's history, right-click its container log or the block on the
 * clicked face, sneak + right-click in the air the nearest changed spot along the line of sight.
 */
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
        if (e.getItem() == null || e.getItem().getType() != plugin.settings().wand()) {
            return;
        }
        Player player = e.getPlayer();
        if (!player.hasPermission("secureprotect.inspect")) {
            return;
        }
        Block clicked = e.getClickedBlock();
        if (clicked == null) {
            // Sneak + right-click in the air: inspect where a now-empty block used to be.
            if (e.getAction() == Action.RIGHT_CLICK_AIR && player.isSneaking() && !onCooldown(player)) {
                plugin.inspector().inspectAir(player);
            }
            return;
        }
        e.setCancelled(true);
        if (onCooldown(player)) {
            return;
        }
        switch (e.getAction()) {
            case LEFT_CLICK_BLOCK -> plugin.inspector().inspect(player, clicked, dev.sebi.secureprotect.storage.Action.BLOCK);
            case RIGHT_CLICK_BLOCK -> {
                Block container = Containers.canonical(clicked);
                if (container != null) {
                    plugin.inspector().inspect(player, container, dev.sebi.secureprotect.storage.Action.CONTAINER);
                } else {
                    plugin.inspector().inspect(player, clicked.getRelative(e.getBlockFace()), dev.sebi.secureprotect.storage.Action.BLOCK);
                }
            }
            default -> {
            }
        }
    }

    private boolean onCooldown(Player player) {
        long now = System.nanoTime();
        Long prev = lastUse.put(player.getUniqueId(), now);
        return prev != null && now - prev < COOLDOWN_NANOS;
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
