package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;
import static io.github.syldegil.autotunnel.FilterSimulator.*;

public class NetfilterCompilerTest {
    private RoutingProfile profile(Mode mode, V6 v6, List<Rule> rules) {
        return new RoutingProfile(Scope.ALL, mode, v6, entries(""), entries(""), entries(""), rules);
    }
    private NetworkPlan compile(RoutingProfile p) { return new NetfilterCompiler().compile(p, SimulatedNetworkBackend.fixture(100000, true)); }
    private Packet client(String out, int family) {
        return new Packet(family, "simusb0", out, family == 4 ? "198.18.2.9" : "2001:db8:4::9",
            family == 4 ? "8.8.8.8" : "2001:4860:4860::8888", "tcp", 443, 45000, 0, false);
    }
    @Test public void mandatoryIpv4AllowsOnlyTunnelAndRejectsDirectInBothDirections() {
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList())), true);
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simup0", 4)));
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("FORWARD", client("simvpn0", 4)));
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", new Packet(4, "simup0", "simusb0", "8.8.8.8", "198.18.2.9", "tcp", 45000, 443, 0, true)));
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("FORWARD", new Packet(4, "simvpn0", "simusb0", "8.8.8.8", "198.18.2.9", "tcp", 45000, 443, 0, true)));
    }
    @Test public void closedGuardBlocksEvenTunnelTrafficButKeepsOnlyOuterTransportException() {
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList())), false);
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simvpn0", 4)));
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("OUTPUT", new Packet(4, "", "simup0", "192.0.2.1", "203.0.113.7", "udp", 51820, 45000, 10123, false)));
        assertEquals(Verdict.DROP, f.evaluate("OUTPUT", new Packet(4, "", "simup0", "192.0.2.1", "203.0.113.7", "udp", 51820, 45000, 10124, false)));
    }
    @Test public void allowRuleCannotOverrideIpv6BlockOrDirectBarrier() {
        Rule allow = new Rule(Direction.FORWARD, Protocol.TCP, Family.BOTH, Path.USB, Action.ALLOW, 1, 65535, "", "", 1, true);
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.singletonList(allow))), true);
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simup0", 4)));
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simvpn0", 6)));
    }
    @Test public void directFirewallWorksIndependentOfWireguard() {
        Rule block = FirewallTemplates.blockPort(Path.USB, Protocol.TCP, 400, 500, 10);
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.DIRECT, V6.REQUIRE_TUNNEL, Collections.singletonList(block))), true);
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simup0", 4)));
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simup0", 6)));
        assertTrue(f.matches() > 0);
    }
    @Test public void priorityAndDisabledRulesAreRespected() {
        Rule allow = new Rule(Direction.OUT, Protocol.TCP, Family.BOTH, Path.USB, Action.ALLOW, 443, 443, "", "", 1, true);
        Rule block = FirewallTemplates.blockPort(Path.USB, Protocol.TCP, 443, 443, 20);
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.DIRECT, V6.REQUIRE_TUNNEL, Arrays.asList(block, allow))), true);
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("FORWARD", client("simup0", 4)));
        Rule disabled = new Rule(Direction.OUT, Protocol.TCP, Family.BOTH, Path.USB, Action.BLOCK, 443, 443, "", "", 1, false);
        f = new FilterSimulator(compile(profile(Mode.DIRECT, V6.REQUIRE_TUNNEL, Collections.singletonList(disabled))), true);
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("FORWARD", client("simup0", 6)));
    }
    @Test public void includesTerminalRpdbAndBoundedSnatDnsNeverNat66() {
        NetworkPlan p = compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList()));
        String cmds = p.preview();
        assertTrue(cmds.contains("unreachable default")); assertTrue(cmds.contains("iif simusb0 prohibit"));
        assertTrue(cmds.contains("-s 198.18.2.0/24 -o simvpn0")); assertTrue(cmds.contains("--src-type LOCAL"));
        assertTrue(cmds.contains("--to-destination 192.0.2.53")); assertTrue(cmds.contains("iif simvpn0 to 198.18.2.0/24"));
        for (NetworkPlan.Operation op : p.policy) {
            assertFalse(op.apply.contains("flush")); assertFalse(op.apply.contains("-F"));
            assertFalse(op.tool == NetworkPlan.Tool.IP6TABLES && op.apply.contains("nat"));
        }
    }
    @Test public void dualStackRoutesRequireRealFamilyAddressAndCannotEscapeAllowedIps() {
        RoutingProfile p = profile(Mode.REQUIRE_TUNNEL, V6.REQUIRE_TUNNEL, Collections.emptyList());
        try { new NetfilterCompiler().compile(p, SimulatedNetworkBackend.fixture(100000, false)); fail(); }
        catch (IllegalArgumentException expected) { }
        assertTrue(compile(p).policy.stream().anyMatch(op -> op.tool == NetworkPlan.Tool.IP && op.apply.get(0).equals("-6")));
    }
    @Test public void icmpFamiliesAndInterfaceSelectorsAreValidated() {
        new Rule(Direction.FORWARD, Protocol.ICMPV6, Family.IPV6, Path.USB, Action.BLOCK, 0, 0, "", "", 1, true, "simusb0", "simvpn0");
        for (String iface : Arrays.asList("wlan0;id", "$(id)", "x y", "-F", "abcdefghijklmnop")) {
            try { new Rule(Direction.FORWARD, Protocol.TCP, Family.IPV4, Path.USB, Action.BLOCK, 53, 53, "", "", 1, true, iface, ""); fail(iface); }
            catch (IllegalArgumentException expected) { }
        }
        try { new Rule(Direction.OUT, Protocol.ICMP, Family.BOTH, Path.PHONE, Action.BLOCK, 0, 0, "", "", 1, true); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void noHostRoutesOrMainDefaultAreOverwrittenAndInverseIsExact() {
        for (NetworkPlan.Operation op : compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList())).policy) {
            if (op.tool == NetworkPlan.Tool.IP) {
                assertEquals("del", op.undo.get(2)); assertFalse(op.apply.contains("replace"));
                if (op.apply.contains("route")) assertEquals("5013", op.apply.get(op.apply.size() - 1));
            }
        }
    }
    @Test public void ruleReadbackRejectsWrongSelectorAtSamePriority() {
        NetworkPlan.Operation op = compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList())).policy.stream()
            .filter(o -> o.check == NetworkPlan.Check.RULE && o.apply.contains("simusb0") && o.apply.contains("lookup")).findFirst().get();
        String priority = op.apply.get(op.apply.indexOf("priority") + 1);
        assertFalse(op.matches(0, priority + ": from all iif simwifi0 lookup 5013"));
        assertFalse(op.matches(0, priority + ": from all iif simusb0 lookup 254"));
        assertFalse(op.matches(0, priority + ": from all iif simusb0 lookup 5013"));
        assertTrue(op.matches(0, priority + ": from 198.18.2.0/24 iif simusb0 lookup 5013"));
        assertFalse(op.matches(0, priority + ": from 198.18.2.0/24 iif simusb0 fwmark 0x1 lookup 5013"));
        assertFalse(op.matches(0, priority + ": from 198.18.2.0/24 iif simusb0 lookup 5013 suppress_prefixlength 0"));
        assertFalse(op.matches(0, priority + ": from 198.18.2.0/24 iif simusb0 lookup 5013\n"
            + priority + ": from all lookup 254"));
    }
    @Test public void routeReadbackRejectsChangedOwnershipAttributes() {
        NetworkPlan p = compile(profile(Mode.REQUIRE_TUNNEL, V6.REQUIRE_TUNNEL, Collections.emptyList()));
        NetworkPlan.Operation route = p.policy.stream().filter(o -> o.check == NetworkPlan.Check.ROUTE
            && o.apply.get(0).equals("-4") && o.apply.get(3).equals("0.0.0.0/0")).findFirst().get();
        assertTrue(route.matches(0, "default dev simvpn0 scope link metric 10"));
        assertTrue(route.matches(0, "default dev simvpn0 proto boot scope link metric 10"));
        for (String suffix : Arrays.asList("via 192.0.2.9", "src 192.0.2.99", "mtu 1280", "onlink", "metric 11", "scope global", "proto static", "unknown flag"))
            assertFalse(suffix, route.matches(0, "default dev simvpn0 metric 10 " + suffix));
        assertFalse(route.matches(0, "local default dev simvpn0 metric 10"));
        assertFalse(route.matches(0, "default dev foreign0 metric 10"));
        assertFalse(route.matches(0, "default dev simvpn0 metric 11"));
    }
    @Test public void routeReadbackAcceptsOnlyObservedIpv6KernelDefaults() {
        NetworkPlan p = compile(profile(Mode.REQUIRE_TUNNEL, V6.REQUIRE_TUNNEL, Collections.emptyList()));
        NetworkPlan.Operation route = p.policy.stream().filter(o -> o.check == NetworkPlan.Check.ROUTE
            && o.apply.get(0).equals("-6") && o.apply.contains("simvpn0")).findFirst().get();
        assertTrue(route.matches(0, "default dev simvpn0 metric 10 pref medium"));
        assertFalse(route.matches(0, "default dev simvpn0 metric 10 pref high"));
        assertFalse(route.matches(0, "default via 2001:db8::9 dev simvpn0 metric 10 pref medium"));
        NetworkPlan.Operation barrier = p.policy.stream().filter(o -> o.check == NetworkPlan.Check.ROUTE
            && o.apply.get(0).equals("-6") && o.apply.contains("unreachable")).findFirst().get();
        assertTrue(barrier.matches(0, "unreachable default dev lo metric 32760 pref medium"));
        assertTrue(barrier.matches(0, "unreachable default metric 32760"));
        assertFalse(barrier.matches(0, "unreachable default dev simvpn0 metric 32760 pref medium"));
        assertFalse(barrier.matches(0, "default dev lo metric 32760 pref medium"));
    }
    @Test public void digestBindsTopologyAndRedactsEndpointFromPreview() {
        NetworkPlan p = compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList()));
        assertFalse(p.preview().contains("203.0.113.7")); assertTrue(p.preview().contains("<endpoint-WG>"));
        NetworkSnapshot s = NetworkEngineTest.copy(p.snapshot, "different-generation", p.snapshot.capabilities, true);
        assertNotEquals(p.digest, new NetfilterCompiler().compile(p.profile, s).digest);
    }
    @Test public void ipv6NeighborControlIsScopedAndCannotPermitDataLeak() {
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.REQUIRE_TUNNEL, V6.REQUIRE_TUNNEL, Collections.emptyList())), true);
        assertEquals(Verdict.RETURN_TO_ANDROID, f.evaluate("INPUT", new Packet(6, "simusb0", "", "fe80::9", "ff02::2", "ipv6-icmp", 0, 0, 0, false, 133, 255)));
        assertEquals(Verdict.DROP, f.evaluate("INPUT", new Packet(6, "simusb0", "", "fe80::9", "ff02::2", "ipv6-icmp", 0, 0, 0, false, 133, 64)));
        assertEquals(Verdict.DROP, f.evaluate("FORWARD", client("simup0", 6)));
    }
    @Test public void selectedClientsCannotUseLocalProxyToBypassTunnel() {
        FilterSimulator f = new FilterSimulator(compile(profile(Mode.REQUIRE_TUNNEL, V6.BLOCK, Collections.emptyList())), true);
        assertEquals(Verdict.DROP, f.evaluate("INPUT", new Packet(4, "simusb0", "", "198.18.2.9", "198.18.2.1", "tcp", 8080, 45000, 0, false)));
    }
    @Test public void singleAddressesBecomeHostSelectorsWithoutDnsLookup() {
        Rule r = new Rule(Direction.OUT, Protocol.UDP, Family.IPV4, Path.PHONE, Action.ALLOW, 53, 53, "", "192.0.2.53", 1, true);
        assertEquals("192.0.2.53/32", r.destination);
    }
    @Test public void policyReadbackRequiresOrderNotJustPresence() {
        NetworkPlan p = compile(profile(Mode.DIRECT, V6.REQUIRE_TUNNEL,
            Collections.singletonList(FirewallTemplates.blockPort(Path.USB, Protocol.TCP, 443, 443, 1))));
        NetworkPlan.Operation op = p.policy.stream().filter(o -> o.apply.contains("--dport") && o.tool == NetworkPlan.Tool.IPTABLES).findFirst().get();
        String chain = op.apply.get(5);
        String rule = "-A " + chain + " -i simusb0 -p tcp -m tcp --dport 443 -j DROP";
        assertTrue(op.matches(0, "-N " + chain + "\n" + rule));
        assertFalse(op.matches(0, "-N " + chain + "\n-A " + chain + " -j RETURN\n" + rule));
    }

    @Test public void readbackAcceptsKernelSelectorOrderingButNotChangedSelectors() {
        NetworkPlan p = new NetfilterCompiler().compile(RoutingProfile.defaults(Scope.PHONE), SimulatedNetworkBackend.fixture(1000,true));
        NetworkPlan.Operation op = p.guards.stream().filter(o -> o.apply.contains("--sport") && o.tool == NetworkPlan.Tool.IPTABLES).findFirst().get();
        String chain = op.apply.get(5);
        String prefix = "-N " + chain + "\n-A " + chain + " -i lo -j RETURN\n";
        String actual = "-A " + chain + " -s 203.0.113.7/32 -p udp -m udp --sport 51820 -m conntrack --ctstate ESTABLISHED -j RETURN";
        assertTrue(op.matches(0, prefix + actual));
        assertFalse(op.matches(0, prefix + actual.replace("-s 203", "! -s 203")));
        assertFalse(op.matches(0, prefix + actual.replace("203.0.113.7/32", "203.0.113.8/32")));
        assertFalse(op.matches(0, prefix + actual.replace("-j RETURN", "-j ACCEPT")));
        assertFalse(op.matches(0, prefix + actual + " -i foreign0"));
    }

    @Test public void readbackAcceptsOmittedUniversalPrefixButNotNarrowOrNegatedPrefix() {
        NetworkPlan p = new NetfilterCompiler().compile(RoutingProfile.defaults(Scope.PHONE), SimulatedNetworkBackend.fixture(1000,true));
        NetworkPlan.Operation op = p.policy.stream().filter(o -> o.apply.contains("-s") && o.apply.contains("0.0.0.0/0")).findFirst().get();
        String chain = op.apply.get(5), actual = "-A " + chain + " -i simvpn0 -g AT13_U4I";
        assertTrue(op.matches(0, actual));
        assertFalse(op.matches(0, actual + " -s 192.0.2.0/24"));
        assertFalse(op.matches(0, actual + " ! -s 0.0.0.0/0"));
    }
}
