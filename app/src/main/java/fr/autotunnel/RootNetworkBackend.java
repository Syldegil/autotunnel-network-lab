package io.github.syldegil.autotunnel;

import java.util.*;

/** Real command backend. Its process runner is separate from capabilities and hardware policy. */
public final class RootNetworkBackend implements NetworkBackend {
    public interface Runner { Result run(NetworkPlan.Tool tool, List<String> arguments) throws Exception; }
    public interface Observer { NetworkSnapshot read() throws Exception; }
    public interface Qualification {
        boolean enabled();
        default boolean authorized(NetworkPlan plan, long now) { return false; }
        boolean verified(NetworkPlan plan, long now) throws Exception;
        void drain(NetworkPlan plan) throws Exception;
    }
    public static final class Result {
        public final int code; public final String output;
        public Result(int code, String output) { this.code = code; this.output = output; }
    }
    private final Runner runner;
    private final Observer observer;
    private final Qualification qualification;
    private final boolean simulation;
    private NetworkPlan activePlan;
    private final Set<NetworkPlan.Operation> approved = new HashSet<>(), touched = new HashSet<>();
    public RootNetworkBackend(Runner runner, Observer observer, Qualification qualification, boolean simulation) {
        this.runner = runner; this.observer = observer; this.qualification = qualification; this.simulation = simulation;
    }
    public NetworkSnapshot observe() throws Exception { return observer.read(); }
    public boolean mutationsEnabled() { return qualification.enabled(); }
    public boolean simulated() { return simulation; }
    public boolean pathVerified(NetworkPlan plan, long now) throws Exception {
        return observe().tunnelUp && qualification.verified(plan, now);
    }
    public void drainOwnedFlows(NetworkPlan plan) throws Exception { qualification.drain(plan); }
    public void preflight(NetworkPlan plan) throws Exception {
        if (!mutationsEnabled() || !qualification.authorized(plan, System.currentTimeMillis())) throw new IllegalStateException("Capabilities not qualified");
        for (NetworkPlan.Operation op : concat(plan.guards, plan.policy)) {
            if (op.apply.contains("-N")) {
                Result r = runner.run(op.tool, op.inspect);
                if (r.code == 0 || !absent(r)) throw new IllegalStateException("Chain collision or inaccessible");
            }
        }
        // Derive reservations from this exact compiler output, including every branch.
        Map<String, Set<String>> tables = new LinkedHashMap<>(), priorities = new LinkedHashMap<>();
        for (NetworkPlan.Operation op : plan.policy) if (op.tool == NetworkPlan.Tool.IP) {
            String family = op.apply.get(0);
            if (!family.equals("-4") && !family.equals("-6")) throw new IllegalStateException("Unknown family");
            for (String flag : Arrays.asList("table", "lookup", "priority")) {
                int i = op.apply.indexOf(flag);
                if (i < 0) continue;
                if (i + 1 >= op.apply.size() || !op.apply.get(i + 1).matches("[1-9][0-9]{0,8}"))
                    throw new IllegalStateException("Invalid reservation");
                (flag.equals("priority") ? priorities : tables)
                    .computeIfAbsent(family, key -> new LinkedHashSet<>()).add(op.apply.get(i + 1));
            }
        }
        for (Map.Entry<String, Set<String>> entry : tables.entrySet()) for (String table : entry.getValue()) {
            Result routes = runner.run(NetworkPlan.Tool.IP, Arrays.asList(entry.getKey(), "route", "show", "table", table));
            boolean missing = routes.code == 2 && routes.output.contains("FIB table does not exist") && !routes.output.contains("Permission denied");
            if (!missing && (routes.code != 0 || !routes.output.trim().isEmpty())) throw new IllegalStateException("Routing table collision");
        }
        for (Map.Entry<String, Set<String>> entry : priorities.entrySet()) {
            Result rules = runner.run(NetworkPlan.Tool.IP, Arrays.asList(entry.getKey(), "rule", "show"));
            if (rules.code != 0) throw new IllegalStateException("Rules unreadable");
            for (String line : rules.output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                int colon = trimmed.indexOf(':');
                if (colon < 1 || !trimmed.substring(0, colon).matches("[0-9]+"))
                    throw new IllegalStateException("Unknown RPDB format");
                if (entry.getValue().contains(trimmed.substring(0, colon))) throw new IllegalStateException("Priority collision");
            }
        }
        approve(plan);
        touched.clear();
        activePlan = plan;
    }
    private void approve(NetworkPlan plan) {
        approved.clear(); approved.addAll(plan.guards); approved.addAll(plan.policy); approved.addAll(plan.open); approved.addAll(plan.close);
    }
    public void execute(NetworkPlan.Operation op) throws Exception {
        if (!mutationsEnabled() || !approved.contains(op)) throw new IllegalStateException("Unapproved operation");
        if (activePlan != null && activePlan.open.contains(op) && !qualification.authorized(activePlan, System.currentTimeMillis()))
            throw new IllegalStateException("Qualification invalidated before opening");
        touched.add(op);
        if (runner.run(op.tool, op.apply).code != 0) throw new IllegalStateException("Network command failed");
    }
    public void undo(NetworkPlan.Operation op) throws Exception {
        if (!mutationsEnabled() || !approved.contains(op) || !touched.contains(op)) throw new IllegalStateException("Unowned operation");
        if (runner.run(op.tool, op.undo).code != 0) throw new IllegalStateException("Targeted undo failed");
    }
    public boolean present(NetworkPlan.Operation op) throws Exception {
        Result result = runner.run(op.tool, op.inspect);
        boolean missingTable = op.tool == NetworkPlan.Tool.IP && op.inspect.contains("route")
            && op.inspect.contains("table") && result.code == 2
            && result.output.contains("FIB table does not exist") && !result.output.contains("Permission denied");
        if (result.code != 0 && !absent(result) && !missingTable) throw new IllegalStateException("Readback unavailable");
        return op.matches(result.code, result.output);
    }
    private boolean absent(Result r) {
        return r.code == 1 && (r.output.contains("No chain/target/match by that name") || r.output.contains("Bad rule") || r.output.contains("does a matching rule exist"));
    }
    private List<NetworkPlan.Operation> concat(List<NetworkPlan.Operation> a, List<NetworkPlan.Operation> b) {
        List<NetworkPlan.Operation> out = new ArrayList<>(a); out.addAll(b); return out;
    }
    /** Root-owned, compiler-reconstructed journal only; never a client-supplied operation list. */
    public void adoptOwned(NetworkPlan plan, Set<String> journalIds) {
        if (!mutationsEnabled()) throw new IllegalStateException("Hardware locked");
        RecoveryPlan.validateOwnedIds(plan, journalIds);
        approve(plan);
        touched.clear();
        activePlan = plan;
        for (NetworkPlan.Operation op : concat(plan.guards, plan.policy)) if (journalIds.contains(op.id)) touched.add(op);
    }
}
