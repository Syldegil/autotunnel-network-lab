package io.github.syldegil.autotunnel;

/** Presence, activity hints and qualification are deliberately separate. */
public final class OffloadAssessment {
    public enum State { COMPATIBLE_QUALIFIED, REQUIRES_SPECIFIC_HANDLING, UNKNOWN, INCOMPATIBLE }
    public static State assess(boolean readable, boolean bpfActive, boolean halActive, boolean bypassDemonstrated,
                               CapabilityMatrix.Status proof) {
        if (bypassDemonstrated || proof == CapabilityMatrix.Status.FAILED) return State.INCOMPATIBLE;
        if (proof == CapabilityMatrix.Status.HARDWARE_VERIFIED) return State.COMPATIBLE_QUALIFIED;
        if (readable && (bpfActive || halActive)) return State.REQUIRES_SPECIFIC_HANDLING;
        return State.UNKNOWN; // absence/inactive without clients is not safety evidence
    }
}
