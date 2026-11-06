package io.github.syldegil.autotunnel;

import java.util.*;

/** Complete context required by future root attestor. Missing dimensions can never authorize a profile. */
public final class QualificationContext {
    public enum Dimension { SYSTEM_BUILD, KERNEL, BACKEND, CONFIGURATION, BOOT, INTERFACES, ROUTING, FILTERING, OFFLOAD, SELINUX }
    private final Map<Dimension, String> fingerprints;
    public QualificationContext(Map<Dimension, String> fingerprints) {
        EnumMap<Dimension,String> copy = new EnumMap<>(Dimension.class);
        for (Map.Entry<Dimension,String> e : fingerprints.entrySet()) {
            if (!e.getValue().matches("[0-9a-f]{64}")) throw new IllegalArgumentException(); copy.put(e.getKey(), e.getValue());
        }
        this.fingerprints = Collections.unmodifiableMap(copy);
    }
    public boolean complete() { return fingerprints.size() == Dimension.values().length; }
    public String digest() { return NetworkPlan.hash(fingerprints.toString()); }
}
