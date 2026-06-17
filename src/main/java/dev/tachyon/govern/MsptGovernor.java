package dev.tachyon.govern;

/**
 * Closed-loop controller that steers live MSPT toward a target by adjusting cheap
 * actuators, instead of running everything flat-out. It reacts to the rolling mean
 * MSPT with hysteresis (a dead-band around the target) and a cooldown so it does
 * not oscillate.
 *
 * <p>Under load it tightens in priority order (activation range → simulation
 * distance → mob-cap scale); with headroom it relaxes back toward defaults in the
 * reverse order. This is the "measure → adjust" loop that closes over the
 * instrumentation, the ServerCore/TT20-style throttles becoming its actuators.
 */
public final class MsptGovernor {

    /** What the governor can turn. Implemented by the server integration layer. */
    public interface Actuators {
        void setSimulationDistance(int chunks);
        int simulationDistance();

        void setActivationRange(int blocks);
        int activationRange();

        void setMobcapScale(double scale);
        double mobcapScale();
    }

    private final double targetMspt;
    private final double deadBand;
    private final long cooldownMs;
    private final Actuators act;

    private final int minSim, maxSim;
    private final int minAct, maxAct;
    private final double minMobcap;

    private long lastAdjustMs;
    private long adjustments;

    public MsptGovernor(double targetMspt, Actuators act) {
        this(targetMspt, act, 4, 12, 24, 96, 0.4, 3000L);
    }

    public MsptGovernor(double targetMspt, Actuators act,
                        int minSim, int maxSim, int minAct, int maxAct,
                        double minMobcap, long cooldownMs) {
        this.targetMspt = targetMspt;
        this.deadBand = Math.max(1.0, targetMspt * 0.10);
        this.act = act;
        this.minSim = minSim; this.maxSim = maxSim;
        this.minAct = minAct; this.maxAct = maxAct;
        this.minMobcap = minMobcap;
        this.cooldownMs = cooldownMs;
    }

    /** Feed the rolling mean MSPT each tick. Adjusts at most once per cooldown. */
    public void update(double meanMspt, long nowMs) {
        if (nowMs - lastAdjustMs < cooldownMs) {
            return;
        }
        if (meanMspt > targetMspt + deadBand) {
            tighten();
            lastAdjustMs = nowMs;
        } else if (meanMspt < targetMspt - deadBand) {
            relax();
            lastAdjustMs = nowMs;
        }
    }

    private void tighten() {
        if (act.activationRange() > minAct) {
            act.setActivationRange(Math.max(minAct, act.activationRange() - 8));
        } else if (act.simulationDistance() > minSim) {
            act.setSimulationDistance(act.simulationDistance() - 1);
        } else if (act.mobcapScale() > minMobcap) {
            act.setMobcapScale(round1(Math.max(minMobcap, act.mobcapScale() - 0.1)));
        } else {
            return;
        }
        adjustments++;
    }

    private void relax() {
        if (act.mobcapScale() < 1.0) {
            act.setMobcapScale(round1(Math.min(1.0, act.mobcapScale() + 0.05)));
        } else if (act.simulationDistance() < maxSim) {
            act.setSimulationDistance(act.simulationDistance() + 1);
        } else if (act.activationRange() < maxAct) {
            act.setActivationRange(Math.min(maxAct, act.activationRange() + 8));
        } else {
            return;
        }
        adjustments++;
    }

    public long adjustments() {
        return adjustments;
    }

    public double targetMspt() {
        return targetMspt;
    }

    private static double round1(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
