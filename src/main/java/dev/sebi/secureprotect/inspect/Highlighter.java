package dev.sebi.secureprotect.inspect;

import dev.sebi.secureprotect.SecureProtectPlugin;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Outlines a block with particles that only the inspecting player sees. */
public final class Highlighter {
    private static final int PERIOD_TICKS = 4;
    private static final double STEP = 0.25;
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.AQUA, 0.7f);

    private final SecureProtectPlugin plugin;
    private final Map<UUID, BukkitTask> active = new HashMap<>();

    public Highlighter(SecureProtectPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, Block block) {
        int seconds = plugin.settings().highlightSeconds();
        stop(player.getUniqueId());
        if (seconds <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        int[] remaining = {seconds * 20 / PERIOD_TICKS};
        active.put(id, plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || remaining[0]-- <= 0) {
                stop(id);
                return;
            }
            outline(player, block.getX(), block.getY(), block.getZ());
        }, 0L, PERIOD_TICKS));
    }

    public void stop(UUID player) {
        BukkitTask task = active.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private static void outline(Player player, int bx, int by, int bz) {
        // Slightly larger than the block so the outline is not hidden inside neighbouring faces.
        double lo = -0.02, hi = 1.02;
        for (double t = lo; t <= hi + 1e-9; t += STEP) {
            for (double a : new double[]{lo, hi}) {
                for (double b : new double[]{lo, hi}) {
                    dot(player, bx + t, by + a, bz + b);
                    dot(player, bx + a, by + t, bz + b);
                    dot(player, bx + a, by + b, bz + t);
                }
            }
        }
    }

    private static void dot(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, DUST);
    }
}
