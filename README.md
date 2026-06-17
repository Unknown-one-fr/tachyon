# Tachyon

Experimental, **max-performance** Fabric **server** engine for **Minecraft 26.1.2 / JDK 25**.
A playground build that fuses ideas from many optimization mods into one and pushes JDK 25–only
techniques. **Not for production worlds** — expect instability and mod incompatibility.

> ### Branch `fabric-26.1` — real mod, deobfuscated MC
> MC 26.1 ships **unobfuscated** (native Mojang names; no Yarn, no mappings download). This branch
> builds the actual Fabric server mod against it; the standalone engine core lives on `main`.
>
> **Milestone 1 ✅ — the mod compiles AND loads on a Fabric 26.1.2 server.** The dev server boots
> with the mod and the startup self-test passes *live*: parallel region scheduler, FFM off-heap,
> AVX-512 SIMD, and Compact Object Headers all active in-server (`Mappings not present!` confirms the
> deobfuscated path); clean startup + shutdown, no errors.
>
> Build here: `./gradlew build` → `build/libs/tachyon-*.jar`; `./gradlew runServer` boots a dev
> server with the mod. Uses `net.fabricmc.fabric-loom` (non-remapping), **no `mappings` line**,
> `implementation`/`jar`, access-widener `official` namespace. Gradle daemon must run on JDK 25.
> Milestone 2 (next): `ServerLevelAdapter` + route entity ticking through `MosaicTicker`.

## What's here (v0.1.0-experimental)

| Subsystem | Package | Status |
|---|---|---|
| Mosaic region engine (parallel, serial-within-region, single-writer) | `core` | core done; MC hookup staged |
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

Two stages, because **no dev mappings (Yarn or official Mojang) are published for 26.1.2** in this
environment — Loom can't set up the MC workspace yet.

```bash
export JAVA_HOME=/path/to/jdk-25      # a no-space path; .jdk25/ here is a staged copy
./gradlew build    # builds the MC-independent engine core -> build/libs/tachyon-*.jar
./gradlew run      # runs the self-test above
```

When 26.1.2 dev mappings land: `mv build.fabric.gradle.txt build.gradle`, then
`./gradlew genSources` (confirm the mixin signatures), move `templates/mixin/*` and the integration
files (`TachyonMod`, `ServerActuators`, `command/`) back into the compiled set, and `./gradlew build`
produces the actual server jar.

## Run as a server mod (once the Fabric build is unblocked)

Separate test instance — **NOT the live server**. Recommended JDK 25 flags:

```
-XX:+UseCompactObjectHeaders
--add-modules=jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

In-game: `/tachyon selftest`, `/tachyon perf` (live MSPT + phase breakdown), `/tachyon status`.
Toggle subsystems in `config/tachyon.properties`; `mosaic.enabled` defaults to **false**.
