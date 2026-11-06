package io.github.syldegil.autotunnel;

import java.util.*;

/** Serialized transaction coordinator; desired VPN intent never changes on a tunnel failure. */
public final class NetworkEngine {
    public enum State { DIRECT, VPN_CONNECTING, VPN_PROTECTED, VPN_BLOCKED, VPN_RECOVERING, UNSUPPORTED, ERROR }
    public enum Event { TUNNEL_LOST, UPSTREAM_CHANGED, UPSTREAM_LOST, TETHER_CHANGED, SERVICE_DIED, BOOT, PERMISSION_DENIED, RULE_STALE, RECOVER }
    public interface Journal {
        void write(String phase, NetworkPlan plan, List<String> applied) throws Exception;
    }
    public static final class MemoryJournal implements Journal {
        public final List<String> events = new ArrayList<>();
        public void write(String phase, NetworkPlan plan, List<String> applied) {
            events.add(phase + ":" + plan.digest + ":" + applied.size());
        }
    }
    private final NetworkBackend backend;
    private final Journal journal;
    private final List<NetworkPlan.Operation> guards = new ArrayList<>(), applied = new ArrayList<>();
    private NetworkPlan plan;
    private State state = State.UNSUPPORTED;
    private String error = "Aucune transaction";
    private boolean required;
    public NetworkEngine(NetworkBackend backend, Journal journal) { this.backend = backend; this.journal = journal; }
    public synchronized State state() { return state; }
    public synchronized String error() { return error; }
    public synchronized boolean simulated() { return backend.simulated(); }
    public synchronized List<String> operationIds() { return ids(); }
    /** Called only with a validated root-owned checkpoint, not an IPC-supplied snapshot. */
    synchronized void resumeOwned(NetworkPlan saved, Set<String> journalIds) throws Exception {
        if (plan != null || !backend.mutationsEnabled() || saved.profiles.size()>1&&!backend.simulated() || !backend.observe().identity().equals(saved.snapshot.identity()))
            throw new IllegalStateException("Recovery identity/boot mismatch");
        RecoveryPlan.validateOwnedIds(saved, journalIds);
        backend.adoptOwned(saved, journalIds); plan = saved; required = plan.profile.mode == RoutingProfile.Mode.REQUIRE_TUNNEL;
        for (NetworkPlan.Operation op : plan.guards) if (journalIds.contains(op.id)) guards.add(op);
        for (NetworkPlan.Operation op : plan.policy) if (journalIds.contains(op.id)) applied.add(op);
        state = State.ERROR; error = "Journal repris ; aucune ouverture automatique";
    }
    private List<String> ids() {
        List<String> ids = new ArrayList<>(); for (NetworkPlan.Operation op : guards) ids.add(op.id);
        for (NetworkPlan.Operation op : applied) ids.add(op.id); return ids;
    }
    public synchronized NetworkPlan preview(RoutingProfile profile) throws Exception {
        return new NetfilterCompiler().compile(profile, backend.observe());
    }
    public synchronized NetworkPlan preview(RoutingTopology topology) throws Exception {
        return new TopologyCompiler().compile(topology,backend.observe());
    }
    public synchronized State apply(NetworkPlan proposed, String confirmedDigest, long now) {
        if (!proposed.digest.equals(confirmedDigest)) { error = "Confirmation du plan requise"; return state; }
        if (plan != null) { error = "Transaction existante : fermer/restaurer explicitement avant remplacement"; return state; }
        try {
            NetworkSnapshot current = backend.observe();
            long checkedAt = backend.simulated() ? now : System.currentTimeMillis();
            if (!backend.mutationsEnabled() || proposed.snapshot.simulated != backend.simulated()
                || proposed.profiles.size()>1&&!backend.simulated()
                || !current.identity().equals(proposed.snapshot.identity()) || !proposed.blockers(current, checkedAt).isEmpty()) {
                state = State.UNSUPPORTED; error = "Capacités/verrou matériel/identité non qualifiés"; return state;
            }
            backend.preflight(proposed);
            plan = proposed; required = plan.profile.mode == RoutingProfile.Mode.REQUIRE_TUNNEL;
            journal.write("INTENT", plan, ids());
            state = required ? State.VPN_CONNECTING : State.ERROR;
            for (NetworkPlan.Operation op : plan.guards) perform(op, guards);
            journal.write("GUARDED", plan, ids());
            backend.drainOwnedFlows(plan);
            for (NetworkPlan.Operation op : plan.policy) perform(op, applied);
            if (required && !backend.pathVerified(plan, backend.simulated() ? now : System.currentTimeMillis())) {
                state = State.VPN_BLOCKED; error = "Règles préparées ; preuve du parcours absente, barrières conservées";
                journal.write("BLOCKED", plan, ids()); return state;
            }
            return open(now);
        } catch (Exception ignored) { return fail(); }
    }
    private void perform(NetworkPlan.Operation op, List<NetworkPlan.Operation> completed) throws Exception {
        journal.write("BEFORE:" + op.id, plan, ids());
        // Record even an ambiguous command failure: its effect must be inspected before rollback.
        completed.add(op);
        backend.execute(op);
        if (!backend.present(op)) throw new IllegalStateException("Readback failed");
        journal.write("AFTER:" + op.id, plan, ids());
    }
    private State open(long now) throws Exception {
        NetworkSnapshot current = backend.observe();
        long checkedAt = backend.simulated() ? now : System.currentTimeMillis();
        if (!current.identity().equals(plan.snapshot.identity()) || !plan.blockers(current, checkedAt).isEmpty()
            || required && !backend.pathVerified(plan, checkedAt)) throw new IllegalStateException("Evidence invalidated");
        for (NetworkPlan.Operation op : plan.policy) if (!backend.present(op)) throw new IllegalStateException("Policy drift");
        for (NetworkPlan.Operation op : plan.guards) if (!backend.present(op)) throw new IllegalStateException("Guard drift");
        for (NetworkPlan.Operation op : plan.open) {
            journal.write("BEFORE:" + op.id, plan, ids()); backend.execute(op);
            if (!backend.present(op)) throw new IllegalStateException("Open failed");
        }
        state = required ? State.VPN_PROTECTED : State.DIRECT; error = "";
        journal.write(state.name(), plan, ids()); return state;
    }
    private boolean close() {
        boolean ok = true;
        for (NetworkPlan.Operation op : plan.close) {
            try { backend.execute(op); if (!backend.present(op)) ok = false; }
            catch (Exception ignored) { ok = false; }
        }
        for (NetworkPlan.Operation op : plan.guards) {
            try { if (!backend.present(op)) ok = false; } catch (Exception ignored) { ok = false; }
        }
        return ok;
    }
    private State fail() {
        boolean closed = plan != null && close();
        try { closed &= plan != null && backend.observe().identity().equals(plan.snapshot.identity()); }
        catch (Exception ignored) { closed = false; }
        // Never remove a barrier on failure. Roll back only policy objects whose presence is established.
        boolean restored = true;
        for (int i = applied.size() - 1; i >= 0; i--) {
            NetworkPlan.Operation op = applied.get(i);
            try { if (backend.present(op)) backend.undo(op); if (backend.present(op)) restored = false; }
            catch (Exception ignored) { restored = false; }
        }
        state = closed && restored && required ? State.VPN_BLOCKED : State.ERROR;
        error = closed ? "Échec transaction ; fermeture relue, restauration ciblée à contrôler" : "Échec de fermeture : protection NON démontrée";
        try { if (plan != null) journal.write("FAILED:" + state, plan, ids()); }
        catch (Exception ignored) { state = State.ERROR; error = "Journal indisponible ; protection non certifiée"; }
        return state;
    }
    public synchronized State event(Event event, long now) {
        if (plan == null) { state = State.UNSUPPORTED; return state; }
        if(event==Event.TUNNEL_LOST&&plan.profiles.size()>1){
            boolean closed=true;
            for(RoutingProfile p:plan.profiles)if(p.mode==RoutingProfile.Mode.REQUIRE_TUNNEL)
                for(NetworkPlan.Operation op:plan.close)if(op.id.startsWith(p.scope.name()+"/"))try{backend.execute(op);closed&=backend.present(op);}catch(Exception e){closed=false;}
            state=closed?State.VPN_BLOCKED:State.ERROR;error=closed?"Branches VPN fermées ; exceptions directes explicites conservées":"Fermeture des branches non démontrée";
            try{if(!backend.observe().identity().equals(plan.snapshot.identity())){state=State.ERROR;close();}journal.write("EVENT:TUNNEL_LOST",plan,ids());}catch(Exception e){state=State.ERROR;close();}
            return state;
        }
        if (event == Event.RECOVER) {
            state = required ? State.VPN_RECOVERING : State.ERROR;
            try { if (!close()) throw new IllegalStateException(); return open(now); } catch (Exception ignored) { return fail(); }
        }
        boolean closed = close();
        state = closed && required ? State.VPN_BLOCKED : State.ERROR;
        error = closed ? "Changement réseau : fermeture relue, nouvelle qualification requise" : "Fermeture non démontrée";
        try {
            if (!backend.observe().identity().equals(plan.snapshot.identity())) {
                state = State.ERROR; error = "Ancien périmètre fermé ; nouvelles interfaces non vérifiées";
            }
        } catch (Exception ignored) { state = State.ERROR; }
        try { journal.write("EVENT:" + event, plan, ids()); } catch (Exception ignored) { state = State.ERROR; }
        return state;
    }
    public synchronized Map<RoutingProfile.Scope,State> branchStates() {
        return branchStates(backend.simulated() && plan != null ? plan.snapshot.sampledAt : System.currentTimeMillis());
    }
    /** Reporting is read-only: installed rules alone never establish a protected path. */
    synchronized Map<RoutingProfile.Scope,State> branchStates(long now) {
        Map<RoutingProfile.Scope,State> states = new EnumMap<>(RoutingProfile.Scope.class);
        if (plan == null) return states;
        boolean identityValid = false, proof = false, policyPresent = true, guardsPresent = true;
        try {
            NetworkSnapshot current = backend.observe();
            identityValid = current.identity().equals(plan.snapshot.identity()) && plan.blockers(current, now).isEmpty();
            proof = identityValid && backend.pathVerified(plan, now);
            for (NetworkPlan.Operation op : plan.policy) policyPresent &= backend.present(op);
            // Gate slots are deliberately replaced by open/close; verify their current
            // form per branch below, while still requiring every static guard/hook.
            for (NetworkPlan.Operation op : plan.guards)
                if (!gateSlot(op)) guardsPresent &= backend.present(op);
        } catch (Exception ignored) { identityValid = false; }
        for (RoutingProfile p : plan.profiles) {
            boolean blocked = true, opened = true, found = false;
            for (int i = 0; i < plan.close.size(); i++) {
                if (plan.profiles.size() != 1 && !plan.close.get(i).id.startsWith(p.scope.name() + "/")) continue;
                found = true;
                try { blocked &= backend.present(plan.close.get(i)); opened &= backend.present(plan.open.get(i)); }
                catch (Exception ignored) { blocked = false; opened = false; }
            }
            State branch = State.ERROR;
            if (found && identityValid && guardsPresent) {
                if (blocked) branch = State.VPN_BLOCKED;
                else if (opened && policyPresent && state != State.ERROR) {
                    if (p.mode == RoutingProfile.Mode.DIRECT) branch = State.DIRECT;
                    else if (proof) branch = State.VPN_PROTECTED;
                }
            }
            states.put(p.scope, branch);
        }
        return states;
    }
    private boolean gateSlot(NetworkPlan.Operation guard) {
        if (guard.check != NetworkPlan.Check.RULE_AT_INDEX) return false;
        for (NetworkPlan.Operation gate : plan.close)
            if (guard.tool == gate.tool && guard.position == gate.position && guard.inspect.equals(gate.inspect)) return true;
        return false;
    }
    /** Explicit profile removal/emergency restoration. WG OFF with firewall uses a new DIRECT plan. */
    public synchronized State restore(boolean explicitDirectConsent) {
        if (plan == null || !explicitDirectConsent) { error = "Retour direct explicite requis"; return state; }
        try {
            if (!backend.observe().identity().equals(plan.snapshot.identity())) throw new IllegalStateException("Stale interface");
            // Explicit direct consent also permits recovery of a partly installed guard.
            // Best-effort close; deletion still requires exact ownership/readback below.
            close();
            journal.write("RESTORE_DIRECT_CONFIRMED", plan, ids()); backend.drainOwnedFlows(plan);
            for (int i = applied.size() - 1; i >= 0; i--) {
                NetworkPlan.Operation op = applied.get(i); if (backend.present(op)) backend.undo(op);
                if (backend.present(op)) throw new IllegalStateException("Undo failed");
            }
            for (int i = guards.size() - 1; i >= 0; i--) {
                NetworkPlan.Operation op = guards.get(i); if (backend.present(op)) backend.undo(op);
                if (backend.present(op)) throw new IllegalStateException("Undo guard failed");
            }
            journal.write("RESTORED", plan, Collections.emptyList());
            plan = null; applied.clear(); guards.clear(); required = false; state = State.DIRECT; error = "Règles AutoTunnel retirées sur consentement";
        } catch (Exception ignored) { state = State.ERROR; error = "Restauration incomplète : objets/journal conservés, contrôle manuel requis"; }
        return state;
    }
}
