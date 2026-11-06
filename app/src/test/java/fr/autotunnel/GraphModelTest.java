package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

public class GraphModelTest {
    private GraphModel graph() { return GraphModel.initial(RoutingProfile.defaults(RoutingProfile.Scope.PHONE)); }
    private void rejects(Runnable call) { try { call.run(); fail("Accepted invalid topology"); } catch (IllegalArgumentException expected) { } }
    @Test public void portsSeparateOverlayOuterTransportAndObservation() {
        assertTrue(Port.OUT_IP.accepts(Port.IN_IP)); assertTrue(Port.TRANSPORT_OUT.accepts(Port.UPLINK_IN));
        assertTrue(Port.PHYSICAL_OUT.accepts(Port.PHYSICAL_IN)); assertTrue(Port.MONITOR_OUT.accepts(Port.MONITOR_IN));
        for (Port output : Port.values()) for (Port input : Port.values())
            assertEquals(output.output && !input.output && output.type == input.type, output.accepts(input));
        rejects(() -> new Link("bad", "n2", Port.TRANSPORT_OUT, "n3", Port.IN_IP, true, Properties.defaults()));
    }
    @Test public void rejectsDanglingPortsDuplicatesAndCyclesIncludingDescriptiveCycles() {
        GraphModel g = graph();
        rejects(() -> g.replace(new Link("dangling", "n0", Port.OUT_IP, "missing", Port.IN_IP, true, Properties.defaults())));
        rejects(() -> g.replace(new Link("wrongPort", "n0", Port.TRANSPORT_OUT, "upstream", Port.UPLINK_IN, false, Properties.defaults())));
        rejects(() -> g.replace(new Link("duplicate", "n0", Port.OUT_IP, "n1", Port.IN_IP, true, Properties.defaults())));
        rejects(() -> g.replace(new Link("cycle", "n2", Port.OUT_IP, "n1", Port.IN_IP, false, Properties.defaults())));
        rejects(() -> g.replace(new Link("self", "n1", Port.OUT_IP, "n1", Port.IN_IP, true, Properties.defaults())));
        List<Node> nodes = new ArrayList<>(g.nodes); nodes.add(g.nodes.get(0));
        rejects(() -> g.with(nodes, g.links));
    }
    @Test public void removalIsAtomicAndDoesNotMutateOriginal() {
        GraphModel original = graph(), next = original.remove("n2");
        assertNotNull(original.node("n2")); assertNull(next.node("n2"));
        assertNull(next.link("e2")); assertNull(next.link("e3")); assertNull(next.link("transport"));
        assertNotNull(next.link("physicalLink"));
        try { next.nodes.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void graphBoundsAndCoordinatesAreValidated() {
        GraphModel g = graph(); Node n = g.node("n0");
        rejects(() -> n.move(Float.NaN, 1)); rejects(() -> n.move(Float.POSITIVE_INFINITY, 1)); rejects(() -> n.move(100001, 1));
        rejects(() -> g.camera(0, 0, 0, ViewMode.LOGICAL)); rejects(() -> g.camera(0, 0, Float.NaN, ViewMode.LOGICAL));
        List<Node> nodes = new ArrayList<>(); for (int i = 0; i < 101; i++) nodes.add(new Node("id" + i, Kind.ROUTER, "R", 0, 0, false, Properties.defaults()));
        rejects(() -> g.with(nodes, Collections.emptyList()));
    }
    @Test public void propertiesRejectAmbiguousFamiliesAndUnsafeInterfaces() {
        rejects(() -> new Properties(null, Policy.INHERIT, true, "192.0.2.0/24", "", "", "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        rejects(() -> new Properties(RoutingProfile.Family.BOTH, Policy.INHERIT, true, "192.0.2.0/24", "", "", "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        rejects(() -> new Properties(null, Policy.INHERIT, true, "", "", "wlan0;id", "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        rejects(() -> new Properties(null, Policy.INHERIT, true, "", "", "", "", Collections.singletonList("example.org"), Collections.emptyList(), Collections.emptyList()));
    }
    @Test public void layoutRespectsTopologyAndPreservesPolicies() {
        GraphModel g = graph().layout();
        for (Link link : g.links) assertTrue(g.node(link.to).x > g.node(link.from).x);
        assertEquals(6, g.links.size()); assertEquals(RoutingProfile.Mode.REQUIRE_TUNNEL, g.profile.mode);
        assertFalse(g.link("transport").enabled); assertEquals(Port.TRANSPORT_OUT, g.link("transport").output);
        assertFalse(g.link("freeboxTransport").enabled);
        assertEquals(Kind.FREEBOX, g.node(g.link("freeboxTransport").to).kind);
    }
}
