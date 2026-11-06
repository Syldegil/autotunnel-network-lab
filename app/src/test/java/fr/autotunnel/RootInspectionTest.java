package io.github.syldegil.autotunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure JVM parsing/bounds tests: no socket, su, Android runtime or device needed. */
public class RootInspectionTest {
    private static final String HEADER = "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
    private static String socket(boolean ipv6) {
        String address = ipv6 ? "00000000000000000000000001000000" : "0100007F";
        return "0: " + address + ":CAFE " + address + ":0035 01 00000000:00000000 00:00000000 00000000 10437 0 123\n";
    }
    @Test public void exactlySevenFixedPathsNoCallerChosenCommand() {
        int count = 0;
        for (NetworkDiscovery.Probe p : NetworkDiscovery.Probe.values()) if (RootInspection.supports(p)) {
            count++;
            assertTrue(RootInspection.path(p).startsWith("/proc/net/"));
            assertEquals(RootInspection.path(p), NetworkDiscovery.command(p).arguments.get(0));
        }
        assertEquals(7, count);
        assertThrows(IllegalArgumentException.class, () -> RootInspection.path(NetworkDiscovery.Probe.ID));
    }
    @Test public void boundedReadRejectsRatherThanParsesPartialData() throws Exception {
        byte[] exact = new byte[RootInspection.INPUT_LIMIT];
        assertEquals(exact.length, RootInspection.readBounded(new ByteArrayInputStream(exact)).length());
        IOException error = assertThrows(IOException.class, () -> RootInspection.readBounded(new ByteArrayInputStream(new byte[exact.length + 1])));
        assertTrue(error.getMessage().contains("no partial data accepted"));
        assertThrows(IllegalArgumentException.class, () -> RootInspection.parse(NetworkDiscovery.Probe.SOCKETS4, 1, "é".repeat(exact.length)));
    }
    @Test public void ioErrorRetainsExactMessageAndCause() {
        IOException error = new IOException("/proc/net/tcp: open failed: EACCES (Permission denied)", new IOException("read failed: EPERM (Operation not permitted)"));
        String rendered = RootInspection.error(error);
        assertTrue(rendered.contains(error.getMessage()));
        assertTrue(rendered.contains(error.getCause().getMessage()));
        assertTrue(RootInspection.error(new IOException("x".repeat(4000))).length() <= 2048);
    }
    @Test public void allSocketFamiliesAndProtocolsArePreserved() {
        for (NetworkDiscovery.Probe p : new NetworkDiscovery.Probe[]{ NetworkDiscovery.Probe.SOCKETS4, NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6 }) {
            RootInspection.Snapshot s = RootInspection.parse(p, 123, HEADER + socket(p.name().endsWith("6")));
            assertEquals("READ_OK", s.status); assertEquals(123, s.at); assertEquals(1, s.connections.size());
            InspectorData.Connection c = s.connections.get(0);
            assertEquals(10437, c.uid); assertEquals(51966, c.sourcePort); assertEquals(53, c.destinationPort);
            assertEquals((p.name().startsWith("UDP") ? "UDP" : "TCP") + (p.name().endsWith("6") ? "6" : "4"), c.protocol);
        }
    }
    @Test public void socketLimitAndMalformedRowsAreExplicit() {
        RootInspection.Snapshot s = RootInspection.parse(NetworkDiscovery.Probe.SOCKETS4, 1, HEADER + socket(false).repeat(65) + "invalid row\n");
        assertEquals(64, s.connections.size()); assertTrue(s.truncated); assertEquals(1, s.rejectedRows); assertEquals("PARTIAL", s.status);
        assertFalse(s.error.isEmpty()); assertTrue(s.describe().contains("aucune capacité qualifiée"));
        assertEquals("READ_OK", RootInspection.parse(NetworkDiscovery.Probe.SOCKETS4, 1, HEADER).status);
        assertEquals("PARTIAL", RootInspection.parse(NetworkDiscovery.Probe.SOCKETS4, 1, "").status);
        assertEquals("PARTIAL", RootInspection.parse(NetworkDiscovery.Probe.SOCKETS4, 1, "sl invalid header").status);
    }
    @Test public void interfaceRowsAndCountersAreBoundedAndInvalidRowsNotHidden() {
        String valid = "test0: 100 1 0 0 0 0 0 0 200 1 0 0 0 0 0 0\n";
        RootInspection.Snapshot s = RootInspection.parse(NetworkDiscovery.Probe.LINK_COUNTERS, 1, valid + "bad: 1\n");
        assertArrayEquals(new long[]{100, 200}, s.counters.get("test0")); assertEquals(1, s.rejectedRows); assertEquals("PARTIAL", s.status);
        StringBuilder rows = new StringBuilder(); for (int i = 0; i < 129; i++) rows.append(valid.replace("test0", "test" + i));
        s = RootInspection.parse(NetworkDiscovery.Probe.LINK_COUNTERS, 1, rows.toString());
        assertEquals(128, s.counters.size()); assertTrue(s.truncated); assertEquals("PARTIAL", s.status);
    }
    @Test public void familyCountersMissingMalformedAndDuplicateAreNotComplete() {
        RootInspection.Snapshot v4 = RootInspection.parse(NetworkDiscovery.Probe.IP_STATS4, 1, "IpExt: InOctets OutOctets\nIpExt: 20 30\n");
        assertEquals("READ_OK", v4.status); assertEquals(Long.valueOf(20), v4.families.get("IPv4-InOctets"));
        String v6 = "Ip6InOctets 40\nIp6OutOctets 50\n";
        assertEquals("READ_OK", RootInspection.parse(NetworkDiscovery.Probe.IP_STATS6, 1, v6).status);
        assertEquals("PARTIAL", RootInspection.parse(NetworkDiscovery.Probe.IP_STATS6, 1, v6 + "Ip6InOctets 60\n").status);
        assertEquals("PARTIAL", RootInspection.parse(NetworkDiscovery.Probe.IP_STATS4, 1, "IpExt: InOctets OutOctets\nIpExt: 20 -30\n").status);
        assertEquals("PARTIAL", RootInspection.parse(NetworkDiscovery.Probe.IP_STATS6, 1, "Ip6InOctets 9223372036854775808\n").status);
    }
    @Test public void cancelledReadStopsBeforeReturningContent() {
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class, () -> RootInspection.readBounded(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)))); }
        finally { Thread.interrupted(); }
    }
}
