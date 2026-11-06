package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.ExperimentSession.State.*;

public class ExperimentSessionTest {
    static final String DIGEST = String.join("", java.util.Collections.nCopies(64, "a"));
    static class Adapter implements ExperimentSession.Adapter {
        String identity = "boot/usb-ifindex/client/tun";
        boolean ready = true, closed, restored, journalFails, closeFails, prepareFails;
        int mutations, restores;
        public String identity() { return identity; }
        public boolean prerequisitesVerified() { return ready; }
        public void checkpoint(String p) throws Exception { if (journalFails) throw new Exception(); }
        public void installClosed() { mutations++; closed = true; }
        public boolean closed() { return closed; }
        public void prepareOwned() throws Exception { mutations++; if (prepareFails) throw new Exception(); }
        public void openOnlyApprovedFlows() { mutations++; closed = false; }
        public void emergencyBlock() throws Exception { mutations++; if (closeFails) throw new Exception(); closed = true; }
        public void restoreOwned() { restores++; restored = true; closed = false; }
        public boolean restored() { return restored; }
    }
    private ExperimentSession.Consent consent(Adapter a) { return new ExperimentSession.Consent(a.identity, DIGEST, 100, 500); }
    @Test public void missingConsentAndUnknownOffloadCannotMutate() {
        Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
        assertEquals(ERROR, e.begin(null, DIGEST, 100)); assertEquals(0, a.mutations);
        a.ready = false; e = new ExperimentSession(a);
        assertEquals(ERROR, e.begin(consent(a), DIGEST, 100)); assertEquals(0, a.mutations);
    }
    @Test public void slowPreparationCannotOpenAfterDeadline() {
        Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
        assertEquals(PREPARED, e.begin(consent(a), DIGEST, 100));
        assertEquals(BLOCKED, e.open(600)); assertEquals(0, a.restores);
    }
    @Test public void helperDeathTunnelLossExpiryAndScopeChangeNeverRestoreDirect() {
        for (int failure = 0; failure < 5; failure++) {
            Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
            e.begin(consent(a), DIGEST, 100); assertEquals(EXPERIMENTAL, e.open(110));
            if (failure == 3) a.identity = "reused-interface";
            if (failure == 4) a.ready = false;
            assertEquals(BLOCKED, e.supervise(failure == 2 ? 600 : 120, failure != 0, failure != 1));
            assertTrue(a.closed); assertEquals(0, a.restores);
            assertEquals(BLOCKED, e.restore(false));
        }
    }
    @Test public void emergencyClosureStillRunsWhenJournalFails() {
        Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
        e.begin(consent(a), DIGEST, 100); e.open(110); a.journalFails = true;
        assertEquals(ERROR, e.block()); assertTrue(a.closed); assertEquals(0, a.restores);
    }
    @Test public void failedPreparationClosesAndExplicitRestoreIsIdempotent() {
        Adapter a = new Adapter(); a.prepareFails = true; ExperimentSession e = new ExperimentSession(a);
        assertEquals(BLOCKED, e.begin(consent(a), DIGEST, 100));
        assertEquals(RESTORED, e.restore(true)); assertEquals(RESTORED, e.restore(true)); assertEquals(1, a.restores);
    }
    @Test public void failedClosureAndChangedIdentityPreventRemoval() {
        Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
        e.begin(consent(a), DIGEST, 100); e.open(110); a.closeFails = true;
        assertEquals(ERROR, e.restore(true)); assertEquals(0, a.restores);
        a.closeFails = false; a.identity = "new-boot";
        assertEquals(ERROR, e.restore(true)); assertEquals(0, a.restores);
    }
    @Test public void mismatchedDigestAndClockRollbackAreRejected() {
        Adapter a = new Adapter(); ExperimentSession e = new ExperimentSession(a);
        assertEquals(ERROR, e.begin(consent(a), DIGEST.replace('a', 'b'), 100)); assertEquals(0,a.mutations);
        e = new ExperimentSession(a); e.begin(consent(a), DIGEST,100); e.open(110);
        assertEquals(BLOCKED,e.supervise(99,true,true));
    }
    @Test(expected=IllegalArgumentException.class) public void unboundedConsentRejected() {
        new ExperimentSession.Consent("scope",DIGEST,0,180001);
    }
}
