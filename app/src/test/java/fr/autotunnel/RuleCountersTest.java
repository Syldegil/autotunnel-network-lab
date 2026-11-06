package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RuleCountersTest {
    @Test public void readsOnlyOwnedCountersAndDropsRuleContents() {
        Map<String, long[]> counters = RuleCounters.parse("[4:400] -A AT13_U4F -p tcp --dport 443 -j DROP\n"
            + "[5:500] -A AT13_U4F -m comment --comment PRIVATE-DATA -j RETURN\n[9:900] -A netd_FORWARD -j ACCEPT\n");
        assertEquals(2, counters.size()); assertArrayEquals(new long[]{4, 400}, counters.get("AT13_U4F:1"));
        assertFalse(counters.keySet().toString().contains("PRIVATE-DATA"));
    }
    @Test public void malformedOrOverflowCounterDoesNotBecomeZero() {
        assertTrue(RuleCounters.parse("[9999999999999999999:1] -A AT13_U4F -j DROP\n[bad:0] -A AT13_U4F -j DROP").isEmpty());
    }
}
