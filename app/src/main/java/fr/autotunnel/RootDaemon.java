package io.github.syldegil.autotunnel;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.util.*;

/** app_process entry point for the inert KernelSU package. No Android component can start it implicitly. */
public final class RootDaemon {
    public static final String SOCKET = "autotunnel.ds014.v1";
    private static final String DIRECTORY = "/data/adb/autotunnel-netd";
    // A UI preference, IPC boolean, root permission or successful build cannot lift this qualification lock.
    public static final boolean HARDWARE_MUTATIONS_QUALIFIED = false;
    private final EnumMap<NetworkDiscovery.Probe, RootNetworkBackend.Result> probes = new EnumMap<>(NetworkDiscovery.Probe.class);
    private final EngineJournal journal = new EngineJournal(new File(DIRECTORY, "transaction.json"));
    private final RootNetworkBackend backend;
    private final NetworkEngine engine;
    private final CapabilityMatrix qualifications = new CapabilityMatrix();
    private RootDaemon() {
        backend = new RootNetworkBackend(new DeviceCommandRunner(), this::observe, new RootNetworkBackend.Qualification() {
            public boolean enabled() { return HARDWARE_MUTATIONS_QUALIFIED; }
            public boolean authorized(NetworkPlan p, long now) {
                // Root-reviewed records and separate experimental consent adapter not provisioned in DS014.
                QualificationContext context = new QualificationContext(Collections.emptyMap());
                return context.complete() && qualifications.permits(p.profile, CapabilityMatrix.paths(p), context.digest(), now, false);
            }
            public boolean verified(NetworkPlan p, long now) { return false; } // No hardware data-path attestation implemented.
            public void drain(NetworkPlan p) {
                throw new IllegalStateException("Phone/client conntrack isolation adapter not qualified");
            }
        }, false);
        engine = new NetworkEngine(backend, journal);
    }
    public static void main(String[] args) {
        try {
            if (Os.getuid() != 0 || args.length != 1) throw new SecurityException();
            Os.umask(0077);
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
            RootDaemon daemon = new RootDaemon();
            if (args[0].equals("--diagnostics")) { System.out.println(daemon.qualify()); return; }
            secure(new File(DIRECTORY), true);
            File lockPath = new File(DIRECTORY, "daemon.lock");
            if (lockPath.exists()) secure(lockPath, false);
            try (RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw"); FileLock lock = lockFile.getChannel().tryLock()) {
                Os.chmod(lockPath.toString(), 0600);
                if (lock == null) throw new IOException("Already running");
                if (args[0].equals("--recovery-status")) {
                    if (!daemon.journal.exists()) System.out.println("EMPTY");
                    else { secure(new File(DIRECTORY, "transaction.json"), false); System.out.println(daemon.journal.read().getString("phase").equals("RESTORED") ? "RESTORED" : "PENDING_OR_UNKNOWN"); }
                    return;
                }
                if (args[0].equals("--restore-owned-confirm-direct")) { daemon.restoreFromJournal(); return; }
                if ((args[0].equals("--close-owned") || args[0].equals("--emergency-block"))) { daemon.resume(); daemon.engine.event(NetworkEngine.Event.SERVICE_DIED, System.currentTimeMillis()); return; }
                if (!args[0].equals("--serve") && !args[0].equals("--serve-observe") && !args[0].equals("--serve-preview")) throw new IllegalArgumentException();
                File config = new File(DIRECTORY, "client.json"); secure(config, false);
                JSONObject client = new JSONObject(readLimited(config, 4096));
                int uid = client.getInt("uid"); String certificate = client.getString("certificateSha256");
                ClientIdentity pin = new ClientIdentity(uid, client.getString("apkSha256"), certificate);
                // Never resume/open automatically after restart, even if a checkpoint exists.
                try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
                    while (true) {
                        try (LocalSocket socket = server.accept()) {
                            socket.setSoTimeout(10000);
                            int peer = socket.getPeerCredentials().getUid();
                            if (!PrivilegedProtocol.authorized(peer, uid) || peer != 0 && !InstalledClientVerifier.verify(peer, pin)) continue;
                            String challenge = PrivilegedProtocol.challenge();
                            PrivilegedProtocol.write(socket.getOutputStream(), new JSONObject().put("version", 1).put("challenge", challenge).toString());
                            JSONObject request = new JSONObject(PrivilegedProtocol.read(socket.getInputStream()));
                            PrivilegedProtocol.authenticate(request.getString("challenge"), challenge, request.getLong("sequence"), request.getInt("version"));
                            if (peer != 0 && !InstalledClientVerifier.verify(peer, pin)) continue;
                            JSONObject response = daemon.handle(request, args[0].equals("--serve-preview"));
                            PrivilegedProtocol.write(socket.getOutputStream(), response.toString());
                        } catch (Exception ignored) { /* No request/profile/error text in logcat or stdout. Socket closes fail-safe. */ }
                    }
                }
            }
        } catch (Exception ignored) { System.err.println("autotunnel-netd: refused or unavailable; no protection claimed"); System.exit(2); }
    }
    private JSONObject handle(JSONObject request, boolean previewMode) throws Exception {
        PrivilegedProtocol.Operation operation = PrivilegedProtocol.Operation.valueOf(request.getString("operation"));
        // Observation server has no dispatch path to preview/apply/close/restore, even for UID0.
        if (!(previewMode ? PrivilegedProtocol.previewAllows(operation) : PrivilegedProtocol.observationAllows(operation))) return locked();
        Set<String> fields = new HashSet<>();
        Iterator<String> keys = request.keys(); while (keys.hasNext()) fields.add(keys.next());
        if (operation == PrivilegedProtocol.Operation.PREVIEW) {
            PrivilegedProtocol.requirePreviewFields(fields);
            try {
                NetworkPlan plan = engine.preview(DraftStore.decode(request.getJSONObject("profile")));
                return bounded(new JSONObject().put("ok", true).put("digest", plan.digest)
                    .put("preview", plan.preview()).put("hardwareEnabled", false)
                    .put("blockers", new org.json.JSONArray(plan.blockers(plan.snapshot, System.currentTimeMillis()))));
            } catch (IllegalArgumentException unavailable) {
                return new JSONObject().put("ok", false).put("state", "UNSUPPORTED")
                    .put("reason", "Interfaces ou liaison WireGuard non qualifiées").put("hardwareEnabled", false);
            }
        }
        PrivilegedProtocol.requireObservationFields(fields);
        if (operation == PrivilegedProtocol.Operation.QUALIFY) {
            String report;
            if (request.has("probe")) {
                NetworkDiscovery.Probe p = NetworkDiscovery.Probe.valueOf(request.getString("probe"));
                if (RootInspection.supports(p)) {
                    // One fixed procfs read only; no shell, extra probe, automatic provisioning or capability inference.
                    RootInspection.Snapshot snapshot = RootInspection.read(p);
                    probes.clear();
                    probes.put(p, new RootNetworkBackend.Result(snapshot.status.equals("ERROR") ? 1 : 0, ""));
                    JSONObject inspection = RootInspection.encode(snapshot);
                    org.json.JSONArray connections = new org.json.JSONArray();
                    for (InspectorData.Connection c : snapshot.connections) connections.put(c.label());
                    return bounded(new JSONObject().put("ok", true)
                        .put("report", NetworkDiscovery.sanitized(probes, Build.VERSION.SDK_INT, System.getProperty("os.version", "unknown")))
                        .put("local", snapshot.describe()).put("connections", connections).put("inspection", inspection)
                        .put("context", NetworkPlan.hash(Build.FINGERPRINT + System.getProperty("os.version") + inspection.toString()))
                        .put("at", snapshot.at).put("hardwareEnabled", false));
                }
                probes.clear(); probe(p);
                if (p == NetworkDiscovery.Probe.TETHERING) {
                    TetheringStatus state = TetheringStatus.parse(output(p));
                    return bounded(new JSONObject().put("ok", true).put("hardwareEnabled", false)
                        .put("probe", p.name()).put("at", System.currentTimeMillis()).put("local", state.describe()));
                }
                report = NetworkDiscovery.sanitized(probes, Build.VERSION.SDK_INT, System.getProperty("os.version", "unknown"));
            } else throw new IllegalArgumentException("One explicitly selected probe required");
            org.json.JSONArray connections = new org.json.JSONArray();
            for (NetworkDiscovery.Probe p : Arrays.asList(NetworkDiscovery.Probe.SOCKETS4, NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6))
                for (InspectorData.Connection c : InspectorData.connections(output(p), p.name().endsWith("6"), p.name().startsWith("UDP"))) {
                    if (connections.length() < 64) connections.put(c.label());
                }
            return bounded(new JSONObject().put("ok", true).put("report", report).put("local", localDetails()).put("connections", connections)
                .put("context", NetworkPlan.hash(Build.FINGERPRINT + System.getProperty("os.version") + report + probes.values().stream()
                    .map(r -> NetworkPlan.hash(r.output)).reduce("", String::concat)))
                .put("at", System.currentTimeMillis()).put("hardwareEnabled", false));
        }
        if (operation == PrivilegedProtocol.Operation.CLOSE) {
            return result(engine.event(NetworkEngine.Event.SERVICE_DIED, System.currentTimeMillis()));
        }
        if (operation == PrivilegedProtocol.Operation.RESTORE) {
            if (!HARDWARE_MUTATIONS_QUALIFIED) return locked();
            return result(engine.restore(request.optBoolean("confirmDirect", false)));
        }
        if (operation == PrivilegedProtocol.Operation.APPLY && !HARDWARE_MUTATIONS_QUALIFIED) return locked();
        try {
            RoutingProfile profile = DraftStore.decode(request.getJSONObject("profile"));
            NetworkPlan plan = engine.preview(profile); // root observation only; request cannot supply capabilities or commands
            if (operation == PrivilegedProtocol.Operation.PREVIEW)
                return new JSONObject().put("ok", true).put("digest", plan.digest).put("preview", plan.preview());
            return result(engine.apply(plan, request.optString("confirmedDigest", ""), System.currentTimeMillis()));
        } catch (IllegalArgumentException ignored) {
            return new JSONObject().put("ok", false).put("state", "UNSUPPORTED").put("reason", "Interface/routage/liaison WireGuard non qualifiés");
        }
    }
    private static JSONObject bounded(JSONObject response) throws Exception {
        if (response.toString().getBytes(StandardCharsets.UTF_8).length > PrivilegedProtocol.LIMIT) {
            // Preserve structured data and exact errors; redundant legacy presentation may be omitted explicitly.
            response.put("local", "Affichage legacy tronqué : consulter le relevé structuré borné.");
            response.put("connections", new org.json.JSONArray());
        }
        if (response.toString().getBytes(StandardCharsets.UTF_8).length > PrivilegedProtocol.LIMIT) throw new IOException("Response exceeds 65536 bytes");
        return response;
    }
    private JSONObject result(NetworkEngine.State state) throws Exception {
        return new JSONObject().put("ok", state == NetworkEngine.State.DIRECT || state == NetworkEngine.State.VPN_PROTECTED)
            .put("state", state.name()).put("reason", engine.error());
    }
    private JSONObject locked() throws Exception { return new JSONObject().put("ok", false).put("state", "UNSUPPORTED").put("reason", "DS013 : mutations matérielles verrouillées"); }
    private String qualify() {
        probes.clear();
        // nft first; iptables presence/readability never implies hook/offload effectiveness.
        List<NetworkDiscovery.Probe> order = new ArrayList<>(Arrays.asList(NetworkDiscovery.Probe.NFT_VERSION, NetworkDiscovery.Probe.NFT_TABLES));
        for (NetworkDiscovery.Probe p : NetworkDiscovery.Probe.values()) if (!order.contains(p)) order.add(p);
        for (NetworkDiscovery.Probe p : order) probe(p);
        return NetworkDiscovery.sanitized(probes, Build.VERSION.SDK_INT, System.getProperty("os.version", "unknown"))
            + "\nKernelSU : contexte/provisionnement à qualifier ; UID0 seul ne prouve pas KernelSU"
            + "\nBPF épinglé visible=" + new File("/sys/fs/bpf/tethering").isDirectory()
            + "\nWireGuard noyau visible=" + new File("/sys/module/wireguard").isDirectory();
    }
    private void probe(NetworkDiscovery.Probe p) {
        try {
            NetworkDiscovery.Command c = NetworkDiscovery.command(p);
            probes.put(p, DeviceCommandRunner.command(c.executable, c.arguments));
        } catch (Exception ignored) { probes.put(p, new RootNetworkBackend.Result(127, "")); }
    }
    private String output(NetworkDiscovery.Probe p) {
        RootNetworkBackend.Result r = probes.get(p); return r != null && r.code == 0 ? r.output : "";
    }
    private String localDetails() {
        StringBuilder out = new StringBuilder("LECTURE LOCALE · non exportée\n");
        try {
            List<NetworkSnapshot.Link> initial = NetworkDiscovery.links(output(NetworkDiscovery.Probe.LINKS), output(NetworkDiscovery.Probe.ADDRESSES4), output(NetworkDiscovery.Probe.ADDRESSES6), Collections.emptyMap());
            Map<String, NetworkSnapshot.Role> roles;
            try { roles = NetworkDiscovery.tetherRoles(output(NetworkDiscovery.Probe.TETHERING), initial); }
            catch (IllegalArgumentException e) { roles = Collections.emptyMap(); out.append("Classification tethering non disponible\n"); }
            for (NetworkSnapshot.Link l : NetworkDiscovery.links(output(NetworkDiscovery.Probe.LINKS), output(NetworkDiscovery.Probe.ADDRESSES4), output(NetworkDiscovery.Probe.ADDRESSES6), roles))
                out.append(l.name).append(" ifindex=").append(l.ifindex).append(" ").append(l.role).append(" IPv4=").append(l.v4).append(" IPv6=").append(l.v6).append('\n');
        } catch (Exception ignored) { out.append("Interfaces ambiguës\n"); }
        for (NetworkDiscovery.Probe p : Arrays.asList(NetworkDiscovery.Probe.COUNTERS4, NetworkDiscovery.Probe.COUNTERS6))
            for (Map.Entry<String, long[]> c : RuleCounters.parse(output(p)).entrySet())
                out.append(c.getKey()).append(" paquets=").append(c.getValue()[0]).append(" octets=").append(c.getValue()[1]).append(" (ne prouve pas le passage des flux accélérés)\n");
        out.append("Indices BPF/HAL : ").append(Boolean.TRUE.equals(NetworkDiscovery.bpfEnabled(output(NetworkDiscovery.Probe.TETHERING))) ? "BPF annoncé actif (aucun chemin accéléré attesté)" : "BPF inactif ou inconnu, protection non attestée")
            .append(" ; ").append(output(NetworkDiscovery.Probe.TETHERING).contains("Offload HALs not started") ? "HAL pas démarré au relevé, pas une désactivation prouvée" : "HAL inconnu");
        out.append("\nOffload : ").append(OffloadAssessment.assess(probes.containsKey(NetworkDiscovery.Probe.TETHERING),
            Boolean.TRUE.equals(NetworkDiscovery.bpfEnabled(output(NetworkDiscovery.Probe.TETHERING))), false, false, CapabilityMatrix.Status.UNKNOWN));
        if (output(NetworkDiscovery.Probe.LINKS).isEmpty()) {
            out.append("\nRTM_GETLINK absent/refusé : inventaire partiel des adresses, état/type inconnus\n");
            for (NetworkSnapshot.Link l : NetworkDiscovery.addressLinks(output(NetworkDiscovery.Probe.ADDRESSES4), output(NetworkDiscovery.Probe.ADDRESSES6)))
                out.append(l.name).append(" ifindex=").append(l.ifindex).append(" IPv4=").append(l.v4).append(" IPv6=").append(l.v6).append('\n');
        }
        for (NetworkDiscovery.Probe p : Arrays.asList(NetworkDiscovery.Probe.ROUTES4, NetworkDiscovery.Probe.ROUTES6,
            NetworkDiscovery.Probe.RULES4, NetworkDiscovery.Probe.RULES6, NetworkDiscovery.Probe.TUN_DEVICES, NetworkDiscovery.Probe.KSU_VERSION,
            NetworkDiscovery.Probe.FORWARD4, NetworkDiscovery.Probe.FORWARD6, NetworkDiscovery.Probe.CONNTRACK_COUNT)) {
            String value = output(p);
            if (!value.isEmpty()) out.append('\n').append(p).append(" (local seulement)\n").append(value.substring(0, Math.min(4096, value.length())));
        }
        for (NetworkDiscovery.Probe p : Arrays.asList(NetworkDiscovery.Probe.SOCKETS4, NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6))
            for (InspectorData.Connection c : InspectorData.connections(output(p), p.name().endsWith("6"), p.name().startsWith("UDP")))
                out.append('\n').append(c.label()).append(" · UID=").append(c.uid).append(" parcours NON ATTRIBUÉ");
        for (Map.Entry<String,long[]> e : InspectorData.interfaces(output(NetworkDiscovery.Probe.LINK_COUNTERS)).entrySet())
            out.append('\n').append(e.getKey()).append(" RX/TX=").append(e.getValue()[0]).append('/').append(e.getValue()[1]);
        out.append("\nCompteurs famille globaux : ").append(InspectorData.familyOctets(output(NetworkDiscovery.Probe.IP_STATS4), output(NetworkDiscovery.Probe.IP_STATS6)));
        if (out.length() > 24000) return out.substring(0, 24000) + "\nAffichage tronqué ; observation non exhaustive";
        return out.toString();
    }
    private NetworkSnapshot observe() throws Exception {
        qualify();
        EnumSet<NetworkSnapshot.Capability> capabilities = EnumSet.noneOf(NetworkSnapshot.Capability.class);
        if (output(NetworkDiscovery.Probe.ID).trim().equals("0")) capabilities.add(NetworkSnapshot.Capability.ROOT);
        if (output(NetworkDiscovery.Probe.SELINUX).trim().equals("Enforcing")) capabilities.add(NetworkSnapshot.Capability.ENFORCING);
        for (NetworkDiscovery.Probe p : Arrays.asList(NetworkDiscovery.Probe.FILTER4, NetworkDiscovery.Probe.FILTER6, NetworkDiscovery.Probe.NFT_TABLES)) {
            RootNetworkBackend.Result r = probes.get(p);
            if (r != null && r.code == 0) capabilities.add(p == NetworkDiscovery.Probe.FILTER4 ? NetworkSnapshot.Capability.IPTABLES4
                : p == NetworkDiscovery.Probe.FILTER6 ? NetworkSnapshot.Capability.IPTABLES6 : NetworkSnapshot.Capability.NFT);
        }
        String boot = readLimited(new File("/proc/sys/kernel/random/boot_id"), 128).trim();
        // No inference from interface names, dumpsys substring or an UP tun. Trusted role binding remains a qualification adapter.
        List<NetworkSnapshot.Link> links = NetworkDiscovery.links(output(NetworkDiscovery.Probe.LINKS), output(NetworkDiscovery.Probe.ADDRESSES4),
            output(NetworkDiscovery.Probe.ADDRESSES6), Collections.emptyMap());
        try {
            Map<String, NetworkSnapshot.Role> roles = NetworkDiscovery.tetherRoles(output(NetworkDiscovery.Probe.TETHERING), links);
            links = NetworkDiscovery.links(output(NetworkDiscovery.Probe.LINKS), output(NetworkDiscovery.Probe.ADDRESSES4), output(NetworkDiscovery.Probe.ADDRESSES6), roles);
        } catch (IllegalArgumentException ignored) { /* Unknown OEM format: retain unclassified interfaces. */ }
        String generation = NetworkPlan.hash(output(NetworkDiscovery.Probe.LINKS) + output(NetworkDiscovery.Probe.ADDRESSES4)
            + output(NetworkDiscovery.Probe.ADDRESSES6));
        return new NetworkSnapshot(boot, generation, System.currentTimeMillis(), false, false, links, capabilities, "", 0, 0,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
    private void resume() throws Exception {
        if (!HARDWARE_MUTATIONS_QUALIFIED || !journal.exists()) throw new SecurityException();
        secure(new File(DIRECTORY, "transaction.json"), false);
        JSONObject record = journal.read();
        if (record.getString("phase").equals("RESTORED")) return;
        NetworkPlan plan = journal.reconstruct(record);
        Set<String> ids = RecoveryPlan.pending(plan, NetworkCodec.strings(record.getJSONArray("applied")), record.getString("phase"));
        engine.resumeOwned(plan, ids);
    }
    private void restoreFromJournal() throws Exception {
        if (!journal.exists()) { System.out.println("No owned checkpoint; no mutation performed"); return; }
        secure(new File(DIRECTORY, "transaction.json"), false);
        if (journal.read().getString("phase").equals("RESTORED")) { System.out.println("Already restored; no mutation performed"); return; }
        resume(); NetworkEngine.State result = engine.restore(true);
        if (result != NetworkEngine.State.DIRECT) throw new IOException("Recovery not verified");
        System.out.println("AutoTunnel owned resources restored; explicit direct mode");
    }
    private static void secure(File path, boolean directory) throws Exception {
        StructStat stat = Os.lstat(path.toString());
        if (stat.st_uid != 0 || (stat.st_mode & 077) != 0 || (directory ? !OsConstants.S_ISDIR(stat.st_mode) : !OsConstants.S_ISREG(stat.st_mode))
            || !path.getCanonicalPath().equals(path.getAbsolutePath())) throw new SecurityException("Unsafe state path");
    }
    private static String readLimited(File file, int limit) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[1024]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException(); out.write(b, 0, n); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
