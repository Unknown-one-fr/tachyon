package dev.tachyon.soa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySoAStoreTest {

    @Test
    void putUpdateAndSizeGrows() {
        EntitySoAStore s = new EntitySoAStore(4); // forces a grow
        for (int i = 0; i < 50; i++) s.put(i, 0, i, 64, 0, 0, 0, 0, 0.3f, 1f, 0.3f, 0L);
        assertEquals(50, s.size());
        // update existing id does not add a slot
        s.put(10, 1, 999, 64, 0, 0, 0, 0, 0.3f, 1f, 0.3f, 0L);
        assertEquals(50, s.size());
        assertTrue(s.contains(10));
    }

    @Test
    void swapRemoveKeepsOthersIntact() {
        EntitySoAStore s = new EntitySoAStore(16);
        for (int i = 0; i < 10; i++) s.put(i, 0, i, 64, i, 0, 0, 0, 0.3f, 1f, 0.3f, 0L);
        s.remove(3); // last element (id 9) swaps into slot 3
        assertEquals(9, s.size());
        assertFalse(s.contains(3));
        assertTrue(s.contains(9));

        // every surviving entity must still be found by a box that covers all of them
        int[] hits = new int[32];
        int found = s.queryBox(-1, 0, -1, 100, 128, 100, hits);
        assertEquals(9, found);
    }

    @Test
    void queryBoxFiltersByPosition() {
        EntitySoAStore s = new EntitySoAStore(64);
        for (int i = 0; i < 100; i++) s.put(i, 0, i, 64, 0, 0, 0, 0, 0.3f, 1f, 0.3f, 0L);
        int[] hits = new int[128];
        int found = s.queryBox(10, 0, -1, 20, 128, 1, hits); // x in [10,20] -> 11 entities
        assertEquals(11, found);
    }
}
