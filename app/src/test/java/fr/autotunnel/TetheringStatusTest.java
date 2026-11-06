package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
public class TetheringStatusTest {
    private String dump(String down, String up) {
        return "Tethering:\n  Tether state:\n"+down+"\n    Upstream wanted: true\n    Current upstream interface(s): "+up
            +"\n  Hardware offload:\n    Offload HALs not started\n  BPF offload:\n    mIsBpfEnabled: true\n  Log:\n    wlan9 - TetheredState - lastError = 0\n";
    }
    @Test public void actualAndroidFormatSeparatesUpstreamAndDownstream() {
        TetheringStatus s=TetheringStatus.parse(dump("    wlan1 - TetheredState - lastError = 0","[rmnet_data3]"));
        assertEquals("wlan1",s.downstreams.get(0));assertEquals("rmnet_data3",s.upstreams.get(0));
        assertEquals(Boolean.TRUE,s.bpfEnabled);assertTrue(s.halNotStarted);
        assertTrue(s.describe().contains("NON QUALIFIÉ"));
    }
    @Test public void stoppedHotspotDoesNotReuseHistoricalInterface() {
        TetheringStatus s=TetheringStatus.parse(dump("","null"));
        assertTrue(s.downstreams.isEmpty());assertTrue(s.upstreams.isEmpty());
    }
    @Test public void malformedOrConflictingStateIsRejected() {
        for(String x:new String[]{dump("wlan1 - TetheredState - lastError = 1","[rmnet_data3]"),
            dump("wlan1 - TetheredState - lastError = 0","[wlan1]"),dump("","unknown"),"Log:\n"+dump("","null")}) {
            try{TetheringStatus.parse(x);fail();}catch(IllegalArgumentException expected){}
        }
    }
}
