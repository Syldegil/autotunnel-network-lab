package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackFilterTrialTest {
    @Test public void oneTupleOnlyWithExactIdempotentCheckAndDelete() {
        LoopbackFilterTrial p = new LoopbackFilterTrial("0123456789abcdef", 1800000000L, 20);
        assertEquals(p.install.subList(6,p.install.size()), p.remove.subList(6,p.remove.size()));
        assertEquals(p.remove.subList(6,p.remove.size()), p.check.subList(6,p.check.size()));
        assertTrue(p.install.contains("lo")); assertTrue(p.install.contains("127.0.0.1/32"));
        assertTrue(p.install.contains("2000")); assertFalse(p.install.contains("FORWARD"));
        assertFalse(p.remove.contains("-F")); assertFalse(p.install.contains("--kerneltz"));
    }
    @Test public void kernelWindowHasInclusiveEndAndClockReversalMustNotBeHidden() {
        LoopbackFilterTrial p = new LoopbackFilterTrial("0123456789abcdef",1800000000L,20);
        assertFalse(p.withinKernelWindow(1799999999L)); assertTrue(p.withinKernelWindow(1800000020L));
        assertFalse(p.withinKernelWindow(1800000021L));
        assertTrue(p.withinKernelWindow(1800000001L)); // deliberately documents a backward-clock hazard
    }
    @Test public void unboundedOrInjectedTrialsAreRejected() {
        for (int seconds : new int[]{0,31,Integer.MAX_VALUE})
            try { new LoopbackFilterTrial("0123456789abcdef",1800000000L,seconds); fail(); } catch (IllegalArgumentException expected) { }
        try { new LoopbackFilterTrial("; reboot",1800000000L,20); fail(); } catch (IllegalArgumentException expected) { }
    }
}
