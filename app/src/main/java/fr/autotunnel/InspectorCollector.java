package io.github.syldegil.autotunnel;

import android.content.Context;
import android.net.*;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.NetworkInterface;
import java.util.*;

/** One public/read-only sample. Activity owns scheduling; no persistent service or root process. */
final class InspectorCollector {
    static final class Sample {
        final long elapsed = SystemClock.elapsedRealtime();
        final long at = System.currentTimeMillis();
        final List<InspectorData.Connection> connections = new ArrayList<>();
        final Map<String, long[]> counters = new TreeMap<>();
        final Map<String, String> counterSources = new TreeMap<>();
        final Map<String, Integer> indices = new TreeMap<>();
        final List<String[]> routeClaims = new ArrayList<>();
        final Map<String, String> types = new TreeMap<>();
        final Map<String, String> interfaceDetails = new TreeMap<>();
        long durationMillis, cpuMillis;
        boolean networkKnown,activeNetworkPresent,activeValidated;
        String activeRole="",activeInterface="";
        String network = "", availability = "", contextHash, source = "API/procfs application";
        Map<String, Long> families;
        String graph(InspectorData.Connection c) {
            return "CONFIGURÉ : profil dans l’éditeur (non appliqué)\n"
                + "PARTIELLEMENT OBSERVÉ : socket UID=" + c.uid + "\n" + c.label()
                + "\nApplications / client ? ↔ [interface non attribuée] ↔ [pare-feu non attribué] ↔ [WireGuard ou direct inconnu] ↔ destination"
                + "\nCALCULÉ (candidats Android, pas décision RPDB) : " + candidates(c.destination)
                + "\nAttribution fwmark/UID/retour indisponible ; aucun candidat n’est un trajet démontré"
                + "\nCompteurs par socket : indisponibles (files procfs ≠ octets transférés)"
                + "\nBLOQUÉ : non observé ; une intention BLOCK ne prouve pas un rejet"
                + "\nVÉRIFIÉ : NON QUALIFIÉ. Cette connexion ne démontre ni sortie Freebox ni absence de fuite.";
        }
        String candidates(String destination) {
            List<String> candidates = new ArrayList<>();
            for (String[] claim : routeClaims) try {
                if (new IpPrefix(claim[0]).contains(new IpPrefix(destination + (destination.contains(":") ? "/128" : "/32"))))
                    candidates.add(claim[0] + " via " + claim[1] + " (" + claim[2] + ")");
            } catch (IllegalArgumentException ignored) { }
            return candidates.isEmpty() ? "inconnus" : String.join(" ; ", candidates);
        }
    }
    @SuppressWarnings("deprecation")
    static Sample sample(Context context) {
        Sample sample = new Sample(); StringBuilder network = new StringBuilder();
        long cpuStart = android.os.Debug.threadCpuTimeNanos();
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network active=cm.getActiveNetwork();sample.networkKnown=true;sample.activeNetworkPresent=active!=null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n); LinkProperties lp = cm.getLinkProperties(n);
                if (caps == null || lp == null) continue;
                String role = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN propriétaire non attribué" : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile (5G non attestée)" : "Autre";
                if(n.equals(active)){sample.activeRole=role;sample.activeInterface=lp.getInterfaceName()==null?"":lp.getInterfaceName();sample.activeValidated=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
                if (lp.getInterfaceName() != null) sample.types.put(lp.getInterfaceName(), role);
                if(lp.getInterfaceName()!=null)sample.interfaceDetails.put(lp.getInterfaceName(),role+" · réseau actif="+n.equals(cm.getActiveNetwork())
                    +" · Internet validé="+caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)+"\nIPv4/IPv6 "+lp.getLinkAddresses()+"\nRoutes Android déclarées "+lp.getRoutes()+"\nDNS "+lp.getDnsServers());
                network.append(role).append(" / ").append(lp.getInterfaceName()).append("\nAdresses ").append(lp.getLinkAddresses())
                    .append("\nRoutes Android ").append(lp.getRoutes()).append("\nDNS ").append(lp.getDnsServers()).append('\n');
                for (RouteInfo route : lp.getRoutes()) sample.routeClaims.add(new String[]{route.getDestination().toString(), String.valueOf(lp.getInterfaceName()), role});
            }
        } catch (Exception e) { sample.networkKnown=false; network.append("API réseau indisponible : ").append(failure(e)).append('\n'); }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement(); sample.indices.put(n.getName(), n.getIndex());
                sample.interfaceDetails.put(n.getName(),sample.interfaceDetails.getOrDefault(n.getName(),"Rôle non attribué")+"\nifindex="+n.getIndex()+" · "+(n.isUp()?"UP":"DOWN")+" · adresses="+n.getInterfaceAddresses());
                network.append("Interface ").append(n.getName()).append(" ifindex=").append(n.getIndex())
                    .append(" état=").append(n.isUp() ? "UP" : "DOWN").append(" type=")
                    .append(sample.types.getOrDefault(n.getName(), n.isLoopback() ? "loopback" : "inconnu"))
                    .append(" adresses=").append(n.getInterfaceAddresses()).append('\n');
            }
        } catch (Exception e) { sample.availability += "Inventaire interfaces : " + failure(e) + "\n"; }
        for (String file : Arrays.asList("tcp", "tcp6", "udp", "udp6")) {
            try {
                List<InspectorData.Connection> rows = InspectorData.connections(read("/proc/net/" + file), file.endsWith("6"), file.startsWith("udp"));
                sample.connections.addAll(rows);
                if (rows.size() == 128) sample.availability += file + " : limite 128 lignes atteinte, inventaire potentiellement tronqué\n";
            }
            catch (Exception e) { sample.availability += file + ": " + failure(e) + " ; helper authentifié requis pour cette source\n"; }
        }
        try {
            sample.counters.putAll(InspectorData.interfaces(read("/proc/net/dev")));
            for (String name : sample.counters.keySet()) sample.counterSources.put(name, "procfs application");
        } catch (Exception e) { sample.availability += "Compteurs procfs : " + failure(e) + "\n"; }
        // API31 exposes per-interface counters without requesting root or changing procfs permissions.
        if (Build.VERSION.SDK_INT >= 31) {
            Set<String> names = new TreeSet<>(sample.indices.keySet()); names.addAll(sample.types.keySet());
            for (String name : names) if (!sample.counters.containsKey(name)) try {
                long rx = TrafficStats.getRxBytes(name), tx = TrafficStats.getTxBytes(name);
                if (InspectorData.validCounters(rx, tx)) {
                    sample.counters.put(name, new long[]{rx, tx}); sample.counterSources.put(name, "TrafficStats API31+");
                } else sample.availability += name + " : TrafficStats UNSUPPORTED (pas zéro)\n";
            } catch (RuntimeException e) { sample.availability += name + " : TrafficStats " + failure(e) + "\n"; }
        }
        long mobileRx=TrafficStats.getMobileRxBytes(),mobileTx=TrafficStats.getMobileTxBytes();
        if(InspectorData.validCounters(mobileRx,mobileTx)){sample.counters.put("@mobile",new long[]{mobileRx,mobileTx});sample.indices.put("@mobile",-2);sample.types.put("@mobile","Mobile agrégé");sample.counterSources.put("@mobile","TrafficStats mobile aggregate");}
        if (sample.counters.isEmpty()) sample.availability += "Compteurs par interface indisponibles\n";
        String v4 = "", v6 = "";
        try { v4 = read("/proc/net/netstat"); } catch (Exception e) { sample.availability += "Compteurs IPv4 : " + failure(e) + "\n"; }
        try { v6 = read("/proc/net/snmp6"); } catch (Exception e) { sample.availability += "Compteurs IPv6 : " + failure(e) + "\n"; }
        sample.families = InspectorData.familyOctets(v4, v6);
        sample.network = network.toString();
        sample.contextHash = NetworkPlan.hash(Build.FINGERPRINT + "|" + System.getProperty("os.version") + "|GoBackend-1.0.20260102|"
            + sample.network + sample.indices + sample.counterSources + "|runtime-filter-offload-binding-UNKNOWN");
        sample.durationMillis = SystemClock.elapsedRealtime() - sample.elapsed;
        sample.cpuMillis = (android.os.Debug.threadCpuTimeNanos() - cpuStart) / 1000000;
        return sample;
    }
    private static String failure(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof android.system.ErrnoException) {
                int errno = ((android.system.ErrnoException) cause).errno;
                return "errno=" + errno + " " + android.system.Os.strerror(errno);
            }
        // Exception messages may include paths or data. Report the category without dumping them.
        return exception.getClass().getSimpleName();
    }
    static String read(String path) throws IOException {
        try (InputStream in = new FileInputStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Collection stopped");
                if (out.size() + n > 131072) throw new IOException("Observation truncated"); out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
