package dev.tachyon.engine;

import dev.tachyon.core.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * A synthetic, Minecraft-independent {@link RegionizedTickWorld} used to develop and
 * benchmark the engine. Hot state is struct-of-arrays (2D flocking, y fixed). Entities
 * are grouped into grid-cell regions — a stand-in for the connected-component regions
 * {@link dev.tachyon.core.RegionGraph} produces from loaded chunks.
 *
 * <p>The Phase-R behaviour is delegated to a {@link FlockKernel} (scalar or SIMD).
 * A {@code drift} can be applied so entities flow across the map and exercise live
 * cross-region migration (call {@link #repartition()} between ticks).
 */
public final class EntityWorld implements RegionizedTickWorld {
    public final int n;
    public final double regionSize;
    public final double[] x;
    public final double[] z;
    public final double[] vx;
    public final double[] vz;

    private final double[] sx; // snapshot buffers (reused each tick)
    private final double[] sz;
    private final double[] intentVx;
    private final double[] intentVz;

    private int[] regionOf;
    private List<Region> regions;
    private int[][] members;

    private double cohesion = 0.02;
    private double separation = 0.5;
    private double sepRadius2 = 16.0;
    private double maxStep = 0.5;
    private double driftX = 0.0;
    private double driftZ = 0.0;
    private FlockKernel kernel = FlockKernel.scalar();

    public EntityWorld(int n, double worldSize, double regionSize, long seed) {
        this.n = n;
        this.regionSize = regionSize;
        this.x = new double[n];
        this.z = new double[n];
        this.vx = new double[n];
        this.vz = new double[n];
        this.sx = new double[n];
        this.sz = new double[n];
        this.intentVx = new double[n];
        this.intentVz = new double[n];
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextDouble() * worldSize;
            z[i] = rng.nextDouble() * worldSize;
        }
        repartition();
    }

    private EntityWorld(EntityWorld o) {
        this.n = o.n;
        this.regionSize = o.regionSize;
        this.x = o.x.clone();
        this.z = o.z.clone();
        this.vx = o.vx.clone();
        this.vz = o.vz.clone();
        this.sx = new double[n];
        this.sz = new double[n];
        this.intentVx = new double[n];
        this.intentVz = new double[n];
        this.cohesion = o.cohesion;
        this.separation = o.separation;
        this.sepRadius2 = o.sepRadius2;
        this.maxStep = o.maxStep;
        this.driftX = o.driftX;
        this.driftZ = o.driftZ;
        this.kernel = o.kernel;
        repartition();
    }

    /** Deep copy of state + settings; repartitions identically. */
    public EntityWorld copy() {
        return new EntityWorld(this);
    }

    public void setKernel(FlockKernel k) {
        this.kernel = k;
    }

    public FlockKernel kernel() {
        return kernel;
    }

    public void setDrift(double dx, double dz) {
        this.driftX = dx;
        this.driftZ = dz;
    }

    public int regionOf(int slot) {
        return regionOf[slot];
    }

    /** Recompute grid-cell regions from current positions (deterministic order). */
    public void repartition() {
        if (regionOf == null) regionOf = new int[n];
        TreeMap<Long, List<Integer>> byCell = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            long cx = (long) Math.floor(x[i] / regionSize);
            long cz = (long) Math.floor(z[i] / regionSize);
            long key = (cz << 32) ^ (cx & 0xFFFFFFFFL);
            byCell.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<Region> rs = new ArrayList<>(byCell.size());
        int[][] mem = new int[byCell.size()][];
        int id = 0;
        for (List<Integer> cell : byCell.values()) {
            int[] arr = new int[cell.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = cell.get(k);
                regionOf[arr[k]] = id;
            }
            mem[id] = arr;
            rs.add(new Region(id, new long[0]));
            id++;
        }
        this.regions = rs;
        this.members = mem;
    }

    // --- RegionizedTickWorld ---

    @Override
    public List<Region> regions() {
        return regions;
    }

    public int regionCount() {
        return regions.size();
    }

    @Override
    public int[] members(int regionId) {
        return members[regionId];
    }

    @Override
    public void beginTick() {
        System.arraycopy(x, 0, sx, 0, n);
        System.arraycopy(z, 0, sz, 0, n);
    }

    @Override
    public void computeIntent(int slot) {
        kernel.compute(slot, members[regionOf[slot]], sx, sz,
                cohesion, separation, sepRadius2, intentVx, intentVz);
    }

    @Override
    public void computeRegion(int regionId) {
        kernel.computeRegion(members[regionId], sx, sz,
                cohesion, separation, sepRadius2, intentVx, intentVz);
    }

    @Override
    public void applyIntent(int slot) {
        double stepX = clamp(intentVx[slot], maxStep) + driftX;
        double stepZ = clamp(intentVz[slot], maxStep) + driftZ;
        vx[slot] = stepX;
        vz[slot] = stepZ;
        x[slot] += stepX;
        z[slot] += stepZ;
    }

    private static double clamp(double v, double max) {
        return v > max ? max : (v < -max ? -max : v);
    }
}
