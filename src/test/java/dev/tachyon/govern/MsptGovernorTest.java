package dev.tachyon.govern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The governor must close the loop: tighten actuators under sustained overload, relax
 * them back toward defaults when there's headroom, and respect its cooldown so it
 * doesn't oscillate.
 */
class MsptGovernorTest {

    /** In-memory actuators that just record the governor's decisions. */
    static final class RecordingActuators implements MsptGovernor.Actuators {
        int sim = 10;
        int act = 64;
        double cap = 1.0;

        public void setSimulationDistance(int c) { sim = c; }
        public int simulationDistance() { return sim; }
        public void setActivationRange(int b) { act = b; }
        public int activationRange() { return act; }
        public void setMobcapScale(double s) { cap = s; }
        public double mobcapScale() { return cap; }
    }

    @Test
    void tightensUnderLoadThenRelaxes() {
        RecordingActuators a = new RecordingActuators();
        MsptGovernor g = new MsptGovernor(35, a);

        long now = 0;
        for (int i = 0; i < 25; i++) {     // sustained overload
            g.update(55.0, now);
            now += 3001;                   // step past the cooldown each time
        }
        assertTrue(a.activationRange() < 64 || a.simulationDistance() < 10 || a.mobcapScale() < 1.0,
                "governor should tighten under sustained overload");
        assertTrue(g.adjustments() > 0);

        for (int i = 0; i < 60; i++) {     // lots of headroom
            g.update(8.0, now);
            now += 3001;
        }
        assertTrue(a.mobcapScale() >= 1.0 - 1e-9, "mob-cap should relax back to ~1.0 with headroom");
    }

    @Test
    void respectsCooldown() {
        RecordingActuators a = new RecordingActuators();
        MsptGovernor g = new MsptGovernor(35, a);
        long now = 10_000;
        g.update(55.0, now);                       // one adjustment
        long after = g.adjustments();
        g.update(55.0, now + 5);                    // within cooldown -> ignored
        g.update(55.0, now + 50);                   // within cooldown -> ignored
        assertEquals(after, g.adjustments(), "no extra adjustments within the cooldown window");
    }
}
