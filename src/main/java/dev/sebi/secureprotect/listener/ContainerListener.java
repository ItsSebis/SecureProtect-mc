package dev.sebi.secureprotect.listener;

import dev.sebi.secureprotect.inspect.Containers;
import dev.sebi.secureprotect.storage.Action;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Logs container changes as one aggregated diff per visit: snapshot when a player opens a container,
 * compare when they close it. That is one pass over a few dozen slots, not one log line per click.
 */
public final class ContainerListener implements Listener {
    private record Key(UUID world, int x, int y, int z) {
    }

    private static final class Session {
        final Block block;
        final Inventory inventory;
        final Key key;
        final Map<Material, Integer> before;

        Session(Block block, Inventory inventory, Map<Material, Integer> before) {
            this.block = block;
            this.inventory = inventory;
            this.before = before;
            this.key = new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final Recorder recorder;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ContainerListener(Recorder recorder) {
        this.recorder = recorder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = Containers.blockOf(e.getInventory());
        if (block == null) {
            return;
        }
        sessions.put(player.getUniqueId(), new Session(block, e.getInventory(), Containers.count(e.getInventory())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player) {
            finish(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        finish(e.getPlayer());
    }

    private void finish(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        Map<Material, Integer> after = Containers.count(s.inventory);
        Map<Material, Integer> delta = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> en : after.entrySet()) {
            delta.merge(en.getKey(), en.getValue() - s.before.getOrDefault(en.getKey(), 0), Integer::sum);
        }
        for (Map.Entry<Material, Integer> en : s.before.entrySet()) {
            if (!after.containsKey(en.getKey())) {
                delta.merge(en.getKey(), -en.getValue(), Integer::sum);
            }
        }
        for (Map.Entry<Material, Integer> en : delta.entrySet()) {
            int d = en.getValue();
            if (d != 0) {
                recorder.record(player, s.block, d < 0 ? Action.TAKE : Action.PUT, en.getKey().name(), Math.abs(d));
            }
        }
        // Other players still looking at the same container must not be blamed for this player's changes.
        for (Session other : sessions.values()) {
            if (other.key.equals(s.key)) {
                for (Map.Entry<Material, Integer> en : delta.entrySet()) {
                    other.before.merge(en.getKey(), en.getValue(), Integer::sum);
                }
            }
        }
    }
}
