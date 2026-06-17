package dev.tachyon.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Per-worker off-heap scratch memory using the JDK 25 Foreign Function &amp; Memory
 * API. Each thread gets a long-lived <em>confined</em> {@link Arena} with a single
 * pre-allocated segment; callers bump-allocate slices and {@link Scratch#reset()}
 * the bump pointer once per work unit.
 *
 * <p>This removes short-lived native buffers (pathfinding grids/open-sets, voxel
 * shape temporaries, packet assembly, chunk serialization) from the Java heap
 * entirely — no GC pressure, and no repeated native malloc/free. It directly
 * targets the ~30% native alloc/memcpy self-time seen in profiling. Confined arenas
 * need no synchronization because only their owning worker ever touches them.
 */
public final class ScratchArenaPool {
    private static final long DEFAULT_BYTES = 8L * 1024 * 1024; // 8 MiB per worker

    private final ThreadLocal<Scratch> threadLocal;
    private final long bytesPerThread;

    public ScratchArenaPool(long bytesPerThread) {
        this.bytesPerThread = bytesPerThread > 0 ? bytesPerThread : DEFAULT_BYTES;
        this.threadLocal = ThreadLocal.withInitial(() -> new Scratch(this.bytesPerThread));
    }

    public ScratchArenaPool() {
        this(DEFAULT_BYTES);
    }

    /** The calling thread's scratch arena (created lazily, confined to this thread). */
    public Scratch local() {
        return threadLocal.get();
    }

    public long bytesPerThread() {
        return bytesPerThread;
    }

    public static final class Scratch implements AutoCloseable {
        private final Arena arena;
        private final MemorySegment base;
        private final long cap;
        private long offset;

        Scratch(long bytes) {
            this.arena = Arena.ofConfined();
            this.base = arena.allocate(bytes);
            this.cap = bytes;
        }

        /**
         * Bump-allocate {@code bytes} aligned to {@code align}. Returns {@code null}
         * if the arena is exhausted so the caller can fall back to the heap rather
         * than crash.
         */
        public MemorySegment alloc(long bytes, long align) {
            long aligned = (offset + (align - 1)) & ~(align - 1);
            if (aligned + bytes > cap) {
                return null;
            }
            MemorySegment slice = base.asSlice(aligned, bytes);
            offset = aligned + bytes;
            return slice;
        }

        /** Reset the bump pointer. O(1): no native free, memory is reused next unit. */
        public void reset() {
            offset = 0;
        }

        public long used() {
            return offset;
        }

        public long capacity() {
            return cap;
        }

        /** Frees the backing native memory. Only at shutdown — normally reset() instead. */
        @Override
        public void close() {
            arena.close();
        }
    }
}
