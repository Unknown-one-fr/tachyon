package dev.tachyon.mc;

/**
 * Duck-typed onto {@code ServerChunkCache} by a mixin so the engine can register/unregister the
 * worker threads currently allowed to access this level's chunk cache inline (single-writer per
 * region). See {@code ServerChunkCacheMixin} and {@link IntraLevelEntityTicker}.
 */
public interface RegionOwnable {
    void tachyon$addOwner(Thread t);
    void tachyon$removeOwner(Thread t);
    void tachyon$clearOwners();
    boolean tachyon$isOwner(Thread t);
}
