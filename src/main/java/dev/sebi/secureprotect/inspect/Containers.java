package dev.sebi.secureprotect.inspect;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Helpers that give every container one canonical coordinate (a double chest is always its left half). */
public final class Containers {
    private Containers() {
    }

    /** The block a container inventory is logged under, or null if it is not block-backed. */
    public static Block blockOf(Inventory inv) {
        if (inv instanceof DoubleChestInventory d) {
            return d.getLeftSide().getLocation().getBlock();
        }
        Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        Block block = loc.getBlock();
        return block.getState(false) instanceof Container ? block : null;
    }

    /** The canonical container block for a clicked block, or null if it is not a container. */
    public static Block canonical(Block block) {
        if (block.getState(false) instanceof Chest chest && chest.getInventory() instanceof DoubleChestInventory d) {
            return d.getLeftSide().getLocation().getBlock();
        }
        return block.getState(false) instanceof Container ? block : null;
    }

    /** Item counts keyed by a single-item copy, so differently enchanted/named stacks stay separate. */
    public static Map<ItemStack, Integer> count(Inventory inv) {
        Map<ItemStack, Integer> counts = new HashMap<>();
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                counts.merge(stack.asOne(), stack.getAmount(), Integer::sum);
            }
        }
        return counts;
    }
}
