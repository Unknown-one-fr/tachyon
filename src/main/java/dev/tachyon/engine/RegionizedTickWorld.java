package dev.tachyon.engine;

import dev.tachyon.core.Region;

import java.util.List;

/**
 * The contract the Mosaic engine ticks against — the seam between the scheduler and
 * the world being simulated. Implemented today by the synthetic {@link EntityWorld};
 * the future Minecraft integration provides a {@code ServerLevel} adapter with the
 * same shape, so {@link MosaicTicker} never changes.
 *
 * <p>Lifecycle / threading contract the engine relies on:
 * <ul>
 *   <li>{@link #beginTick()} — once, main thread, before the parallel phase
 *       (take the immutable snapshot here).</li>
 *   <li>{@link #computeIntent(int)} — parallel phase. <b>Must be read-only</b> w.r.t.
 *       shared state: read the snapshot, write only this entity's private intent.</li>
 *   <li>{@link #applyIntent(int)} — parallel phase, but single-writer: only ever
 *       called for entities the calling region owns, so writes never race.</li>
 *   <li>{@link #endTick()} — once, main thread, after the barrier.</li>
 * </ul>
 * Honour those and parallel execution is deterministic and equal to serial.
 */
public interface RegionizedTickWorld {
    List<Region> regions();

    int[] members(int regionId);

    void beginTick();

    void computeIntent(int entitySlot);

    /** Phase R for a whole region; default loops {@link #computeIntent}, adapters may batch. */
    default void computeRegion(int regionId) {
        for (int slot : members(regionId)) computeIntent(slot);
    }

    void applyIntent(int entitySlot);

    default void endTick() {
    }
}
