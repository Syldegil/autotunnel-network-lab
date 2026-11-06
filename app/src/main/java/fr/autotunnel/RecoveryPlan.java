package io.github.syldegil.autotunnel;

import java.util.*;

/** Rebuild operations from declarative profiles, never from journal-supplied argv. */
public final class RecoveryPlan {
    private RecoveryPlan() { }
    public static NetworkPlan reconstruct(List<RoutingProfile> profiles, NetworkSnapshot snapshot, String digest) {
        if (profiles == null || profiles.isEmpty() || profiles.size() > 3) throw new IllegalArgumentException("Invalid profiles");
        NetworkPlan plan;
        if (profiles.size() == 1) plan = new NetfilterCompiler().compile(profiles.get(0), snapshot);
        else {
            List<RoutingTopology.Branch> branches = new ArrayList<>();
            for (RoutingProfile p : profiles) branches.add(new RoutingTopology.Branch(p.scope.name(),
                p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL ? "recovery-shared-wg" : "", p,
                Collections.emptyList(), Collections.singletonList("recovery-destination")));
            plan = new TopologyCompiler().compile(new RoutingTopology(branches), snapshot);
        }
        if (!plan.digest.equals(digest)) throw new IllegalArgumentException("Journal digest mismatch");
        return plan;
    }
    public static void validateOwnedIds(NetworkPlan plan, Set<String> ids) {
        Set<String> allowed = new HashSet<>();
        for (List<NetworkPlan.Operation> ops : Arrays.asList(plan.guards, plan.policy))
            for (NetworkPlan.Operation op : ops) allowed.add(op.id);
        if (!allowed.containsAll(ids)) throw new IllegalArgumentException("Unknown journal ownership");
    }
    public static Set<String> pending(NetworkPlan plan, List<String> recorded, String phase) {
        Set<String> ids = new LinkedHashSet<>(recorded);
        if (ids.size() != recorded.size()) throw new IllegalArgumentException("Duplicate ownership");
        validateOwnedIds(plan, ids);
        if (phase.startsWith("BEFORE:")) {
            String pending = phase.substring(7);
            Set<String> candidate = new LinkedHashSet<>(ids); candidate.add(pending);
            boolean gate = plan.open.stream().anyMatch(op -> op.id.equals(pending));
            if (!gate) { validateOwnedIds(plan, candidate); ids = candidate; }
            // Interrupted opening never grants ownership or triggers automatic reopening.
        }
        return ids;
    }
}
