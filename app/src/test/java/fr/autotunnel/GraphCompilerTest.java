package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

public class GraphCompilerTest {
    private GraphModel graph() { return GraphModel.initial(RoutingProfile.defaults(RoutingProfile.Scope.PHONE)); }
    private Properties props(RoutingProfile.Family family, Policy policy, boolean block, String source, String input) {
        return new Properties(family, policy, block, source, "", input, "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
    private GraphModel nodeProps(GraphModel g, String id, Properties props) { Node n = g.node(id); return g.replace(new Node(n.id, n.kind, n.label, n.x, n.y, n.enabled, props)); }
    private GraphModel insert(GraphModel g, Kind kind, Properties props) {
        g = g.remove("e2").replace(new Node("insert", kind, kind.title, 400, 200, true, props));
        return g.replace(new Link("a", "n1", Port.OUT_IP, "insert", Port.IN_IP, true, Properties.defaults()))
            .replace(new Link("b", "insert", Port.OUT_IP, "n2", Port.IN_IP, true, Properties.defaults()));
    }
    @Test public void defaultsCompileEveryIndividualPathWithoutInventingDnsOrRoutes() throws Exception {
        for (RoutingProfile.Scope scope : new RoutingProfile.Scope[]{RoutingProfile.Scope.PHONE, RoutingProfile.Scope.WIFI, RoutingProfile.Scope.USB}) {
            GraphModel g = GraphModel.initial(RoutingProfile.defaults(scope)); GraphCompiler.Result result = GraphCompiler.compile(g);
            assertTrue(result.report(), result.valid()); assertEquals(scope, result.profile.scope); assertTrue(result.profile.dns.isEmpty()); assertTrue(result.profile.routes4.isEmpty());
            SimulatedNetworkBackend backend = new SimulatedNetworkBackend(1000); NetworkEngine engine = new NetworkEngine(backend, new NetworkEngine.MemoryJournal());
            NetworkPlan plan = GraphCompiler.preview(g, engine); assertFalse(plan.guards.isEmpty()); assertEquals(0, backend.mutationCount);
            assertEquals(NetworkEngine.State.VPN_PROTECTED, engine.apply(plan, plan.digest, 1000));
            assertEquals(NetworkEngine.State.VPN_BLOCKED, engine.event(NetworkEngine.Event.TUNNEL_LOST, 1001)); assertTrue(backend.closed());
            assertFalse(GraphCompiler.HARDWARE_ACTIVATION_ENABLED);
        }
    }
    @Test public void directProfileCompilesOnlyAfterTopologyIsExplicitlyConsistent() {
        RoutingProfile direct = new RoutingProfile(RoutingProfile.Scope.PHONE, RoutingProfile.Mode.DIRECT, RoutingProfile.V6.BLOCK,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertFalse(GraphCompiler.compile(graph().profile(direct)).valid());
        assertTrue(GraphCompiler.compile(GraphModel.initial(direct)).valid());
        RoutingProfile directRoutes = new RoutingProfile(direct.scope, direct.mode, direct.ipv6, Collections.singletonList("0.0.0.0/0"), direct.routes6, direct.dns, direct.rules);
        assertFalse(GraphCompiler.compile(GraphModel.initial(directRoutes)).valid());
    }
    @Test public void rejectsEveryUnsupportedActiveRoleInsteadOfIgnoringIt() {
        for (Kind kind : new Kind[]{Kind.NAT4, Kind.INTERFACE, Kind.PHONE_INTERFACE, Kind.FREEBOX})
            assertFalse(kind.name(), GraphCompiler.compile(insert(graph(), kind, Properties.defaults())).valid());
        GraphModel g = graph().replace(new Node("monitor", Kind.RATE, "Rate", 0, 0, true, Properties.defaults()));
        assertFalse(GraphCompiler.compile(g).valid());
        Node m = g.node("monitor"); g = g.replace(new Node(m.id, m.kind, m.label, 0, 0, false, m.properties));
        assertTrue(GraphCompiler.compile(g).valid());
    }
    @Test public void activePhysicalOrMonitorLinksAreNotSilentlyDropped() {
        GraphModel g = graph(); Link outer = g.link("transport");
        g = g.replace(new Link(outer.id, outer.from, outer.output, outer.to, outer.input, true, outer.properties));
        assertFalse(GraphCompiler.compile(g).valid());
        GraphModel monitor = graph().replace(new Node("observe", Kind.INSPECTOR, "Observer", 0, 0, false, Properties.defaults()))
            .replace(new Link("observeLink", "n2", Port.MONITOR_OUT, "observe", Port.MONITOR_IN, true, Properties.defaults()));
        assertFalse(GraphCompiler.compile(monitor).valid());
    }
    @Test public void rejectsContradictoryOrUnrepresentableNodeAndLinkProperties() {
        for (Properties p : Arrays.asList(props(RoutingProfile.Family.IPV6, Policy.INHERIT, true, "", ""),
            props(null, Policy.DIRECT, true, "", ""),
            props(null, Policy.INHERIT, false, "", ""), props(RoutingProfile.Family.IPV4, Policy.INHERIT, true, "192.0.2.0/24", ""),
            props(null, Policy.INHERIT, true, "", "wlan0"))) {
            GraphModel g = graph(); assertFalse(GraphCompiler.compile(nodeProps(g, "n1", p)).valid());
            Link link = g.link("e1"); assertFalse(GraphCompiler.compile(g.replace(new Link(link.id, link.from, link.output, link.to, link.input, true, p))).valid());
        }
    }
    @Test public void acceptsMatchingExplicitPoliciesAndCompilesBothFamilies() throws Exception {
        RoutingProfile p = new RoutingProfile(RoutingProfile.Scope.PHONE, RoutingProfile.Mode.REQUIRE_TUNNEL, RoutingProfile.V6.REQUIRE_TUNNEL,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        GraphModel g = nodeProps(graph().profile(p), "n2", props(RoutingProfile.Family.BOTH, Policy.REQUIRE_TUNNEL, true, "", ""));
        GraphCompiler.Result result = GraphCompiler.compile(g); assertTrue(result.report(), result.valid());
        assertEquals(RoutePolicy.Intent.BLOCK_REQUIRED, RoutePolicy.intent(result.profile, RoutingProfile.Family.IPV6, null, 1000));
        assertNotNull(GraphCompiler.preview(g, new NetworkEngine(new SimulatedNetworkBackend(1000), new NetworkEngine.MemoryJournal())));
    }
    @Test public void disconnectedBranchingMissingTunnelOrWrongScopeNeverCompiles() {
        assertFalse(GraphCompiler.compile(graph().remove("e2")).valid()); assertFalse(GraphCompiler.compile(graph().remove("n2")).valid());
        GraphModel branch = graph().replace(new Node("sink", Kind.INTERNET, "Second sink", 0, 0, true, Properties.defaults()))
            .replace(new Link("branch", "n1", Port.OUT_IP, "sink", Port.IN_IP, true, Properties.defaults()));
        assertFalse(GraphCompiler.compile(branch).valid());
        assertFalse(GraphCompiler.compile(graph().profile(RoutingProfile.defaults(RoutingProfile.Scope.WIFI))).valid());
        assertTrue(GraphCompiler.compile(GraphModel.initial(RoutingProfile.defaults(RoutingProfile.Scope.ALL))).valid());
    }
    @Test public void dnsAndRoutesHaveOneOwnerAndArePreservedExactly() throws Exception {
        Properties dns = new Properties(null, Policy.INHERIT, true, "", "", "", "", Collections.singletonList("192.0.2.53"), Collections.emptyList(), Collections.emptyList());
        GraphModel g = insert(graph(), Kind.DNS, dns); GraphCompiler.Result result = GraphCompiler.compile(g);
        assertTrue(result.report(), result.valid()); assertEquals(Collections.singletonList("192.0.2.53"), result.profile.dns);
        assertNotNull(GraphCompiler.preview(g, new NetworkEngine(new SimulatedNetworkBackend(1000), new NetworkEngine.MemoryJournal())));
        assertFalse(GraphCompiler.compile(g.profile(result.profile)).valid()); // duplicate owner even if equal
        assertFalse(GraphCompiler.compile(insert(graph(), Kind.ROUTER, dns)).valid());
        Properties routes = new Properties(null, Policy.INHERIT, true, "", "", "", "", Collections.emptyList(), Collections.singletonList("192.0.2.0/24"), Collections.emptyList());
        assertEquals(Collections.singletonList("192.0.2.0/24"), GraphCompiler.compile(insert(graph(), Kind.ROUTER, routes)).profile.routes4);
        Link link = graph().link("e1"); assertFalse(GraphCompiler.compile(graph().replace(new Link(link.id, link.from, link.output, link.to, link.input, true, dns))).valid());
    }
    @Test public void rulesArePreservedAndRequireAnUnambiguousFirewall() {
        RoutingProfile.Rule rule = new RoutingProfile.Rule(RoutingProfile.Direction.OUT, RoutingProfile.Protocol.TCP, RoutingProfile.Family.IPV4,
            RoutingProfile.Path.PHONE, RoutingProfile.Action.BLOCK, 80, 8080, "192.0.2.0/24", "", 10, true, "", "simvpn0");
        RoutingProfile p = new RoutingProfile(RoutingProfile.Scope.PHONE, RoutingProfile.Mode.REQUIRE_TUNNEL, RoutingProfile.V6.BLOCK,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.singletonList(rule));
        GraphModel g = graph().profile(p); assertSame(rule, GraphCompiler.compile(g).profile.rules.get(0));
        assertFalse(GraphCompiler.compile(insert(g, Kind.FIREWALL, Properties.defaults())).valid());
        Node fw = g.node("n1"); g = g.replace(new Node(fw.id, Kind.ROUTER, "Router", fw.x, fw.y, true, fw.properties));
        assertFalse(GraphCompiler.compile(g).valid());
    }
    @Test public void serverSideProcessingAfterWgIsNotCompiledAsPhoneRules() {
        GraphModel g = graph().remove("e3").replace(new Node("remoteFw", Kind.FIREWALL, "Remote filter", 0, 0, true, Properties.defaults()));
        g = g.replace(new Link("after", "n2", Port.OUT_IP, "remoteFw", Port.IN_IP, true, Properties.defaults()))
            .replace(new Link("sink", "remoteFw", Port.OUT_IP, "n3", Port.IN_IP, true, Properties.defaults()));
        assertFalse(GraphCompiler.compile(g).valid());
    }
    @Test public void graphPreviewRefusesBeforeEngineCanObserveOrMutateUnsupportedGraph() throws Exception {
        SimulatedNetworkBackend backend = new SimulatedNetworkBackend(1000);
        try { GraphCompiler.preview(graph().remove("e2"), new NetworkEngine(backend, new NetworkEngine.MemoryJournal())); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("refusée")); }
        assertEquals(0, backend.mutationCount); assertEquals(0, backend.ownedCount());
    }
}
