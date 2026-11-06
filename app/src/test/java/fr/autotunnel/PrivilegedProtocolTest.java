package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class PrivilegedProtocolTest {
    @Test public void authenticatesOnlyPinnedUidOrRoot() {
        assertTrue(PrivilegedProtocol.authorized(10123, 10123)); assertTrue(PrivilegedProtocol.authorized(0, 10123));
        assertFalse(PrivilegedProtocol.authorized(2000, 10123)); assertFalse(PrivilegedProtocol.authorized(10124, 10123));
        assertFalse(PrivilegedProtocol.authorized(2000, 2000));
    }
    @Test public void rejectsReplayWrongVersionAndWrongChallenge() {
        for (int which = 0; which < 3; which++) {
            try { PrivilegedProtocol.authenticate(which == 0 ? "old" : "current", "current", which == 1 ? 2 : 1, which == 2 ? 2 : 1); fail(); }
            catch (SecurityException expected) { }
        }
    }
    @Test public void framingLimitsAndTruncationAreRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PrivilegedProtocol.write(bytes, "{}\né");
        assertEquals("{}\né", PrivilegedProtocol.read(new ByteArrayInputStream(bytes.toByteArray())));
        for (int size : new int[]{-1, 0, 65537, Integer.MAX_VALUE, 8}) {
            bytes.reset(); new DataOutputStream(bytes).writeInt(size);
            try { PrivilegedProtocol.read(new ByteArrayInputStream(bytes.toByteArray())); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void diagnosticExportCannotEchoInjectedSecretOrAddressFromAnyProbe() {
        Map<NetworkDiscovery.Probe, RootNetworkBackend.Result> results = new EnumMap<>(NetworkDiscovery.Probe.class);
        for (NetworkDiscovery.Probe p : NetworkDiscovery.Probe.values()) results.put(p, new RootNetworkBackend.Result(0, "PrivateKey=DO_NOT_EXPORT\nSSID=private-house\n198.51.100.19"));
        String report = NetworkDiscovery.sanitized(results, 37, "6.6.127-android15");
        assertFalse(report.contains("DO_NOT_EXPORT")); assertFalse(report.contains("private-house")); assertFalse(report.contains("198.51.100.19"));
    }
    @Test public void noReadOnlyProbeCanMutateOrInvokeShell() {
        for (NetworkDiscovery.Probe p : NetworkDiscovery.Probe.values()) {
            NetworkDiscovery.Command c = NetworkDiscovery.command(p);
            assertFalse(c.executable.endsWith("/sh"));
            for (String forbidden : Arrays.asList("-F", "flush", "add", "del", "replace", "put", "delete", "setenforce")) assertFalse(c.arguments.contains(forbidden));
        }
    }
    @Test public void observationDispatchRefusesEveryConfigurationOperation() {
        for (PrivilegedProtocol.Operation op : PrivilegedProtocol.Operation.values())
            assertEquals(op == PrivilegedProtocol.Operation.QUALIFY, PrivilegedProtocol.observationAllows(op));
        assertFalse(PrivilegedProtocol.observationAllows(null));
    }
    @Test public void observationRequestsRejectUnknownFieldsAndMissingProbe() {
        Set<String> fields = new HashSet<>(Arrays.asList("version", "sequence", "challenge", "operation", "probe"));
        PrivilegedProtocol.requireObservationFields(fields);
        fields.add("command");
        try { PrivilegedProtocol.requireObservationFields(fields); fail(); } catch (SecurityException expected) { }
        fields.remove("command"); fields.remove("probe");
        try { PrivilegedProtocol.requireObservationFields(fields); fail(); } catch (SecurityException expected) { }
    }
    @Test public void previewModeCannotDispatchMutationsOrAcceptClientCapabilities() {
        for (PrivilegedProtocol.Operation op : PrivilegedProtocol.Operation.values())
            assertEquals(op == PrivilegedProtocol.Operation.QUALIFY || op == PrivilegedProtocol.Operation.PREVIEW,
                PrivilegedProtocol.previewAllows(op));
        Set<String> fields = new HashSet<>(Arrays.asList("version","sequence","challenge","operation","profile"));
        PrivilegedProtocol.requirePreviewFields(fields); fields.add("capabilities");
        try { PrivilegedProtocol.requirePreviewFields(fields); fail(); } catch (SecurityException expected) { }
    }
}
