package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;
import static io.github.syldegil.autotunnel.NetworkEngine.*;

public class NetworkEngineTest {
    private final long now = 100000;
    private RoutingProfile profile(Scope scope, Mode mode) {
        return new RoutingProfile(scope, mode, V6.BLOCK, entries(""), entries(""), entries(""), Collections.emptyList());
    }
    private NetworkPlan plan(SimulatedNetworkBackend b, Scope scope, Mode mode) { return new NetfilterCompiler().compile(profile(scope, mode), b.snapshot); }
    private NetworkEngine engine(SimulatedNetworkBackend b) { return new NetworkEngine(b, new MemoryJournal()); }
    @Test public void mandatoryModeNeverFallsBackOnAnyFailureEvent() {
        for (Event event : Event.values()) {
            if (event == Event.RECOVER) continue;
            SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
            NetworkPlan p = plan(b, Scope.ALL, Mode.REQUIRE_TUNNEL);
            assertEquals(State.VPN_PROTECTED, e.apply(p, p.digest, now));
            assertEquals(State.VPN_BLOCKED, e.event(event, now)); assertTrue(b.closed());
            assertEquals(3, b.foreignObjects.size());
        }
    }
    @Test public void failureAtEveryCommandNeverReportsProtectedOrDirect() {
        SimulatedNetworkBackend reference = new SimulatedNetworkBackend(now);
        NetworkPlan full = plan(reference, Scope.ALL, Mode.REQUIRE_TUNNEL);
        int count = full.guards.size() + full.policy.size() + full.open.size();
        for (int i = 1; i <= count; i++) {
            SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); b.failAtMutation = i;
            NetworkEngine e = engine(b); NetworkPlan p = plan(b, Scope.ALL, Mode.REQUIRE_TUNNEL);
            State state = e.apply(p, p.digest, now);
            assertTrue("failure " + i + " => " + state, state == State.ERROR || state == State.VPN_BLOCKED);
            assertEquals(3, b.foreignObjects.size());
            assertEquals("explicit recovery after failure " + i, State.DIRECT, e.restore(true));
            assertEquals(0, b.ownedCount());
        }
    }
    @Test public void rulesPresentWithoutPathProofRemainClosed() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); b.proof = false;
        NetworkEngine e = engine(b); NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL);
        assertEquals(State.VPN_BLOCKED, e.apply(p, p.digest, now));
        b.proof = true; assertEquals(State.VPN_PROTECTED, e.event(Event.RECOVER, now));
    }
    @Test public void staleEvidenceAndUnconfirmedPlanCannotMutate() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.PHONE, Mode.REQUIRE_TUNNEL);
        assertEquals(State.UNSUPPORTED, e.apply(p, "not-confirmed", now)); assertEquals(0, b.mutationCount);
        assertEquals(State.UNSUPPORTED, e.apply(p, p.digest, now + 16000)); assertEquals(0, b.mutationCount);
    }
    @Test public void missingOffloadProofRefusesBeforeFirstMutation() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now);
        Set<NetworkSnapshot.Capability> caps = new HashSet<>(b.snapshot.capabilities); caps.remove(NetworkSnapshot.Capability.OFFLOAD_USB);
        b.snapshot = copy(b.snapshot, "sim-generation1", caps, true);
        NetworkEngine e = engine(b); NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL);
        assertEquals(State.UNSUPPORTED, e.apply(p, p.digest, now)); assertEquals(0, b.mutationCount);
    }
    @Test public void topologyChangeCannotReuseProofOrBeCalledBlockedOnNewInterface() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.WIFI, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        b.snapshot = copy(b.snapshot, "new-generation", b.snapshot.capabilities, true);
        assertEquals(State.ERROR, e.event(Event.TETHER_CHANGED, now)); assertTrue(b.closed());
        assertNotEquals(State.VPN_PROTECTED, e.event(Event.RECOVER, now));
    }
    @Test public void journalFailureBlocksBeforeAnyDataPlaneMutation() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now);
        NetworkEngine e = new NetworkEngine(b, (phase, p, ids) -> { throw new java.io.IOException(); });
        NetworkPlan p = plan(b, Scope.PHONE, Mode.REQUIRE_TUNNEL);
        assertEquals(State.ERROR, e.apply(p, p.digest, now)); assertEquals(0, b.ownedCount());
    }
    @Test public void explicitRestoreOnlyTouchesOwnedObjectsAndKeepsProfile() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.ALL, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        assertEquals(State.VPN_PROTECTED, e.restore(false));
        assertEquals(State.DIRECT, e.restore(true)); assertEquals(0, b.ownedCount()); assertEquals(3, b.foreignObjects.size());
        assertEquals(Mode.REQUIRE_TUNNEL, p.profile.mode);
    }
    @Test public void directModeRetainsFirewallUntilExplicitProfileRemoval() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.ALL, Mode.DIRECT);
        assertEquals(State.DIRECT, e.apply(p, p.digest, now)); assertTrue(b.ownedCount() > 0);
        assertTrue(p.policy.stream().noneMatch(op -> op.tool == NetworkPlan.Tool.IP));
    }
    @Test public void failedCloseOrReadbackNeverClaimsBlockedSuccessfully() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.PHONE, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now); b.failClose = true;
        assertEquals(State.ERROR, e.event(Event.SERVICE_DIED, now));
        b.failClose = false; b.refuseReadback = true; assertEquals(State.ERROR, e.event(Event.TUNNEL_LOST, now));
    }
    @Test public void missingPolicyRulePreventsRecovery() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.PHONE, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        b.removeForTest(p.policy.get(p.policy.size() - 1));
        assertEquals(State.VPN_BLOCKED, e.event(Event.RECOVER, now));
    }
    @Test public void daemonRestartDoesNotOpenAndCanRestoreOwnedCheckpoint() throws Exception {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine old = engine(b);
        NetworkPlan p = plan(b, Scope.ALL, Mode.REQUIRE_TUNNEL); old.apply(p, p.digest, now);
        Set<String> ids = new HashSet<>(old.operationIds()); NetworkEngine restarted = engine(b);
        restarted.resumeOwned(p, ids); assertEquals(State.ERROR, restarted.state());
        assertEquals(State.DIRECT, restarted.restore(true)); assertEquals(0, b.ownedCount());
    }
    @Test public void failedUndoIsNotReportedAsRestored() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.ALL, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now); b.failUndo = true;
        assertEquals(State.ERROR, e.restore(true)); assertTrue(b.ownedCount() > 0);
    }
    @Test public void aDeletedHookCannotBeReportedAsSuccessfullyBlocked() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        b.removeForTest(p.guards.stream().filter(o -> o.apply.contains("-I")).findFirst().get());
        assertEquals(State.ERROR, e.event(Event.TUNNEL_LOST, now));
    }
    @Test public void branchReportDoesNotMistakeOpenRulesForCurrentProtection() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        assertEquals(State.VPN_PROTECTED, e.branchStates(now).get(Scope.USB));
        b.proof = false;
        assertEquals(State.ERROR, e.branchStates(now).get(Scope.USB));
        b.proof = true;
        assertEquals(State.ERROR, e.branchStates(now + 16000).get(Scope.USB));
        b.removeForTest(p.policy.get(p.policy.size() - 1));
        assertEquals(State.ERROR, e.branchStates(now).get(Scope.USB));
    }
    @Test public void branchReportRejectsReusedInterfaceAndMissingGuardHook() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL); e.apply(p, p.digest, now);
        e.event(Event.TUNNEL_LOST, now);
        assertEquals(State.VPN_BLOCKED, e.branchStates(now).get(Scope.USB));
        b.removeForTest(p.guards.stream().filter(o -> o.apply.contains("-I")).findFirst().get());
        assertEquals(State.ERROR, e.branchStates(now).get(Scope.USB));
        b.snapshot = copy(b.snapshot, "new-interface", b.snapshot.capabilities, true);
        assertEquals(State.ERROR, e.branchStates(now).get(Scope.USB));
    }
    @Test public void openGateReplacesDropWithoutRemovingItsStaticGuards() {
        SimulatedNetworkBackend b = new SimulatedNetworkBackend(now); NetworkEngine e = engine(b);
        NetworkPlan p = plan(b, Scope.USB, Mode.REQUIRE_TUNNEL);
        assertEquals(State.VPN_PROTECTED, e.apply(p, p.digest, now));
        NetworkPlan.Operation gate = p.open.get(0);
        NetworkPlan.Operation initialDrop = p.guards.stream().filter(op -> op.tool == gate.tool
            && op.position == gate.position && op.inspect.equals(gate.inspect)).findFirst().get();
        assertFalse("The open slot cannot simultaneously contain its old DROP rule", b.present(initialDrop));
        assertTrue(b.present(gate));
        assertEquals(State.VPN_PROTECTED, e.branchStates(now).get(Scope.USB));
        assertEquals(State.VPN_BLOCKED, e.event(Event.TUNNEL_LOST, now));
        assertTrue(b.present(initialDrop));
        assertFalse(b.present(gate));
        assertEquals(State.VPN_BLOCKED, e.branchStates(now).get(Scope.USB));
        assertEquals(State.VPN_PROTECTED, e.event(Event.RECOVER, now));
        assertEquals(State.VPN_PROTECTED, e.branchStates(now).get(Scope.USB));
    }
    static NetworkSnapshot copy(NetworkSnapshot s, String generation, Set<NetworkSnapshot.Capability> caps, boolean up) {
        return new NetworkSnapshot(s.boot, generation, s.sampledAt, s.simulated, up, s.links, caps, s.endpoint, s.endpointPort, s.vpnUid,
            s.routes4, s.routes6, s.rules4, s.rules6, s.dns);
    }
}
