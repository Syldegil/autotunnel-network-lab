package io.github.syldegil.autotunnel;

/** Single-step reversible edits, independent of active WireGuard configuration. */
public final class DraftHistory {
    public final RoutingProfile current, previous;
    public final long revision;
    public DraftHistory(RoutingProfile current, RoutingProfile previous, long revision) {
        if (current == null || revision < 0 || (previous != null && current.scope != previous.scope))
            throw new IllegalArgumentException("Historique invalide.");
        this.current = current; this.previous = previous; this.revision = revision;
    }
    public DraftHistory save(RoutingProfile next) {
        if (next.scope != current.scope) throw new IllegalArgumentException("Profil différent.");
        return new DraftHistory(next, current, revision + 1);
    }
    public DraftHistory undo() {
        if (previous == null) throw new IllegalStateException("Aucune révision précédente.");
        return new DraftHistory(previous, current, revision + 1);
    }
}
