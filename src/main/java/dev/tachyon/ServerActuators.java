package dev.tachyon;

import dev.tachyon.govern.ActivationState;
import dev.tachyon.govern.MsptGovernor;
import net.minecraft.server.MinecraftServer;

/**
 * Binds the governor's abstract knobs to the running server. Simulation distance is
 * pushed straight to the player list; activation range and mob-cap scale are
 * published to {@link ActivationState} for the throttling mixins to honour.
 *
 * <p>This is one of only two classes that touch Minecraft internals, so it is the
 * blast radius for mapping drift in a new MC version. The MC call is guarded so a
 * signature mismatch degrades gracefully instead of crashing the playground.
 */
final class ServerActuators implements MsptGovernor.Actuators {
    private final MinecraftServer server;
    private int simDistance;

    ServerActuators(MinecraftServer server, int initialSimDistance) {
        this.server = server;
        this.simDistance = initialSimDistance;
    }

    @Override
    public void setSimulationDistance(int chunks) {
        simDistance = chunks;
        try {
            server.getPlayerList().setSimulationDistance(chunks);
        } catch (Throwable t) {
            TachyonMod.LOG.debug("setSimulationDistance unavailable on this mapping: {}", t.toString());
        }
    }

    @Override
    public int simulationDistance() {
        return simDistance;
    }

    @Override
    public void setActivationRange(int blocks) {
        ActivationState.activationRangeBlocks = blocks;
    }

    @Override
    public int activationRange() {
        return ActivationState.activationRangeBlocks;
    }

    @Override
    public void setMobcapScale(double scale) {
        ActivationState.mobcapScale = scale;
    }

    @Override
    public double mobcapScale() {
        return ActivationState.mobcapScale;
    }
}
