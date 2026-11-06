package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;

public class RoutingProfileTest {
    private RoutingProfile profile(V6 v6, List<String> v4, List<String> v6routes, List<String> dns, List<Rule> rules) {
        return new RoutingProfile(Scope.PHONE, Mode.REQUIRE_TUNNEL, v6, v4, v6routes, dns, rules);
    }
    private Rule rule(Family family, String source, int first, int last, int priority, Path path) {
        return new Rule(Direction.OUT, Protocol.UDP, family, path, Action.BLOCK,
            first, last, source, "", priority, true);
    }
    private void rejects(Runnable call) {
        try { call.run(); fail("Invalid input accepted"); } catch (IllegalArgumentException expected) { }
    }
    @Test public void defaultsNeverInventIpv6OrDnsOrRoutes() {
        for (Scope scope : Scope.values()) {
            RoutingProfile p = defaults(scope);
            assertEquals(Mode.REQUIRE_TUNNEL, p.mode); assertEquals(V6.BLOCK, p.ipv6);
            assertTrue(p.dns.isEmpty()); assertTrue(p.routes4.isEmpty()); assertTrue(p.routes6.isEmpty());
        }
    }
    @Test public void dualStackLiteralRoutesAcceptedAsIntentOnly() {
        RoutingProfile p = profile(V6.REQUIRE_TUNNEL, entries("0.0.0.0/0,192.0.2.0/24"),
            entries("::/0 2001:db8::/32"), entries("192.0.2.53,2001:db8::53"), Collections.emptyList());
        assertEquals(2, p.routes6.size()); assertTrue(RoutePolicy.plan(p).contains("non appliqué"));
        assertTrue(RoutePolicy.activationRefusal(p).contains("indisponible"));
    }
    @Test public void blockIpv6RejectsFakeIpv6Route() {
        rejects(() -> profile(V6.BLOCK, entries(""), entries("::/0"), entries(""), Collections.emptyList()));
    }
    @Test public void rejectsFamilyMismatchAndInvalidPrefix() {
        for (String cidr : new String[]{"::/0", "192.0.2.0/33", "192.0.2.0/-1", "192.0.2.0", "1.2.3.4/00"})
            rejects(() -> cidr(cidr, Family.IPV4));
        rejects(() -> cidr("2001:db8::/129", Family.IPV6));
    }
    @Test public void rejectsHostnamesZoneIdsCommandsAndAmbiguousIpv4() {
        for (String value : new String[]{"example.com", "localhost", "1.2.3.4;id", "$(id)", "1.2.3", "01.2.3.4", "256.1.1.1", "fe80::1%wlan0", "::gg", "1::2::3"})
            rejects(() -> address(value));
    }
    @Test public void dnsIsPreservedAndNeverReplacedWithFallback() {
        RoutingProfile p = profile(V6.BLOCK, entries(""), entries(""), entries("192.0.2.53"), Collections.emptyList());
        assertEquals(Collections.singletonList("192.0.2.53"), p.dns);
        rejects(() -> profile(V6.BLOCK, entries(""), entries(""), entries("dns.example.com"), Collections.emptyList()));
    }
    @Test public void validatesPortRangesAndPriority() {
        rule(Family.BOTH, "", 1, 65535, 1, Path.PHONE);
        rejects(() -> rule(Family.IPV4, "", 0, 1, 1, Path.PHONE));
        rejects(() -> rule(Family.IPV4, "", 100, 90, 1, Path.PHONE));
        rejects(() -> rule(Family.IPV6, "", 53, 65536, 1, Path.PHONE));
        rejects(() -> rule(Family.IPV4, "", 53, 53, 0, Path.PHONE));
    }
    @Test public void bothFamiliesCannotSilentlyUseIpv4OnlySelector() {
        rejects(() -> rule(Family.BOTH, "192.0.2.0/24", 53, 53, 1, Path.PHONE));
        rejects(() -> rule(Family.IPV6, "192.0.2.0/24", 53, 53, 1, Path.PHONE));
        rule(Family.IPV6, "2001:db8::/32", 53, 53, 1, Path.PHONE);
    }
    @Test public void rejectsDuplicatePriorityAndOutOfScopeRules() {
        Rule a = rule(Family.IPV4, "", 53, 53, 1, Path.PHONE);
        rejects(() -> profile(V6.BLOCK, entries(""), entries(""), entries(""), java.util.Arrays.asList(a, a)));
        rejects(() -> profile(V6.BLOCK, entries(""), entries(""), entries(""), Collections.singletonList(rule(Family.IPV4, "", 53, 53, 1, Path.USB))));
    }
    @Test public void profileIsAnImmutableSnapshot() {
        List<String> routes = new ArrayList<>(entries("192.0.2.0/24"));
        RoutingProfile p = profile(V6.BLOCK, routes, entries(""), entries(""), Collections.emptyList());
        routes.clear(); assertEquals(1, p.routes4.size());
        try { p.routes4.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void excessiveInputRejected() {
        rejects(() -> profile(V6.BLOCK, Collections.nCopies(129, "0.0.0.0/0"), entries(""), entries(""), Collections.emptyList()));
    }
}
