package dev.tachyon.govern;

/**
 * Live actuator knobs the {@link MsptGovernor} writes and the entity-tick / spawning
 * mixins read. Kept as plain statics so deep hot-path code can read them with zero
 * indirection. Simulation distance is applied straight to the server; these two are
 * honoured by our own throttling layer.
 */
public final class ActivationState {
    private ActivationState() {}

    /** Beyond this distance (blocks) from any player, non-essential entity ticking is skipped. */
    public static volatile int activationRangeBlocks = 64;

    /** Multiplier applied to mob spawn caps (1.0 = vanilla). */
    public static volatile double mobcapScale = 1.0;
}
