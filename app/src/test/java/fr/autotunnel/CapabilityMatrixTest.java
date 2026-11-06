package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.CapabilityMatrix.*;

public class CapabilityMatrixTest {
    @Test public void sixteenCapabilitiesRequireIndependentIpcAndRollbackProofs() {
        assertEquals(16, Capability.values().length);
        CapabilityMatrix m = new CapabilityMatrix();
        for (Path path : Path.values()) {
            assertTrue(m.requirements(RoutingProfile.defaults(RoutingProfile.Scope.ALL), path).contains(Capability.IPC_AUTHENTICATION));
            assertTrue(m.requirements(RoutingProfile.defaults(RoutingProfile.Scope.ALL), path).contains(Capability.OWNED_ROLLBACK));
        }
    }
    private final String context = NetworkPlan.hash("full context"), artifact = NetworkPlan.hash("external experiment record");
    private Evidence e(Capability c, Path p, Status s) { return new Evidence(c,p,s,context,artifact,"DS015_IPV4",100,1000); }
    @Test public void observedSimulatedFailedNeverAuthorize() {
        for (Status s : Status.values()) if (s != Status.HARDWARE_VERIFIED) {
            CapabilityMatrix m = new CapabilityMatrix(); RoutingProfile profile = RoutingProfile.defaults(RoutingProfile.Scope.USB);
            for (Capability c : m.requirements(profile, Path.USB)) m.recordReviewed(e(c,Path.USB,s));
            assertFalse(m.permits(profile,Collections.singleton(Path.USB),context,200,true));
        }
    }
    @Test public void uiImportedVerifiedRecordCannotGrantPrivilege() {
        CapabilityMatrix m = new CapabilityMatrix(); m.recordObservation(e(Capability.FIREWALL_IPV4,Path.USB,Status.HARDWARE_VERIFIED));
        assertEquals(Status.HARDWARE_OBSERVED,m.status(Capability.FIREWALL_IPV4,Path.USB,context,200));
    }
    @Test public void proofsAreIndependentBetweenFamiliesPathsAndConsent() {
        CapabilityMatrix m = new CapabilityMatrix(); RoutingProfile p = RoutingProfile.defaults(RoutingProfile.Scope.USB);
        for (Capability c : m.requirements(p,Path.USB)) m.recordReviewed(e(c,Path.USB,Status.HARDWARE_VERIFIED));
        assertTrue(m.permits(p,Collections.singleton(Path.USB),context,200,true));
        assertFalse(m.permits(p,Collections.singleton(Path.USB),context,200,false));
        assertFalse(m.permits(p,Collections.singleton(Path.PHONE_WIFI),context,200,true));
        assertEquals(Status.UNKNOWN,m.status(Capability.USB_IPV6_FORWARD,Path.USB,context,200));
        assertEquals(Status.UNKNOWN,m.status(Capability.FIREWALL_IPV4,Path.WIFI,context,200));
    }
    @Test public void proofsExpireAndContextChangesInvalidate() {
        CapabilityMatrix m = new CapabilityMatrix(); m.recordReviewed(e(Capability.BOOT_FAIL_CLOSED,Path.USB,Status.HARDWARE_VERIFIED));
        for (long t : new long[]{99,1000,1001}) assertEquals(Status.UNKNOWN,m.status(Capability.BOOT_FAIL_CLOSED,Path.USB,context,t));
        assertEquals(Status.UNKNOWN,m.status(Capability.BOOT_FAIL_CLOSED,Path.USB,NetworkPlan.hash("new kernel"),200));
    }
    @Test public void everyEnvironmentDimensionInvalidatesEvidenceAndMissingFieldsBlock() {
        Map<QualificationContext.Dimension,String> fields = new EnumMap<>(QualificationContext.Dimension.class);
        for (QualificationContext.Dimension d : QualificationContext.Dimension.values()) fields.put(d,NetworkPlan.hash("baseline-"+d));
        QualificationContext baseline = new QualificationContext(fields); assertTrue(baseline.complete());
        for (QualificationContext.Dimension d : QualificationContext.Dimension.values()) {
            Map<QualificationContext.Dimension,String> changed = new EnumMap<>(fields); changed.put(d,NetworkPlan.hash("changed"));
            assertNotEquals(baseline.digest(),new QualificationContext(changed).digest()); changed.remove(d); assertFalse(new QualificationContext(changed).complete());
        }
    }
    @Test public void phoneWifiProofDoesNotQualifyMobile() {
        CapabilityMatrix m = new CapabilityMatrix(); m.recordReviewed(e(Capability.PHONE_IPV4_VPN,Path.PHONE_WIFI,Status.HARDWARE_VERIFIED));
        assertEquals(Status.UNKNOWN,m.status(Capability.PHONE_IPV4_VPN,Path.PHONE_MOBILE,context,200));
    }
    @Test public void offloadAndTunPresenceAreNotProtection() {
        assertEquals(OffloadAssessment.State.UNKNOWN, OffloadAssessment.assess(true,false,false,false,Status.UNKNOWN));
        assertEquals(OffloadAssessment.State.REQUIRES_SPECIFIC_HANDLING, OffloadAssessment.assess(true,true,false,false,Status.HARDWARE_OBSERVED));
        assertEquals(OffloadAssessment.State.INCOMPATIBLE, OffloadAssessment.assess(true,true,true,true,Status.HARDWARE_VERIFIED));
        assertEquals(Status.HARDWARE_OBSERVED,TunQualification.assess(true,true,true,true,true,true,false,false));
        assertEquals(Status.UNKNOWN,TunQualification.assess(false,true,true,true,true,true,true,true));
    }
}
