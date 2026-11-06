package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class InspectorHistoryTest {
    private Map<String,long[]> counters(long rx,long tx) { return Collections.singletonMap("wlan0",new long[]{rx,tx}); }
    private Map<String,String> identity(String value) { return Collections.singletonMap("wlan0",value); }
    @Test public void ratesUseElapsedTimeAndFirstSampleIsUnknown() {
        InspectorHistory h=new InspectorHistory(); h.add(1000,counters(100,200),identity("a")); h.add(4000,counters(6100,3200),identity("a"));
        assertEquals(-1,h.points("wlan0",60000).get(0).rx); assertEquals(2000,h.points("wlan0",60000).get(1).rx); assertEquals(1000,h.points("wlan0",60000).get(1).tx);
    }
    @Test public void resetSourceChangeAndLongGapAreNotZeroes() {
        InspectorHistory h=new InspectorHistory(); h.add(1000,counters(100,200),identity("a")); h.add(4000,counters(1,2),identity("a"));
        h.add(7000,counters(500,600),identity("b")); h.add(25000,counters(1000,1200),identity("b"));
        for(InspectorHistory.Point p:h.points("wlan0",60000)) { assertEquals(-1,p.rx); assertEquals(-1,p.tx); }
    }
    @Test public void missingCounterCannotBridgeSamples() {
        InspectorHistory h=new InspectorHistory(); h.add(1000,counters(100,200),identity("a")); h.add(4000,counters(400,500),identity("a"));
        h.add(7000,Collections.emptyMap(),Collections.emptyMap()); h.add(10000,counters(1000,1100),identity("a"));
        assertEquals(-1,h.points("wlan0",60000).get(2).rx); assertEquals(-1,h.points("wlan0",60000).get(3).rx);
    }
    @Test public void boundedHistoryRejectsOutOfOrderAndClears() {
        InspectorHistory h=new InspectorHistory(); for(int i=0;i<1000;i++) h.add(1000+i*3000,counters(i*100,i*200),identity("a"));
        assertEquals(301,h.points("wlan0",900000).size()); assertEquals(21,h.points("wlan0",60000).size());
        h.add(100,counters(0,0),identity("a")); assertEquals(301,h.points("wlan0",900000).size()); h.clear(); assertTrue(h.points("wlan0",900000).isEmpty());
    }
    @Test public void inputIsCopiedAndMissingIdentityNeverProducesRate() {
        InspectorHistory h=new InspectorHistory(); Map<String,long[]> c=counters(100,200); h.add(1000,c,identity("a")); c.get("wlan0")[0]=10000;
        h.add(4000,counters(400,500),identity("a")); assertEquals(100,h.points("wlan0",60000).get(1).rx);
        h.add(7000,counters(700,800),Collections.emptyMap()); assertEquals(-1,h.points("wlan0",60000).get(2).rx);
    }
}
