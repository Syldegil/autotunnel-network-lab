package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RootNetworkBackendTest {
    static class Runner implements RootNetworkBackend.Runner {
        final Set<String> chains = new HashSet<>();
        final List<List<String>> calls = new ArrayList<>();
        boolean permissionDenied, collideTable;
        public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
            calls.add(a);
            if (permissionDenied) return new RootNetworkBackend.Result(4, "Permission denied");
            if (tool == NetworkPlan.Tool.IP) return new RootNetworkBackend.Result(0, collideTable && a.contains("route") ? "192.0.2.0/24 dev foreign0" : "");
            String key = tool + a.get(5);
            if (a.contains("-S")) return chains.contains(key) ? new RootNetworkBackend.Result(0, "-N " + a.get(5)) : new RootNetworkBackend.Result(1, "No chain/target/match by that name");
            if (a.contains("-N")) { if (!chains.add(key)) return new RootNetworkBackend.Result(1, "exists"); }
            if (a.contains("-X")) chains.remove(key);
            return new RootNetworkBackend.Result(0, "");
        }
    }
    private RootNetworkBackend backend(Runner runner, boolean enabled) {
        return new RootNetworkBackend(runner, () -> SimulatedNetworkBackend.fixture(100000, true), new RootNetworkBackend.Qualification() {
            public boolean enabled() { return enabled; }
            public boolean authorized(NetworkPlan p, long now) { return true; } // Explicit fixture authority, never production
            public boolean verified(NetworkPlan p, long now) { return false; }
            public void drain(NetworkPlan p) { }
        }, false);
    }
    private NetworkPlan plan() { return new NetfilterCompiler().compile(RoutingProfile.defaults(RoutingProfile.Scope.ALL), SimulatedNetworkBackend.fixture(100000, true)); }
    @Test public void productionHardwareGateIsClosedAndLockedBackendCannotInvokeRunner() throws Exception {
        assertFalse(RootDaemon.HARDWARE_MUTATIONS_QUALIFIED);
        Runner r = new Runner(); RootNetworkBackend b = backend(r, false); NetworkPlan p = plan();
        try { b.preflight(p); fail(); } catch (IllegalStateException expected) { }
        try { b.execute(p.guards.get(0)); fail(); } catch (IllegalStateException expected) { }
        assertTrue(r.calls.isEmpty());
    }
    @Test public void actualBackendRunsCompilerArgvAndTargetedInverseWithFakeProcessRunner() throws Exception {
        Runner r = new Runner(); RootNetworkBackend b = backend(r, true); NetworkPlan p = plan(); b.preflight(p);
        NetworkPlan.Operation create = p.guards.get(0);
        b.execute(create); assertTrue(b.present(create)); b.undo(create); assertFalse(b.present(create));
        assertTrue(r.calls.contains(create.apply)); assertTrue(r.calls.contains(create.undo));
        for (List<String> call : r.calls) { assertFalse(call.contains("-F")); assertFalse(call.contains("flush")); }
    }
    @Test public void permissionErrorIsNotMisreadAsChainAbsent() throws Exception {
        Runner r = new Runner(); r.permissionDenied = true; RootNetworkBackend b = backend(r, true);
        try { b.preflight(plan()); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, r.calls.size());
    }
    @Test public void refusesReservedTableOrChainCollisionWithoutOverwriting() throws Exception {
        Runner r = new Runner(); r.collideTable = true;
        try { backend(r, true).preflight(plan()); fail(); } catch (IllegalStateException expected) { }
        r = new Runner(); NetworkPlan p = plan(); NetworkPlan.Operation op = p.guards.get(0); r.chains.add(op.tool + op.apply.get(5));
        try { backend(r, true).preflight(p); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, r.chains.size());
    }
    @Test public void arbitraryOperationCannotCrossApprovalBoundary() throws Exception {
        Runner r = new Runner(); RootNetworkBackend b = backend(r, true); NetworkPlan p = plan(); b.preflight(p);
        NetworkPlan.Operation forged = new NetworkPlan.Operation("forged", NetworkPlan.Tool.IPTABLES, Arrays.asList("-F"),
            Collections.emptyList(), Collections.emptyList(), NetworkPlan.Check.EXIT_ZERO, Collections.emptyList());
        int reads = r.calls.size();
        try { b.execute(forged); fail(); } catch (IllegalStateException expected) { }
        assertEquals(reads, r.calls.size());
    }
    @Test public void readbackDoesNotGrantPathVerification() throws Exception {
        RootNetworkBackend b = backend(new Runner(), true); NetworkPlan p = plan(); b.preflight(p); b.execute(p.guards.get(0));
        assertTrue(b.present(p.guards.get(0))); assertFalse(b.pathVerified(p, 100000));
    }
    @Test public void capabilityRevocationBlocksOpeningButNotEmergencyClosing() throws Exception {
        Runner r=new Runner();final boolean[] qualified={true};
        RootNetworkBackend b=new RootNetworkBackend(r,()->SimulatedNetworkBackend.fixture(100000,true),new RootNetworkBackend.Qualification(){
            public boolean enabled(){return true;}public boolean authorized(NetworkPlan p,long now){return qualified[0];}
            public boolean verified(NetworkPlan p,long now){return false;}public void drain(NetworkPlan p){}},false);
        NetworkPlan p=plan();b.preflight(p);qualified[0]=false;int calls=r.calls.size();
        try{b.execute(p.open.get(0));fail();}catch(IllegalStateException expected){}assertEquals(calls,r.calls.size());
        b.execute(p.close.get(0));assertEquals(calls+1,r.calls.size());
    }

    @Test public void compositePreflightChecksEveryReservedTableAndPriority() throws Exception {
        NetworkPlan p = new TopologyCompiler().compile(GraphCompiler.compile(GraphTemplates.create(4)).topology,
            SimulatedNetworkBackend.fixture(100000, true));
        Runner clean = new Runner(); backend(clean, true).preflight(p);
        assertTrue(clean.calls.stream().anyMatch(a -> a.contains("table") && a.contains("5014")));
        assertTrue(clean.calls.stream().anyMatch(a -> a.contains("table") && a.contains("5015")));
        for (String table : Arrays.asList("5014", "5015")) {
            Runner collision = new Runner() {
                public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
                    if (tool == NetworkPlan.Tool.IP && a.contains("route") && a.contains(table))
                        return new RootNetworkBackend.Result(0, "default dev foreign0");
                    return super.run(tool, a);
                }
            };
            try { backend(collision, true).preflight(p); fail(table); } catch (IllegalStateException expected) { }
        }
        for (NetworkPlan.Operation op : p.policy) if (op.apply.contains("priority")) {
            String family = op.apply.get(0), priority = op.apply.get(op.apply.indexOf("priority") + 1);
            Runner collision = new Runner() {
                public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
                    if (tool == NetworkPlan.Tool.IP && a.equals(Arrays.asList(family, "rule", "show")))
                        return new RootNetworkBackend.Result(0, priority + ": from all lookup 99");
                    return super.run(tool, a);
                }
            };
            try { backend(collision, true).preflight(p); fail(family + priority); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void unknownRpdbOutputAndUnknownRecoveryOwnershipAreRejected() throws Exception {
        Runner malformed = new Runner() {
            public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
                if (tool == NetworkPlan.Tool.IP && a.contains("rule")) return new RootNetworkBackend.Result(0, "OEM unknown");
                return super.run(tool, a);
            }
        };
        try { backend(malformed, true).preflight(plan()); fail(); } catch (IllegalStateException expected) { }
        Runner r = new Runner(); RootNetworkBackend b = backend(r, true);
        try { b.adoptOwned(plan(), Collections.singleton("foreign-rule")); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(r.calls.isEmpty());
    }

    @Test public void lastOwnedRouteRemovalTreatsMissingFibTableAsAbsentButNotDenied() throws Exception {
        NetworkPlan.Operation route = plan().policy.stream().filter(o -> o.tool == NetworkPlan.Tool.IP && o.apply.contains("route")).findFirst().get();
        Runner missing = new Runner() {
            public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
                return new RootNetworkBackend.Result(2, "Error: ipv4: FIB table does not exist.");
            }
        };
        assertFalse(backend(missing, true).present(route));
        Runner denied = new Runner() {
            public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> a) {
                return new RootNetworkBackend.Result(2, "Permission denied");
            }
        };
        try { backend(denied, true).present(route); fail(); } catch (IllegalStateException expected) { }
    }
}
