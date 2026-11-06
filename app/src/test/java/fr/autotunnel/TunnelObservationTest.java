package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelObservationTest {
    @Test public void expiryAndClockReversalInvalidateState() {
        TunnelObservation observation = new TunnelObservation("Interface locale active", 100, 50, 1000, 900, 1);
        assertTrue(observation.fresh(21000));
        assertFalse(observation.fresh(21001));
        assertFalse(observation.fresh(999));
        assertTrue(observation.describeAt(21001).startsWith("État actuel inconnu"));
        assertTrue(observation.describeAt(999).contains("horloge incohérente"));
    }
    @Test public void absentAndNeverEstablishedHandshakeDiffer() {
        assertTrue(new TunnelObservation("inconnu", -1, -1, 0).describeAt(1000).contains("handshake : indisponible"));
        assertTrue(new TunnelObservation("UP", 0, 0, 1000, 0, 1).describeAt(1000).contains("aucun dans cette mesure"));
        assertTrue(new TunnelObservation("UP", 50, 60, 1000, 900, 1).describeAt(1000).contains("Pairs mesurés : 1"));
    }
    @Test public void unsupportedCountersAreNotZeroTraffic() {
        assertFalse(InspectorData.validCounters(-1, 0));
        assertFalse(InspectorData.validCounters(0, -1));
        assertTrue(InspectorData.validCounters(0, 0));
        assertEquals(-1, InspectorData.rate(10, 50, 3000, false));
    }
}
