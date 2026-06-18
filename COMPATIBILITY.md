# Tachyon — mod compatibility

Tachyon has **two modes**, and which one runs is decided automatically at load time by a Mixin
config plugin (`TachyonMixinPlugin`):

| Mode | When | What it does | Safe with other mods? |
|---|---|---|---|
| **Measure-only** | a conflicting deep tick/chunk mod is present | read-only region stats via `/tachyon regions`; **no** behaviour change | **Yes** — loads alongside anything |
| **Full takeover** | none of those mods present | parallel per-level + intra-level ticking (`mosaic.enabled` / `mosaic.intraLevel`) | only on a clean perf stack (see below) |

So the jar is **drop-in safe on any server**: on a normal modded server it quietly runs measure-only;
the takeover only engages where it can run safely. `/tachyon status` shows which mode is active.

## Hard incompatibilities (takeover auto-disabled when these are present)

These mods rewrite the exact vanilla systems Tachyon's takeover transforms, **and** they assume
single-threaded ticking — so the takeover cannot run with them. Detected mods:
`lithium`, `c2me`, `vmp`, `servercore`, `tt20`, `moonrise`, `noisium`.

Verified, not theoretical — booting Tachyon next to **Lithium** crashes at startup without the gate:

```
Mixin apply for mod tachyon failed … ServerChunkCacheMixin …
@At("FIELD") on …tachyon$effectiveOwner cannot inject into ServerChunkCache::getChunk
merged by net.caffeinemc.mods.lithium.mixin.world.chunk_access.ServerChunkCacheMixin
```

Lithium's `chunk_access` mixin rewrites `getChunk` and removes the `mainThread` field read Tachyon
redirects. Static mixin-overlap scan against the server snapshot (250 mods) confirmed the collisions:

| Mod | Overlaps Tachyon targets on |
|---|---|
| **Lithium** | `ServerChunkCache`, `EntityTickList`, `PersistentEntitySectionManager` (8 classes), `ServerLevel` (41) |
| **C2ME** | rewrites the whole chunk load/gen pipeline (`ServerChunkCache`, `ChunkMap`, async) |
| **VMP** | `ServerChunkCache`, `MinecraftServer`, `ServerLevel` |
| **ServerCore** | `ServerChunkCache`, `EntityTickList`, `MinecraftServer`, `ServerLevel` |
| **TT20** | `MinecraftServer`, `ServerLevel` (tick loop) |

Because the gate disables the takeover when any of these is present, **there is no crash** — the jar
loads in measure-only mode instead.

## Running the full takeover (a dedicated test instance)

The takeover **replaces** that optimization stack — it is its own parallel tick engine. To run it:

1. Remove: `lithium`, `c2me`, `vmp`, `servercore`, `tt20` (and `moonrise`/`noisium` if present).
2. Keep (compatible — different subsystems): FerriteCore (memory), Krypton (networking),
   ScalableLux (lighting), Debugify, content/mob mods, etc. These tick their entities/blocks under
   the takeover like vanilla.
3. Enable in `config/tachyon.properties`: `mosaic.enabled=true` (per-dimension parallelism) and/or
   `mosaic.intraLevel=true` (within-dimension parallelism).
4. JDK 25 flags: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders`.

`alternate-current` (redstone) and other block-logic mods are untested under the takeover; start with
a minimal set and add back gradually while watching `/tachyon perf`.

## Recommended path for a live server

- **Keep your current perf stack** and drop in Tachyon for **measure mode** — zero risk, and
  `/tachyon regions` shows how parallelizable your world actually is (per-dimension region counts).
- To trial the **takeover**, spin up a separate instance with the perf stack removed and copy a world
  in. `mosaic.enabled` and `mosaic.intraLevel` both default **off**; turn them on (or use
  `/tachyon mosaic on`) once booted.
