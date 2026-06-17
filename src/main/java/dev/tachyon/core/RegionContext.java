package dev.tachyon.core;

/**
 * Binds the {@link Region} currently being ticked to the executing thread using a
 * JDK 25 {@link ScopedValue} (final in 25). Scoped values are immutable, cheaper
 * than {@code ThreadLocal}, and automatically unbound when the region tick scope
 * exits — so mixin code deep in the call stack can ask "which region am I in?"
 * without us threading a parameter through every MC method.
 */
public final class RegionContext {
    public static final ScopedValue<Region> CURRENT = ScopedValue.newInstance();

    private RegionContext() {}

    /** The region the current thread is ticking, or null if not inside a region tick. */
    public static Region current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    public static boolean isInRegion() {
        return CURRENT.isBound();
    }

    /** Run {@code body} with {@code region} bound as the current region. */
    public static void runIn(Region region, Runnable body) {
        ScopedValue.where(CURRENT, region).run(body);
    }
}
