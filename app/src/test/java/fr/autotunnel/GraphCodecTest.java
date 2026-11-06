package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

public class GraphCodecTest {
    private GraphModel graph() { return GraphModel.initial(RoutingProfile.defaults(RoutingProfile.Scope.USB)); }
    @Test public void roundTripPreservesEveryCatalogueKindPropertyCameraAndRule() throws Exception {
        RoutingProfile.Rule rule = new RoutingProfile.Rule(RoutingProfile.Direction.FORWARD, RoutingProfile.Protocol.UDP,
            RoutingProfile.Family.IPV4, RoutingProfile.Path.USB, RoutingProfile.Action.BLOCK, 53, 5353, "192.0.2.0/24", "198.51.100.0/24", 42, false, "simusb0", "simvpn0");
        RoutingProfile p = new RoutingProfile(RoutingProfile.Scope.USB, RoutingProfile.Mode.REQUIRE_TUNNEL, RoutingProfile.V6.REQUIRE_TUNNEL,
            Collections.singletonList("0.0.0.0/0"), Collections.singletonList("::/0"), Collections.singletonList("192.0.2.53"), Collections.singletonList(rule));
        Properties props = new Properties(RoutingProfile.Family.IPV4, Policy.REQUIRE_TUNNEL, false, "192.0.2.0/24", "198.51.100.0/24", "simusb0", "simvpn0",
            Collections.singletonList("192.0.2.53"), Collections.singletonList("192.0.2.0/24"), Collections.singletonList("2001:db8::/32"));
        GraphModel g = graph().profile(p).camera(-120, 123, .6f, ViewMode.LIVE);
        for (Kind kind : Kind.values()) g = g.replace(new Node("extra" + kind.ordinal(), kind, kind.title, kind.ordinal() * 3.5f, -20, false, props));
        Link link = g.link("e1"); g = g.replace(new Link(link.id, link.from, link.output, link.to, link.input, false, props));
        byte[] encoded = GraphCodec.encode(g); GraphModel decoded = GraphCodec.decode(encoded);
        assertArrayEquals(encoded, GraphCodec.encode(decoded)); assertEquals(Kind.values().length + 7, decoded.nodes.size());
        assertEquals(-120, decoded.panX, 0); assertEquals(ViewMode.LIVE, decoded.view); assertEquals("simusb0", decoded.profile.rules.get(0).inputInterface);
        assertEquals(5353, decoded.profile.rules.get(0).lastPort); assertFalse(decoded.link("e1").properties.blockUnavailable);
    }
    @Test public void rejectsTruncationUnknownSchemaTrailingDataAndOversize() throws Exception {
        byte[] bytes = GraphCodec.encode(graph());
        for (int end : new int[]{0, 4, 8, 40, bytes.length - 1}) rejects(Arrays.copyOf(bytes, end));
        byte[] unknown = bytes.clone(); unknown[7] = 99; rejects(unknown);
        rejects(Arrays.copyOf(bytes, bytes.length + 1)); rejects(new byte[GraphCodec.MAX_BYTES + 1]);
    }
    private void rejects(byte[] bytes) { try { GraphCodec.decode(bytes); fail("Corrupt graph accepted"); } catch (IOException expected) { } }
    @Test public void undoRedoCheckpointSurviveRestartAndBranching() throws Exception {
        GraphHistory h = new GraphHistory(graph()); Node n = h.current.node("n0");
        h = h.change(h.current.replace(n.move(100, 200))).checkpoint();
        h = h.change(h.current.remove("n2")); assertNull(h.current.node("n2"));
        h = GraphHistory.decode(h.encode()).undo(); assertNotNull(h.current.node("n2")); assertEquals(100, h.current.node("n0").x, 0);
        h = GraphHistory.decode(h.encode()).redo(); assertNull(h.current.node("n2"));
        h = h.restore(); assertNotNull(h.current.node("n2")); assertEquals(100, h.current.node("n0").x, 0);
        h = h.undo(); assertNull(h.current.node("n2"));
        h = h.change(h.current.camera(50, 60, 2, ViewMode.PHYSICAL)); assertTrue(h.redo.isEmpty());
        assertArrayEquals(h.encode(), GraphHistory.decode(h.encode()).encode());
    }
    @Test public void boundedHistoryDoesNotDiscardCurrentOrCheckpoint() throws Exception {
        GraphHistory h = new GraphHistory(graph());
        for (int i = 0; i < 30; i++) h = h.change(h.current.camera(i, i, 1, ViewMode.LOGICAL));
        assertEquals(12, h.undo.size()); assertEquals(30, h.revision); assertEquals(0, h.checkpoint.panX, 0);
        GraphHistory restored = GraphHistory.decode(h.encode()); assertEquals(29, restored.current.panX, 0);
        byte[] bytes = h.encode(); bytes[7] = 99;
        try { GraphHistory.decode(bytes); fail(); } catch (IOException expected) { }
    }
}
