package dev.tachyon.soa;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Struct-of-Arrays storage for the hot per-entity fields that dominate the tick
 * profile (position, velocity, AABB half-extents, type id, packed flags).
 *
 * <p>Why: vanilla stores these as fields on heavy polymorphic {@code Entity}
 * objects, so the per-tick scan pointer-chases across the heap and every virtual
 * call is megamorphic. Laying the hot fields out as parallel primitive arrays makes
 * the scan cache-linear and auto-vectorizable, turns {@code EntityLookup.getEntities}
 * into a dense range scan, and lets us batch entities of one concrete type so the
 * JIT sees monomorphic call sites. Together that targets four of the spark hotspots
 * at once (native memcpy/alloc churn, megamorphic dispatch, EntityLookup, SynchedEntityData).
 *
 * <p>Slots are kept dense via swap-remove. Not thread-safe by design: each region
 * owns its entities and mutates its slots single-threaded; the read snapshot for
 * Phase R copies out of here.
 */
public final class EntitySoAStore {
    private int capacity;
    private int size;

    private int[] entityId;     // MC network id; -1 == free
    private double[] x, y, z;
    private double[] vx, vy, vz;
    private float[] hx, hy, hz; // AABB half-extents
    private int[] typeId;
    private long[] flags;

    private final HashMap<Integer, Integer> idToSlot = new HashMap<>();

    public EntitySoAStore(int initialCapacity) {
        capacity = Math.max(64, initialCapacity);
        entityId = new int[capacity];
        Arrays.fill(entityId, -1);
        x = new double[capacity]; y = new double[capacity]; z = new double[capacity];
        vx = new double[capacity]; vy = new double[capacity]; vz = new double[capacity];
        hx = new float[capacity]; hy = new float[capacity]; hz = new float[capacity];
        typeId = new int[capacity];
        flags = new long[capacity];
    }

    public int size() {
        return size;
    }

    public boolean contains(int id) {
        return idToSlot.containsKey(id);
    }

    /** Insert or update an entity's hot fields. Returns its slot. */
    public int put(int id, int type,
                   double px, double py, double pz,
                   double dvx, double dvy, double dvz,
                   float halfX, float halfY, float halfZ,
                   long packedFlags) {
        Integer existing = idToSlot.get(id);
        int slot;
        if (existing == null) {
            if (size == capacity) grow();
            slot = size++;
            entityId[slot] = id;
            idToSlot.put(id, slot);
        } else {
            slot = existing;
        }
        typeId[slot] = type;
        x[slot] = px; y[slot] = py; z[slot] = pz;
        vx[slot] = dvx; vy[slot] = dvy; vz[slot] = dvz;
        hx[slot] = halfX; hy[slot] = halfY; hz[slot] = halfZ;
        flags[slot] = packedFlags;
        return slot;
    }

    /** Remove by entity id via swap with the last slot to keep the arrays dense. */
    public void remove(int id) {
        Integer s = idToSlot.remove(id);
        if (s == null) return;
        int slot = s;
        int last = --size;
        if (slot != last) {
            entityId[slot] = entityId[last];
            x[slot] = x[last]; y[slot] = y[last]; z[slot] = z[last];
            vx[slot] = vx[last]; vy[slot] = vy[last]; vz[slot] = vz[last];
            hx[slot] = hx[last]; hy[slot] = hy[last]; hz[slot] = hz[last];
            typeId[slot] = typeId[last];
            flags[slot] = flags[last];
            idToSlot.put(entityId[slot], slot);
        }
        entityId[last] = -1;
    }

    /**
     * Dense AABB broadphase: append the ids of all entities whose AABB intersects
     * the box to {@code out}, returning the count. A flat scan over contiguous
     * primitive arrays the JIT can auto-vectorize — the SoA replacement for
     * pointer-chasing {@code getEntities}.
     */
    public int queryBox(double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ,
                        int[] out) {
        int n = 0;
        final int sz = size;
        for (int i = 0; i < sz && n < out.length; i++) {
            double px = x[i], py = y[i], pz = z[i];
            if (px + hx[i] >= minX && px - hx[i] <= maxX
                    && py + hy[i] >= minY && py - hy[i] <= maxY
                    && pz + hz[i] >= minZ && pz - hz[i] <= maxZ) {
                out[n++] = entityId[i];
            }
        }
        return n;
    }

    // --- accessors by slot (hot path reads) ---
    public int entityIdAt(int slot) { return entityId[slot]; }
    public int typeAt(int slot) { return typeId[slot]; }
    public double xAt(int slot) { return x[slot]; }
    public double yAt(int slot) { return y[slot]; }
    public double zAt(int slot) { return z[slot]; }
    public long flagsAt(int slot) { return flags[slot]; }

    private void grow() {
        int n = capacity + (capacity >> 1); // 1.5x
        entityId = Arrays.copyOf(entityId, n);
        Arrays.fill(entityId, capacity, n, -1);
        x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); z = Arrays.copyOf(z, n);
        vx = Arrays.copyOf(vx, n); vy = Arrays.copyOf(vy, n); vz = Arrays.copyOf(vz, n);
        hx = Arrays.copyOf(hx, n); hy = Arrays.copyOf(hy, n); hz = Arrays.copyOf(hz, n);
        typeId = Arrays.copyOf(typeId, n);
        flags = Arrays.copyOf(flags, n);
        capacity = n;
    }
}
