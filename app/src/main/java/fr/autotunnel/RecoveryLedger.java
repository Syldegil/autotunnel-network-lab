package io.github.syldegil.autotunnel;

import java.util.*;

/** Idempotent compare-and-restore of journal-owned changes; absent value is distinct from zero. */
public final class RecoveryLedger {
    public enum Kind { OWNED_RULE, OWNED_ROUTE, OFFLOAD_SETTING }
    public static final class Entry {
        public final Kind kind;
        public final String resource, before, applied;
        public Entry(Kind kind, String resource, String before, String applied) {
            if (kind == null || !resource.matches("[A-Za-z0-9_.:/-]{1,128}") || Objects.equals(before, applied)) throw new IllegalArgumentException();
            if (kind == Kind.OFFLOAD_SETTING && !resource.equals("tether_offload_disabled")) throw new IllegalArgumentException();
            if (kind != Kind.OFFLOAD_SETTING && !resource.startsWith("AT13/")) throw new IllegalArgumentException("Not owned");
            this.kind = kind; this.resource = resource; this.before = before; this.applied = applied;
        }
    }
    public interface Adapter {
        String current(Entry entry) throws Exception;
        void restoreExact(Entry entry) throws Exception;
        void checkpoint(Entry entry) throws Exception;
    }
    public static void restore(List<Entry> entries, Adapter adapter, boolean directConsent) throws Exception {
        if (!directConsent) throw new SecurityException("Explicit recovery consent required");
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i); String current = adapter.current(e);
            if (Objects.equals(current, e.before)) continue;
            if (!Objects.equals(current, e.applied)) throw new IllegalStateException("Foreign modification: manual review");
            adapter.checkpoint(e); adapter.restoreExact(e);
            if (!Objects.equals(adapter.current(e), e.before)) throw new IllegalStateException("Restore not confirmed");
        }
    }
}
