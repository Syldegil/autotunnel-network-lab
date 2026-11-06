package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NetworkDiscoveryTest {
    private final String links = "2: wlan0: <UP> mtu 1500\n11: ncm7: <UP> mtu 1500\n30: tun9: <UP> mtu 1280\n";
    private final String addresses = "2: wlan0    inet 198.18.1.1/24 brd 198.18.1.255 scope global wlan0\n11: ncm7    inet 198.18.2.1/24 brd 198.18.2.255 scope global ncm7\n";
    private String dump(String state) {
        return "  tetherableWifiRegexs: [wlan\\d]\n  tetherableUsbRegexs: [rndis\\d]\n  tetherableNcmRegexs: [ncm\\d]\n  Tether state:\n" + state + "\n  Hardware offload:\n  Log:\n wlan0 - TetheredState - lastError = 0\n";
    }
    @Test public void namesAndPrivateAddressesAloneDoNotIdentifyVpnOrTethering() {
        for (NetworkSnapshot.Link l : NetworkDiscovery.links(links, addresses, "", Collections.emptyMap())) assertEquals(NetworkSnapshot.Role.OTHER, l.role);
    }
    @Test public void usesActiveIpServerAndAndroidRegexNotHardcodedNames() {
        List<NetworkSnapshot.Link> initial = NetworkDiscovery.links(links, addresses, "", Collections.emptyMap());
        Map<String, NetworkSnapshot.Role> roles = NetworkDiscovery.tetherRoles(dump("    ncm7 - TetheredState - lastError = 0\n    wlan0 - AvailableState - lastError = 0"), initial);
        assertEquals(NetworkSnapshot.Role.USB_CLIENTS, roles.get("ncm7")); assertFalse(roles.containsKey("wlan0"));
    }
    @Test public void ambiguousFormatAndTetherErrorsAreRefused() {
        List<NetworkSnapshot.Link> initial = NetworkDiscovery.links(links, addresses, "", Collections.emptyMap());
        for (String dump : Arrays.asList("unknown", dump("ncm7 - TetheredState - lastError = 4"), dump("eth9 - TetheredState - lastError = 0"))) {
            try { NetworkDiscovery.tetherRoles(dump, initial); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void linkLocalDoesNotBecomeVpnIpv6Evidence() {
        List<NetworkSnapshot.Link> result = NetworkDiscovery.links(links, addresses,
            "30: tun9    inet6 fe80::1/64 scope link\n", Collections.emptyMap());
        assertTrue(result.get(2).v6.isEmpty());
    }
    @Test public void android17BpfColonAndHistoricalLogAreHandled() {
        assertEquals(Boolean.TRUE, NetworkDiscovery.bpfEnabled("  mIsBpfEnabled: true\n  Log:\n  mIsBpfEnabled=false\n"));
        assertEquals(Boolean.FALSE, NetworkDiscovery.bpfEnabled("mIsBpfEnabled=false\n"));
        assertNull(NetworkDiscovery.bpfEnabled("  Log:\n mIsBpfEnabled: true\n"));
        assertNull(NetworkDiscovery.bpfEnabled("mIsBpfEnabled=true\nmIsBpfEnabled: false\n"));
    }
    @Test public void deniedLinkDumpHasOnlyPartialAddressFallback() {
        String text = "44: tun0    inet 198.18.0.1/32 scope global tun0\\       valid_lft forever preferred_lft forever\n";
        List<NetworkSnapshot.Link> links = NetworkDiscovery.addressLinks(text, "");
        assertEquals(1, links.size()); assertEquals(44, links.get(0).ifindex);
        assertEquals(NetworkSnapshot.Role.OTHER, links.get(0).role);
        assertEquals("198.18.0.1/32", links.get(0).v4);
        assertTrue(NetworkDiscovery.addressLinks("Permission denied", "").isEmpty());
    }
}
