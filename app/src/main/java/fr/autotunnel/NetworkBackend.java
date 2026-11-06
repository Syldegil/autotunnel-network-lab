package io.github.syldegil.autotunnel;

/** Control-plane readback and independent data-path evidence are separate contracts. */
public interface NetworkBackend {
    NetworkSnapshot observe() throws Exception;
    boolean mutationsEnabled();
    boolean simulated();
    void preflight(NetworkPlan plan) throws Exception;
    void execute(NetworkPlan.Operation operation) throws Exception;
    void undo(NetworkPlan.Operation operation) throws Exception;
    boolean present(NetworkPlan.Operation operation) throws Exception;
    boolean pathVerified(NetworkPlan plan, long now) throws Exception;
    /** Must drain only the selected downstream flows, never flush global conntrack. */
    void drainOwnedFlows(NetworkPlan plan) throws Exception;
    void adoptOwned(NetworkPlan plan, java.util.Set<String> journalIds) throws Exception;
}
