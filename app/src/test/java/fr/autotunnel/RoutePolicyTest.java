package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;
import static io.github.syldegil.autotunnel.RoutePolicy.*;

public class RoutePolicyTest {
    private final long now = 100000;
    private Evidence evidence(TunnelState state, boolean v4, boolean v6, boolean dns, boolean guard, boolean wifi, boolean usb, long at) {
        return new Evidence(state, v4, v6, dns, guard, wifi, usb, at);
    }
    private RoutingProfile dual(Scope scope) {
        return new RoutingProfile(scope, Mode.REQUIRE_TUNNEL, V6.REQUIRE_TUNNEL,
            entries(""), entries(""), entries(""), java.util.Collections.emptyList());
    }
    @Test public void unknownNeverRequestsDirectFallback() {
        for (Scope scope : Scope.values()) for (Family family : new Family[]{Family.IPV4, Family.IPV6})
            assertEquals(Intent.BLOCK_REQUIRED, intent(dual(scope), family, null, now));
    }
    @Test public void upInterfaceAloneIsInsufficient() {
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.PHONE), Family.IPV4,
            evidence(TunnelState.UP, false, false, false, false, false, false, now), now));
    }
    @Test public void ipv4SuccessDoesNotProveIpv6() {
        Evidence e = evidence(TunnelState.UP, true, false, true, true, true, true, now);
        assertEquals(Intent.TUNNEL_REQUEST, intent(dual(Scope.ALL), Family.IPV4, e, now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.ALL), Family.IPV6, e, now));
    }
    @Test public void downUnknownAndRecoveryRequireFreshProof() {
        for (TunnelState state : TunnelState.values()) {
            Evidence e = evidence(state, true, true, true, true, true, true, now);
            assertEquals(state == TunnelState.UP ? Intent.TUNNEL_REQUEST : Intent.BLOCK_REQUIRED,
                intent(dual(Scope.ALL), Family.IPV4, e, now));
        }
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.ALL), Family.IPV4,
            evidence(TunnelState.UP, true, true, true, true, true, true, now - 15001), now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.ALL), Family.IPV4,
            evidence(TunnelState.UP, true, true, true, true, true, true, now + 1), now));
    }
    @Test public void usbAndWifiProofsCannotSubstituteForEachOther() {
        Evidence wifiOnly = evidence(TunnelState.UP, true, true, true, true, true, false, now);
        assertEquals(Intent.TUNNEL_REQUEST, intent(dual(Scope.WIFI), Family.IPV4, wifiOnly, now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.USB), Family.IPV4, wifiOnly, now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.ALL), Family.IPV4, wifiOnly, now));
        Evidence usbOnly = evidence(TunnelState.UP, true, true, true, true, false, true, now);
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.WIFI), Family.IPV6, usbOnly, now));
        assertEquals(Intent.TUNNEL_REQUEST, intent(dual(Scope.USB), Family.IPV6, usbOnly, now));
    }
    @Test public void dnsOrPersistentGuardFailureRequiresBlocking() {
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.PHONE), Family.IPV4,
            evidence(TunnelState.UP, true, true, false, true, true, true, now), now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(dual(Scope.PHONE), Family.IPV4,
            evidence(TunnelState.UP, true, true, true, false, true, true, now), now));
    }
    @Test public void explicitIpv6BlockWinsEvenWithSuccessfulIpv6Evidence() {
        assertEquals(Intent.BLOCK_REQUIRED, intent(defaults(Scope.PHONE), Family.IPV6,
            evidence(TunnelState.UP, true, true, true, true, true, true, now), now));
    }
    @Test public void directRequiresExplicitPolicyAndStillHonorsIpv6Block() {
        RoutingProfile p = new RoutingProfile(Scope.ALL, Mode.DIRECT, V6.BLOCK,
            entries(""), entries(""), entries(""), java.util.Collections.emptyList());
        assertEquals(Intent.DIRECT_REQUEST, intent(p, Family.IPV4, null, now));
        assertEquals(Intent.BLOCK_REQUIRED, intent(p, Family.IPV6, null, now));
        assertTrue(activationRefusal(p).contains("indisponible"));
    }
    @Test public void noScopeCanActivateUnqualifiedExecutor() {
        for (Scope scope : Scope.values()) assertTrue(activationRefusal(dual(scope)).contains("aucune protection"));
    }
    @Test(expected = IllegalArgumentException.class) public void neverCombineRealFamilyState() {
        intent(defaults(Scope.PHONE), Family.BOTH, null, now);
    }
}
