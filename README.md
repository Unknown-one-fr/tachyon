# Tachyon

Experimental, **max-performance** Fabric **server** engine for **Minecraft 26.1.2 / JDK 25**.
A playground build that fuses ideas from many optimization mods into one and pushes JDK 25–only
techniques. **Not for production worlds** — expect instability and mod incompatibility.

> ### Single trunk — real mod on deobfuscated MC 26.1.2
> MC 26.1 ships **unobfuscated** (native Mojang names; no Yarn, no mappings download). The project
> is now a **single unified build** on the official 26.1.2 mappings — the former MC-independent
> `main` core build and the `fabric-26.1` mod build have been consolidated. One `build.gradle`
> builds the real server mod *and* keeps the standalone engine self-test/benchmarks runnable
> without booting MC (see [Build](#build)).
>
> **Milestone 1 ✅ — the mod compiles AND loads on a Fabric 26.1.2 server.** The dev server boots
> with the mod and the startup self-test passes *live*: parallel region scheduler, FFM off-heap,
> AVX-512 SIMD, and Compact Object Headers all active in-server (`Mappings not present!` confirms the
> deobfuscated path); clean startup + shutdown, no errors.
>
> Build: `./gradlew build` → `build/libs/tachyon-*.jar`; `./gradlew runServer` boots a dev
> server with the mod. Uses `net.fabricmc.fabric-loom` (non-remapping), **no `mappings` line**,
> `implementation`/`jar`, access-widener `official` namespace. Gradle daemon must run on JDK 25.
>
> **Milestone 2 — measure ✅ / takeover ⚠️.** `ServerLevelAdapter` partitions the live world into
> regions; measure mode (read-only, default on) reports it via `/tachyon regions` — e.g. *48 entities
> across 3 regions, 16/region → 3.0× ideal parallelism*. The experimental parallel **takeover**
> (`mosaic.enabled`, default **off**) redirects `ServerLevel`'s entity loop through the scheduler, but
> **parallel-ticking vanilla entities deadlocks**: the Server thread parks in the scheduler join while
> workers block in vanilla's thread-confined tick machinery → Watchdog kill at 60s. Lesson: a thin
> mixin can't make vanilla ticking thread-safe (that needs Folia-depth per-region world isolation).
> `/tachyon` is op-gated (level 2).
>
> **Milestone 3 — per-level parallel takeover that actually runs. ✅** The takeover now ticks whole
> **dimensions in parallel** and boots, ticks, and shuts down cleanly on a live 26.1.2 server (95s,
> 3 dimensions, **no deadlock, no crash, zero exceptions**; `/tachyon perf` → `phases(ms): parallel
> regions=3`). Getting here required solving the real wall:
>
> 1. **Cooperative main-thread dispatcher** (`core/MainThreadDispatcher`) — a worker that needs the
>    server thread mid-tick hops back via `call(...)`; the server thread *pumps that queue while it
>    waits* for the parallel phase (`RegionScheduler.pumpUntil`) instead of bare-blocking in `join()`.
> 2. **The chunk-confinement wall.** `genSources` revealed `ServerChunkCache.getChunk` hard-confines
>    *all* chunk access to one thread: `if (Thread.currentThread() != this.mainThread) …supplyAsync(
>    mainThreadProcessor).join()`. Off-main chunk access is bounced to the server thread and blocks —
>    so any off-main tick deadlocks there (proven twice). The fix is the Folia move: tick each level
>    on **one** worker and temporarily **reassign that level's `mainThread` to the owning worker**
>    (`ParallelLevelTicker` + access-widener), so chunk access runs inline, single-writer *per level*.
>    Disjoint per-level state (entity manager, chunk source, scheduled ticks) makes this race-free by
>    the same single-writer argument vanilla relies on — just relocated.
> 3. **Cross-level writes deferred.** Dimension travel touches two levels' managers, so cross-dimension
>    teleports are deferred to a single-threaded post-tick pass on the main thread (`EntityTeleportMixin`,
>    `ServerPlayerTeleportMixin`, `CrossLevelDefer`).
>
> Plus an off-main tripwire (`core/OffThreadGuard`, `mosaic.guardMode=OFF/WARN/STRICT`).
>
> **Load-tested + ~1.6× faster.** Stress-tested on a live server: ~1000 entities (mob AI + items)
> across 3 dimensions, heavy `randomTickSpeed`, rain, and adversarial simultaneous cross-dimension TNT
> explosions — all stable, zero faults. Same-session A/B on identical load (`/tachyon mosaic on|off`):
> **2.26 ms ON vs 3.57 ms OFF** (capped by the heaviest single dimension, since this layer parallelizes
> across levels, not within one).
>
> **Milestone 4 — intra-level regionization. ✅ (opt-in, `mosaic.intraLevel`)** Parallelizes entity
> ticking *within* a level, so the heaviest dimension is no longer a single thread. Two more isolation
> primitives:
> 1. **Multi-owner chunk cache** (`ServerChunkCacheMixin`) — several workers tick disjoint,
>    interaction-radius-separated regions of one level; a `@Redirect` on the `mainThread` field reads
>    hands each registered region worker inline chunk access (safe: a region only touches its own chunks).
> 2. **Read-write entity-storage lock** (`EntityLifecycleLock`) — entity add/remove/section-move take an
>    exclusive write lock (they mutate the shared `EntityTickList`/`PersistentEntitySectionManager`);
>    every `getEntities`/AABB query takes a shared read lock (`LevelEntityGetterMixin`), so queries stay
>    parallel but never read a half-updated map. Closes the structural-mutation crash *and* the
>    lookup-during-write race.
>
> Verified on live 26.1.2 under interaction-heavy load (250 zombies vs 250 villagers on hard difficulty,
> 3 dims): no deadlock/crash/exception/desync; levels split into multiple parallel regions
> (overworld→4, nether→6/7). Gated `mosaic.intraLevel` (default **off**), independent of the per-level
> `mosaic.enabled`.
>
> **Off-thread chunk generation — validated stable (Chunky).** Under the per-level takeover each
> dimension drives its own generation on its worker thread (the relocated `mainThread` field also
> covers the chunk mailbox's `getRunningThread`, so no extra code was needed), with the heavy work on
> MC's shared background worldgen pool. Stress-tested with [Chunky](https://github.com/pop4959/Chunky)
> pre-generating **fresh terrain in all 3 dimensions concurrently** (~2600 chunks/dim at ~100 cps real
> gen) while the takeover ran: zero faults, MSPT ~3 ms. The full **combined** mode (per-level +
> intra-level) + Chunky gen + ~2000 entities is also stable (no nested-scheduler deadlock).
>
> **Server-global state isolated.** The scoreboard is one object shared by every dimension, so it's
> touched by workers across all dimensions at once — AI reads it (team/alliance lookups) and entity
> events write it (score awards, score/team cleanup on entity removal). `ScoreboardMixin` guards the
> tick-time methods with a `ReentrantReadWriteLock` (`ServerStateLock`): shared read lock for lookups
> (AI stays parallel), exclusive write lock for the rare mutations; command-driven edits run on the
> server thread between phases and need no guard. Verified under parallel combat (scored + teamed mobs
> dying en masse across 3 dimensions, both layers on): no corruption, no deadlock. `/tachyon` is
> op-gated (level 2).

## Stress testing with Chunky

[Chunky](https://github.com/pop4959/Chunky) (drop `Chunky-Fabric-*.jar` into `run/mods/`) drives the
off-thread chunk-generation path. Headless example — concurrent fresh generation in 3 dimensions under
the takeover, via stdin commands to `runServer`:

```
chunky world minecraft:overworld
chunky center 2000000 2000000
chunky radius 400
chunky start
chunky world minecraft:the_nether
chunky center 800000 800000
chunky radius 400
chunky start
chunky world minecraft:the_end
chunky center 800000 800000
chunky radius 400
chunky start
```

Pick far, never-generated centers to force real terrain generation (already-generated chunks load too
fast to stress the pipeline). `tachyon perf` confirms the takeover is active (`regions=3`) during gen.

## What's here (v0.1.0-experimental)

| Subsystem | Package | Status |
|---|---|---|
| Mosaic region engine (parallel, serial-within-region, single-writer) | `core` | done; drives live MC ticking |
| Per-level parallel takeover (dimensions ticked concurrently) | `mc`, `mixin` | done; live, load-tested (~1.6×) |
| Intra-level regionization (multi-owner chunk cache + rw entity lock) | `mc`, `mixin` | done; opt-in `mosaic.intraLevel` |
| Parallel entity tick engine (Phase-R/W, snapshot, cross-region migration) | `engine` | done (adapter-based; 10.6x) |
| Struct-of-Arrays entity hot store | `soa` | done |
| FFM off-heap scratch arenas | `ffm` | done (0 GC, proven) |
| SIMD kernels — worldgen noise + flocking broadphase (Vector API + scalar fallback) | `simd`, `engine` | done (1.6–4.7x) |
| Self-tuning MSPT governor | `govern` | done; actuators wired + tested |
| Tick metrics + `/tachyon perf` | `metrics`, `command` | done |

The engine ticks against a `RegionizedTickWorld` adapter (synthetic `EntityWorld` now, a
`ServerLevel` adapter later), so the same `MosaicTicker` drives real Minecraft entities unchanged.

The algorithmic core (`core`, `soa`, `ffm`, `simd`, `govern`, `metrics`) depends only on the JDK,
so it compiles/runs regardless of MC mapping drift. Only `ServerActuators` and `TachyonCommand`
touch Minecraft internals. The deep Mosaic mixins are staged in `templates/mixin/` and get moved
into `src` + `tachyon.mixins.json` after their exact 26.1.2 signatures are confirmed via
`./gradlew genSources`.

## Validated on JDK 25 (Temurin 25.0.3)

`./gradlew run` exercises every core subsystem on the real JVM:

```
Tachyon self-test:
  [region-graph] 32 chunks -> 2 regions (expected 2)
  [scheduler] ticked 8/8 regions across 8 worker threads
  [soa] size=99 (after 1 removal) boxQuery=11 (expected 11)
  [ffm] ok (used=1024B, roundtrip=true)
  [simd] backend=simd(16x f32) sample[0..2]=-1.000,-0.502,0.023
```

SIMD vs scalar noise (`dev.tachyon.NoiseBench`, 1M samples × 5 octaves, AVX-512):

```
  scalar:  42.6 ms/iter   (24.6 Msamples/s)
  simd  :   9.0 ms/iter  (115.9 Msamples/s)
  speedup: 4.71x
```

Parallel entity engine (`dev.tachyon.EntityBench`) — 30k flocking entities, 36 regions, two axes
(region threads × SIMD flocking):

```
  scalar : serial=27.7 ms   parallel=4.3 ms   (6.5x threads)
  simd   : serial=17.0 ms   parallel=2.6 ms   (6.5x threads)
  SIMD flock single-thread: 1.63x  (naive gather lost at 0.64x; contiguous packing won)
  combined best vs scalar-serial baseline: 10.6x
```

FFM off-heap scratch vs heap allocation (`dev.tachyon.FfmScratchBench`, 512 KiB grid × 5000 ticks):

```
  heap new[] : 22 GC collections
  ffm reset  :  0 GC collections   (identical checksum; zero GC = no allocation-driven MSPT spikes)
```

Tests: `./gradlew test` → **15 passing**, including Mosaic **parallel == serial bitwise
determinism** (the core safety proof) under both static load and live cross-region migration,
SIMD/scalar parity for the noise and flocking kernels, governor tighten/relax/cooldown, RegionGraph
partitioning, and SoA swap-remove/query.

## Build

One unified build (MC 26.1 is unobfuscated, so Loom needs **no mappings** — see the note at the
top). The Gradle daemon must run on JDK 25; `.jdk25/` here is a no-space staged copy.

```bash
export JAVA_HOME=/path/to/jdk-25      # a no-space path; .jdk25/ here is a staged copy

# Real server mod
./gradlew build        # -> build/libs/tachyon-*.jar
./gradlew runServer    # boots a dev 26.1.2 server with the mod loaded

# MC-independent engine core (no server boot needed)
./gradlew selfTest     # the self-test above   (alias: ./gradlew run)
./gradlew noiseBench    # SIMD vs scalar noise
./gradlew entityBench  # parallel entity-tick engine
./gradlew ffmBench     # FFM off-heap scratch vs heap GC
./gradlew test         # JUnit determinism/parity suite (15 tests)
```

## Run as a server mod

Separate test instance — **NOT the live server**. Recommended JDK 25 flags:

```
-XX:+UseCompactObjectHeaders
--add-modules=jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

In-game: `/tachyon selftest`, `/tachyon perf` (live MSPT + phase breakdown), `/tachyon status`.
Toggle subsystems in `config/tachyon.properties`; `mosaic.enabled` defaults to **false**.
