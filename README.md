# SecureProtect

A lightweight block and container history plugin for Paper. Click or look at a block to see who broke, placed or interacted with it, and see a detailed item log for chests, with minimal performance impact and bounded storage.

```
2h 5m ago Sebi took 64 Diamond
1h 50m ago Sebi put in 32 Diamond
12m 3s ago Sebi broke Block of Diamond
11m 40s ago Sebi placed Block of Lapis Lazuli
```

Each page reads oldest to newest, so the newest entry is at the bottom.

## Requirements

- Paper 26.3
- Java 25

## Install

Download the jar from the [latest release](https://github.com/ItsSebis/SecureProtect-mc/releases/latest), put it in `plugins/` and restart. Paper provides the SQLite driver, so there is nothing else to install.

## Using it

Give players `secureprotect.inspect`, then `/sp wand` hands out a wooden hoe (the item is configurable).

| Action with the hoe | Result |
| --- | --- |
| Left-click a block | History of that block (breaks, places, interactions) |
| Right-click a container | Its item log: who took or put what, with enchantments |
| Right-click any other block | History of the block on the clicked face |
| Sneak + right-click in the air | Nearest spot with history along your line of sight, so you can inspect a block that is now air |

The inspected block is outlined with particles that only you see. While you use the hoe as the wand it never tills, breaks or opens anything. Hover over an item in the output to see its full tooltip.

### Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/sp wand` | `secureprotect.inspect` | Give yourself the wand |
| `/sp lookup` | `secureprotect.inspect` | History of the block you are looking at (no click needed) |
| `/sp near [radius]` | `secureprotect.inspect` | Spots with history around you, newest at the bottom. Click one to inspect it |
| `/sp at <x> <y> <z>` | `secureprotect.inspect` | Full history of a position |
| `/sp page <n>` | `secureprotect.inspect` | Page through the last result |
| `/sp status` | `secureprotect.admin` | Entry count, file size, queue depth |
| `/sp purge <days>` | `secureprotect.admin` | Delete entries older than that |
| `/sp reload` | `secureprotect.admin` | Reload `config.yml` |

Both permissions default to operators.

## What is logged

- Block breaks and placements.
- Interactions with doors, trapdoors, fence gates, buttons, levers, repeaters, comparators, note blocks, daylight detectors, jukeboxes, lecterns, cakes and respawn anchors (configurable).
- Container item changes, including enchantments, custom names and other item data, for chests, trapped chests, barrels, shulker boxes, hoppers, droppers, dispensers, furnaces, blast furnaces, smokers, brewing stands and crafters. A double chest is one position.

Not logged: ender chests, container entities (chest minecarts, boats, mounts), item frames, chiseled bookshelves, decorated pots, automated hopper moves, explosions, fire and liquids.

Container changes are recorded as one comparison per visit (when a player opens and closes it), not one line per click. If two players have the same container open, each is only credited with their own changes.

## Performance and storage

- Event handlers do no I/O. They put a small record on a queue, and one background thread writes batches to SQLite every second.
- Repeated interactions with the same block (button or lever spam) are collapsed.
- Entries use about 57 bytes each, roughly 18 million per GB. Identical enchanted items share one stored row.
- Retention runs hourly in small chunks so it never blocks writes: entries older than 30 days are deleted, and if the database passes 1 GB the oldest entries are dropped until it is back under 90%.

## Configuration

`plugins/SecureProtect/config.yml`:

```yaml
retention:
  days: 30            # 0 = keep forever
  max-size-mb: 1024   # 0 = no size cap
storage:
  flush-interval-ms: 1000
  queue-limit: 100000
  max-item-data-bytes: 16384   # larger items (full shulker boxes) are stored as plain items
inspect:
  tool: WOODEN_HOE
  page-size: 8
  air-range: 6
  highlight-seconds: 3   # 0 = off
  near-max-radius: 16
interactions: ["#doors", "#trapdoors", LEVER, ...]   # materials or #block tags
ignored-worlds: []
```

The flush interval and queue limit need a restart; everything else applies on `/sp reload`.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`. This needs JDK 25.
