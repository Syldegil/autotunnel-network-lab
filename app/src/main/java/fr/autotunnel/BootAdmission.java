package io.github.syldegil.autotunnel;

import java.util.*;

/** Model only: must be driven by a synchronous, pre-network platform barrier, not app polling. */
public final class BootAdmission {
    private final String boot;
    private final Map<String, Boolean> closed = new HashMap<>();
    private boolean earlyBarrierVerified;
    public BootAdmission(String boot) { this.boot = boot; }
    public void earlyBarrier(String observedBoot, boolean beforeAnyNetwork, boolean v4, boolean v6, boolean bypassCovered) {
        earlyBarrierVerified = boot.equals(observedBoot) && beforeAnyNetwork && v4 && v6 && bypassCovered;
        if (!earlyBarrierVerified) closed.clear();
    }
    public void interfaceCreated(String identity) { closed.put(identity, false); }
    public void barrierAcknowledged(String identity) { if (closed.containsKey(identity) && earlyBarrierVerified) closed.put(identity, true); }
    public boolean mayOpen(String identity, boolean pathVerified, boolean consent) {
        return earlyBarrierVerified && Boolean.TRUE.equals(closed.get(identity)) && pathVerified && consent;
    }
    public void invalidate() { earlyBarrierVerified = false; closed.clear(); }
}
