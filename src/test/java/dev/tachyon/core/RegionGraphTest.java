package dev.tachyon.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionGraphTest {

    @Test
    void emptySetYieldsNoRegions() {
        assertEquals(0, new RegionGraph(2).partition(new HashSet<>()).size());
    }

    @Test
    void contiguousBlobIsOneRegion() {
        Set<Long> loaded = new HashSet<>();
        for (int x = 0; x < 6; x++)
            for (int z = 0; z < 6; z++)
                loaded.add(ChunkKey.of(x, z));
        List<Region> regions = new RegionGraph(2).partition(loaded);
        assertEquals(1, regions.size());
        assertEquals(36, regions.get(0).chunkCount());
    }

    @Test
    void wellSeparatedClustersSplit() {
        Set<Long> loaded = new HashSet<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) loaded.add(ChunkKey.of(x, z));
        for (int x = 50; x < 53; x++) for (int z = 50; z < 53; z++) loaded.add(ChunkKey.of(x, z));
        assertEquals(2, new RegionGraph(2).partition(loaded).size());
    }

    @Test
    void gapWithinInteractionRadiusMerges() {
        // two chunks 2 apart, radius 2 (Chebyshev) -> same region
        Set<Long> loaded = new HashSet<>();
        loaded.add(ChunkKey.of(0, 0));
        loaded.add(ChunkKey.of(2, 0));
        assertEquals(1, new RegionGraph(2).partition(loaded).size());
        // radius 1 -> they are 2 apart -> separate
        assertEquals(2, new RegionGraph(1).partition(loaded).size());
    }

    @Test
    void partitionCoversEveryChunkOnce() {
        Set<Long> loaded = new HashSet<>();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 4; z++) loaded.add(ChunkKey.of(x, z));
        int counted = 0;
        for (Region r : new RegionGraph(2).partition(loaded)) counted += r.chunkCount();
        assertEquals(loaded.size(), counted);
        assertTrue(counted > 0);
    }
}
