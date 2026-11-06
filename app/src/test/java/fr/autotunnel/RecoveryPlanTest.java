package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RecoveryPlanTest {
    private NetworkPlan plan(int template, SimulatedNetworkBackend b) throws Exception {
        return new NetworkEngine(b, new NetworkEngine.MemoryJournal()).preview(GraphCompiler.compile(GraphTemplates.create(template)).topology);
    }
    @Test public void allSevenPlansReconstructExactlyWithoutMutation() throws Exception {
        for (int i = 0; i < 7; i++) {
            SimulatedNetworkBackend b = new SimulatedNetworkBackend(1000); NetworkPlan p = plan(i, b);
            NetworkPlan rebuilt = RecoveryPlan.reconstruct(p.profiles, p.snapshot, p.digest);
            assertEquals(p.digest, rebuilt.digest); assertEquals(p.preview(), rebuilt.preview());
            assertEquals(p.profiles.size(), rebuilt.profiles.size()); assertEquals(0, b.mutationCount);
            try { RecoveryPlan.reconstruct(p.profiles, p.snapshot, NetworkPlan.hash("altered")); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void restartedControllerRestoresAllBranchesAndPreservesForeignObjects() throws Exception {
        for (int i = 0; i < 7; i++) {
            SimulatedNetworkBackend b = new SimulatedNetworkBackend(1000);
            NetworkEngine old = new NetworkEngine(b, new NetworkEngine.MemoryJournal()); NetworkPlan p = plan(i, b);
            old.apply(p, p.digest, 1000); assertTrue(b.ownedCount() > 0);
            NetworkEngine restarted = new NetworkEngine(b, new NetworkEngine.MemoryJournal());
            int mutations = b.mutationCount;
            restarted.resumeOwned(p, new HashSet<>(old.operationIds()));
            assertEquals(NetworkEngine.State.ERROR, restarted.state()); assertEquals(mutations, b.mutationCount);
            assertEquals(NetworkEngine.State.DIRECT, restarted.restore(true));
            assertEquals(0, b.ownedCount()); assertEquals(3, b.foreignObjects.size());
            assertEquals(NetworkEngine.State.DIRECT, restarted.restore(true));
        }
    }
    @Test public void beforeCommandClaimsOnlyCompiledOwnedResources() throws Exception {
        NetworkPlan p = plan(4, new SimulatedNetworkBackend(1000));
        for (NetworkPlan.Operation op : p.guards)
            assertEquals(Collections.singleton(op.id), RecoveryPlan.pending(p, Collections.emptyList(), "BEFORE:" + op.id));
        for (NetworkPlan.Operation op : p.open)
            assertTrue(RecoveryPlan.pending(p, Collections.emptyList(), "BEFORE:" + op.id).isEmpty());
        for (String phase : Arrays.asList("BEFORE:foreign", "BEFORE:")) {
            try { RecoveryPlan.pending(p, Collections.emptyList(), phase); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { RecoveryPlan.pending(p, Arrays.asList(p.guards.get(0).id,p.guards.get(0).id), "INTENT"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void partialTransactionsCanRecoverAfterEveryInterruptedOwnedOperation() throws Exception {
        SimulatedNetworkBackend initial = new SimulatedNetworkBackend(1000); NetworkPlan reference = plan(4, initial);
        int operations = reference.guards.size() + reference.policy.size();
        for (int failure = 1; failure <= operations; failure++) {
            SimulatedNetworkBackend b = new SimulatedNetworkBackend(1000); b.failAtMutation = failure;
            NetworkEngine engine = new NetworkEngine(b, new NetworkEngine.MemoryJournal()); NetworkPlan p = plan(4, b);
            engine.apply(p, p.digest, 1000);
            NetworkEngine restarted = new NetworkEngine(b, new NetworkEngine.MemoryJournal());
            // Same compiled plan objects model persistent kernel state; codec equality is tested separately.
            restarted.resumeOwned(p, new HashSet<>(engine.operationIds()));
            assertEquals("failure " + failure, NetworkEngine.State.DIRECT, restarted.restore(true));
            assertEquals(0, b.ownedCount()); assertEquals(3, b.foreignObjects.size());
        }
    }
}
