# SecureProtect: block and container history plugin (Paper 26.3)

## Context
The user wants a CoreProtect-style history tool for their Paper 26.3 server. Anyone with the right permission should be able to click or look at a block and see who broke, placed or interacted with it. Chests should show a per-item log ("Sebi took 64 diamonds", "Sebi put in 32 diamonds"). It must have minimal performance impact and bounded storage, with a time limit plus a size cap (the user chose "Time + size cap").
The repo is empty (no commits), so this is greenfield.

## Decisions made with the user
- Server: Paper 26.3.
- Retention: time-based purge plus a max database size, oldest entries dropped first.

## My calls (the user hasn't confirmed these)
- Language/build: Java, Gradle (Kotlin DSL), `run-paper` plugin for a local test server.
- **Unverified:** Paper 26.x API coordinates and JDK. A search suggests `io.papermc.paper:paper-api:26.x.build.+` and Java 25, but the 26.3 specifics are unconfirmed. First step of implementation: check repo.papermc.io and the Paper docs before pinning anything.
- Storage: SQLite (WAL mode), loaded through Paper's library loader so the jar stays small.
- Package `dev.sebi.secureprotect`, plugin name `SecureProtect`.
- No Folia support in v1.

## Goals
1. Log block break, block place and block interaction (doors, buttons, levers, containers and similar) with player, time, world, x/y/z and block type.
2. Log container changes as item diffs: who took or put what, and how many.
3. Inspect UX, WorldEdit-style wand: a player with `secureprotect.inspect` who is holding a **wooden hoe** triggers the lookup by clicking. No mode toggle needed.
   - **Left-click** a block: full history of that block (breaks, places, interactions).
   - **Right-click** a block: if it is a container, its item log (took/put in, with counts). Otherwise the history of the block on the clicked face (the spot where something would be placed), like CoreProtect.
   - The click event is cancelled while the hoe is used as the wand, so the hoe never tills, breaks or opens anything. Players without the permission use the hoe normally.
   - `/sp lookup` is a no-click fallback: history of the block you are looking at via a 5-block ray trace.
   - Results are paginated (page 1 holds the newest entries, and each page reads oldest to newest from top to bottom), with relative plus absolute time, e.g. `3h ago Sebi took 64 Diamond`.
   - The wand item is configurable (`inspect.tool`, default `WOODEN_HOE`). Optionally require a custom name/model data so ordinary hoes keep working (my call: default off, since the user asked for a plain wooden hoe).
4. Near-zero main-thread cost.
5. Bounded storage via configurable retention.

## Non-goals (v1)
Rollback/restore, entity and mob logging, explosions, fire, liquids, command logging, chat logging, web UI, Folia.

## Design

### Performance
- Event handlers only build a small immutable record (ints and a few IDs) and push it onto a lock-free queue. No I/O and no item serialization on the main thread.
- One dedicated writer thread drains the queue every ~1s or at 500 entries. It writes in a single transaction with a prepared batch insert.
- Coalescing: drop repeat identical interactions by the same player on the same block within ~2s (button/lever spam).
- Containers: snapshot contents (material to count) on `InventoryOpenEvent`, diff on `InventoryCloseEvent`. This gives one aggregated log line per visit instead of one per click. Only block-backed holders are tracked (double chests resolve to one canonical location).
- Interaction logging uses a material whitelist (doors, buttons, levers, containers, etc.). Not every right-click is logged.
- If the queue exceeds a hard limit (default 100k), drop the oldest entries and warn rather than grow memory.

### Storage
- Interned lookup tables: `users(id, uuid, name)`, `materials(id, key)`, `worlds(id, name)`. Mapped in memory, so rows hold small ints instead of strings.
- `actions(time INT epoch-seconds, user INT, world INT, x INT, y INT, z INT, action TINYINT, material INT, amount INT)`. Estimated ~50 bytes per row including index, so roughly 20M entries per GB.
- Index on `(world, x, z, y, time)` for coordinate lookups. Second index on `time` for purging.
- `auto_vacuum=INCREMENTAL`, `synchronous=NORMAL`, WAL.
- Container item NBT (enchants, names) is not stored in v1. Only material and count.

### Retention
- Async task, hourly: delete rows older than `retention.days` (default 30), in chunks of ~10k rows with a short pause between them so the writer is never blocked long.
- If the DB file exceeds `retention.max-size-mb` (default 1024), delete the oldest chunks until it is under 90%, then run `incremental_vacuum`.

### Commands and permissions
`/sp lookup`, `/sp page <n>`, `/sp wand` (gives a wooden hoe), `/sp status` (queue depth, DB size, row count), `/sp purge <days>`, `/sp reload`. Permissions: `secureprotect.inspect`, `secureprotect.admin`.
Lookups run on the writer or reader thread (not main) and send results back via the scheduler.

### Config (`config.yml`)
retention days, max size, flush interval, queue limit, interaction whitelist, inspect tool defaults, world blacklist.

## Critical files (to create)
- `build.gradle.kts`, `settings.gradle.kts`
- `src/main/resources/paper-plugin.yml` (or `plugin.yml`) and `config.yml`
- `.../SecureProtectPlugin.java`: lifecycle wiring
- `.../listener/BlockListener.java` (break, place, interact) and `ContainerListener.java` (open/close diff)
- `.../storage/Database.java`, `WriteQueue.java` (queue and writer thread), `Lookups.java` (interning), `RetentionTask.java`
- `.../command/SpCommand.java`, `.../listener/WandListener.java` (PlayerInteractEvent with hoe check, left/right click routing, cancel) and `.../InspectService.java` (ray trace, formatting and pagination)

## Verification
- Unit tests (JUnit) against in-memory/temporary SQLite: batch insert, interning, coalescing, chunked purge by time, size-cap purge, queue overflow behaviour.
- `./gradlew runServer` (run-paper): place/break blocks, open a chest and take/put items, check that the wooden hoe left-click/right-click output matches (and that a player without permission can still till dirt). Also check `/sp lookup`.
- Stress check: script or bot generating ~10k block events per minute; confirm tick time stays flat (Spark or `/tps`) and the DB grows as estimated.
- Restart the server and confirm history persists, and that a low size cap triggers purging.

## Next step
After approval: write the design doc to `docs/superpowers/specs/2026-10-28-secureprotect-design.md` (per the brainstorming flow), then use `superpowers:writing-plans`, then implement.
