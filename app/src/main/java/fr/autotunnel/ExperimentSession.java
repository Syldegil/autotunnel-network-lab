package io.github.syldegil.autotunnel;

import java.util.Objects;

/** Root-controlled bounded experiment. Never creates a hardware qualification record.
 * The caller must supply independently validated guards and a root-owned consent record;
 * no IPC/graph constructor or Android mutation adapter is exposed in this release. */
public final class ExperimentSession {
    public enum Mode { OBSERVE, PREVIEW, EXPERIMENT, RESTORE, EMERGENCY_BLOCK }
    public enum State { IDLE, PREPARED, EXPERIMENTAL, BLOCKED, RESTORED, ERROR }
    public interface Adapter {
        String identity() throws Exception;
        boolean prerequisitesVerified() throws Exception;
        void checkpoint(String phase) throws Exception;
        void installClosed() throws Exception;
        boolean closed() throws Exception;
        void prepareOwned() throws Exception;
        void openOnlyApprovedFlows() throws Exception;
        void emergencyBlock() throws Exception;
        void restoreOwned() throws Exception;
        boolean restored() throws Exception;
    }
    public static final class Consent {
        final String identity, digest;
        final long issued, deadline;
        public Consent(String identity, String digest, long issuedElapsed, long durationMillis) {
            if (identity == null || identity.isEmpty() || digest == null || !digest.matches("[0-9a-f]{64}")
                || issuedElapsed < 0 || durationMillis < 1 || durationMillis > 180000
                || issuedElapsed > Long.MAX_VALUE - durationMillis) throw new IllegalArgumentException();
            this.identity = identity; this.digest = digest; this.issued = issuedElapsed;
            this.deadline = issuedElapsed + durationMillis;
        }
        boolean valid(String currentIdentity, String planDigest, long now) {
            return Objects.equals(identity, currentIdentity) && digest.equals(planDigest) && now >= issued && now < deadline;
        }
    }
    private final Adapter adapter;
    private State state = State.IDLE;
    private Consent consent;
    private String digest;
    private boolean touched;
    public ExperimentSession(Adapter adapter) { this.adapter = Objects.requireNonNull(adapter); }
    public synchronized State state() { return state; }
    public synchronized State begin(Consent rootConsent, String planDigest, long elapsed) {
        if (state != State.IDLE) throw new IllegalStateException("No replacement or implicit renewal");
        try {
            if (rootConsent == null || !rootConsent.valid(adapter.identity(), planDigest, elapsed)
                || !adapter.prerequisitesVerified()) throw new SecurityException("Experiment prerequisites missing");
            consent = rootConsent; digest = planDigest;
            adapter.checkpoint("BEFORE_GUARDS"); touched = true;
            adapter.installClosed();
            if (!adapter.closed()) throw new IllegalStateException("Guard not verified");
            adapter.checkpoint("BEFORE_PREPARE"); adapter.prepareOwned();
            adapter.checkpoint("PREPARED"); state = State.PREPARED;
        } catch (Exception ignored) { if (touched) return block(); state = State.ERROR; return state; }
        return state;
    }
    /** Separate fresh check after preparation; a slow preparation cannot consume the lease then open. */
    public synchronized State open(long elapsed) {
        if (state != State.PREPARED) return state;
        try {
            if (!consent.valid(adapter.identity(), digest, elapsed) || !adapter.prerequisitesVerified() || !adapter.closed()) return block();
            adapter.checkpoint("BEFORE_OPEN"); adapter.openOnlyApprovedFlows();
            adapter.checkpoint("EXPERIMENTAL"); state = State.EXPERIMENTAL;
        } catch (Exception ignored) { return block(); }
        return state;
    }
    /** Independent supervisor must call this; caller heartbeat is not a safety proof. */
    public synchronized State supervise(long elapsed, boolean controllerAlive, boolean tunnelAlive) {
        if (state != State.PREPARED && state != State.EXPERIMENTAL) return state;
        try {
            if (!controllerAlive || !tunnelAlive || !consent.valid(adapter.identity(), digest, elapsed)
                || !adapter.prerequisitesVerified()) return block();
        } catch (Exception ignored) { return block(); }
        return state;
    }
    public synchronized State block() {
        if (!touched) return state;
        // Journal failure must not prevent attempted emergency closure.
        boolean durable = true;
        try { adapter.checkpoint("BEFORE_EMERGENCY_BLOCK"); } catch (Exception ignored) { durable = false; }
        try {
            adapter.emergencyBlock();
            state = adapter.closed() && durable ? State.BLOCKED : State.ERROR;
            adapter.checkpoint(state.name());
        } catch (Exception ignored) { state = State.ERROR; }
        return state;
    }
    public synchronized State restore(boolean explicitDirectConsent) {
        if (!explicitDirectConsent || !touched) return state;
        if (block() != State.BLOCKED) return state;
        try {
            if (!consent.identity.equals(adapter.identity())) throw new IllegalStateException("Changed scope");
            adapter.checkpoint("RESTORE_DIRECT_CONFIRMED"); adapter.restoreOwned();
            if (!adapter.restored()) throw new IllegalStateException("Partial restore");
            adapter.checkpoint("RESTORED"); state = State.RESTORED; touched = false;
        } catch (Exception ignored) { state = State.ERROR; }
        return state;
    }
}
