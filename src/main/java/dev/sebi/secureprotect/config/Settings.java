package dev.sebi.secureprotect.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml; the plugin swaps in a new one on /sp reload. */
public record Settings(int retentionDays, long maxSizeBytes, long flushIntervalMs, int queueLimit,
                       Material wand, int pageSize, int highlightSeconds, int airRange, int nearMaxRadius,
                       int maxItemDataBytes, Set<Material> interactions, Set<String> ignoredWorlds) {

    public static Settings load(FileConfiguration cfg, Logger log) {
        Material wand = Material.matchMaterial(cfg.getString("inspect.tool", "WOODEN_HOE"));
        if (wand == null) {
            log.warning("inspect.tool is not a valid material, using WOODEN_HOE.");
            wand = Material.WOODEN_HOE;
        }
        return new Settings(
                Math.max(0, cfg.getInt("retention.days", 30)),
                Math.max(0L, cfg.getLong("retention.max-size-mb", 1024)) * 1024 * 1024,
                Math.max(100L, cfg.getLong("storage.flush-interval-ms", 1000)),
                Math.max(1000, cfg.getInt("storage.queue-limit", 100_000)),
                wand,
                Math.max(1, cfg.getInt("inspect.page-size", 8)),
                Math.max(0, cfg.getInt("inspect.highlight-seconds", 3)),
                Math.min(32, Math.max(1, cfg.getInt("inspect.air-range", 6))),
                Math.min(64, Math.max(1, cfg.getInt("inspect.near-max-radius", 16))),
                Math.max(0, cfg.getInt("storage.max-item-data-bytes", 16384)),
                resolveInteractions(cfg.getStringList("interactions"), log),
                new HashSet<>(cfg.getStringList("ignored-worlds")));
    }

    private static Set<Material> resolveInteractions(Iterable<String> entries, Logger log) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(entry.substring(1).toLowerCase()), Material.class);
                if (tag == null) {
                    log.warning("Unknown block tag in interactions: " + entry);
                } else {
                    out.addAll(tag.getValues());
                }
            } else {
                Material m = Material.matchMaterial(entry);
                if (m == null) {
                    log.warning("Unknown material in interactions: " + entry);
                } else {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
