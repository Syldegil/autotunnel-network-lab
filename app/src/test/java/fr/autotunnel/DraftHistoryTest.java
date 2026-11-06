package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;

public class DraftHistoryTest {
    @Test public void restoreKeepsExactPriorDnsRoutesAndRulesWithoutMutatingOriginal() {
        RoutingProfile original = new RoutingProfile(Scope.ALL, Mode.REQUIRE_TUNNEL, V6.BLOCK,
            entries("192.0.2.0/24"), entries(""), entries("192.0.2.53"), java.util.Collections.singletonList(
                new Rule(Direction.IN, Protocol.TCP, Family.BOTH, Path.USB, Action.BLOCK, 20, 25, "", "", 10, true)));
        DraftHistory first = new DraftHistory(original, null, 0);
        DraftHistory changed = first.save(defaults(Scope.ALL));
        DraftHistory restored = changed.undo();
        assertSame(original, restored.current); assertEquals(2, restored.revision);
        assertEquals("192.0.2.53", restored.current.dns.get(0));
        assertEquals(1, restored.current.rules.size()); assertSame(original, first.current);
        assertSame(changed.current, restored.undo().current);
    }
    @Test(expected = IllegalStateException.class) public void noInventedBaselineForUndo() {
        new DraftHistory(defaults(Scope.PHONE), null, 0).undo();
    }
    @Test(expected = IllegalArgumentException.class) public void cannotRestoreAnotherScope() {
        new DraftHistory(defaults(Scope.WIFI), null, 0).save(defaults(Scope.USB));
    }
}
