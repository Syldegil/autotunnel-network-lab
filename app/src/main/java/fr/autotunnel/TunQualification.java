package io.github.syldegil.autotunnel;

/** Correlation contract; a visible tun or handshake alone cannot satisfy it. */
public final class TunQualification {
    public static CapabilityMatrix.Status assess(boolean visible, boolean currentBootAndIfindex, boolean ownerUidBound,
            boolean officialBackendBound, boolean outerSocketProtected, boolean policyAndReturnObserved,
            boolean forwardedClientTest, boolean independentNoLeakEvidence) {
        if (!visible) return CapabilityMatrix.Status.UNKNOWN;
        if (!currentBootAndIfindex || !ownerUidBound || !officialBackendBound || !outerSocketProtected || !policyAndReturnObserved)
            return CapabilityMatrix.Status.HARDWARE_OBSERVED;
        return forwardedClientTest && independentNoLeakEvidence ? CapabilityMatrix.Status.HARDWARE_VERIFIED : CapabilityMatrix.Status.HARDWARE_OBSERVED;
    }
}
