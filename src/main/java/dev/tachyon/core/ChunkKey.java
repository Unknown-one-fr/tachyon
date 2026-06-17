package dev.tachyon.core;

/**
 * Packs/unpacks chunk coordinates into a single long, bit-compatible with
 * Minecraft's {@code ChunkPos.asLong(x, z)} so our keys can be used directly
 * against MC chunk maps without translation.
 */
public final class ChunkKey {
    private ChunkKey() {}

    public static long of(int x, int z) {
        return (((long) x) & 0xFFFFFFFFL) | ((((long) z) & 0xFFFFFFFFL) << 32);
    }

    public static int x(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int z(long key) {
        return (int) ((key >>> 32) & 0xFFFFFFFFL);
    }

    public static String describe(long key) {
        return "(" + x(key) + ", " + z(key) + ")";
    }
}
