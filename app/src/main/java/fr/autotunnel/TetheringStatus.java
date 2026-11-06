package io.github.syldegil.autotunnel;

import java.util.*;
import java.util.regex.*;

/** Current tethering metadata only. No route, client identity or qualification inferred from log history. */
public final class TetheringStatus {
    public final List<String> downstreams, upstreams;
    public final Boolean bpfEnabled;
    public final boolean halNotStarted;
    private TetheringStatus(List<String> down, List<String> up, Boolean bpf, boolean hal) {
        downstreams = Collections.unmodifiableList(down); upstreams = Collections.unmodifiableList(up);
        bpfEnabled = bpf; halNotStarted = hal;
    }
    public static TetheringStatus parse(String dump) {
        if (dump == null || dump.length() > 524288) throw new IllegalArgumentException("Tethering unavailable");
        String current = dump.split("(?m)^\\s{0,2}Log:\\s*$", 2)[0];
        String[] start = current.split("(?m)^  Tether state:\\s*$", -1);
        if (start.length != 2) throw new IllegalArgumentException("Unknown tethering format");
        String[] end = start[1].split("(?m)^  Hardware offload:\\s*$", -1);
        if (end.length != 2) throw new IllegalArgumentException("Unknown offload boundary");
        List<String> down = new ArrayList<>(), up = new ArrayList<>();
        boolean upstreamSeen = false;
        for (String raw : end[0].split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Upstream wanted:")) continue;
            if (line.startsWith("Current upstream interface(s):")) {
                if (upstreamSeen) throw new IllegalArgumentException("Duplicate upstream");
                upstreamSeen = true;
                String value = line.substring("Current upstream interface(s):".length()).trim();
                if (value.equals("null")) continue;
                if (!value.startsWith("[") || !value.endsWith("]")) throw new IllegalArgumentException("Unknown upstream");
                for (String name : value.substring(1,value.length()-1).split(",")) {
                    if (name.trim().isEmpty()) continue;
                    name = name.trim(); RoutingProfile.interfaceName(name);
                    if (up.contains(name)) throw new IllegalArgumentException("Duplicate interface");
                    up.add(name);
                }
                continue;
            }
            Matcher m = Pattern.compile("(\\S+) - (\\w+) - lastError = (\\d+)").matcher(line);
            if (!m.matches()) throw new IllegalArgumentException("Unknown IpServer state");
            String name = m.group(1); RoutingProfile.interfaceName(name);
            if (!m.group(3).equals("0")) throw new IllegalArgumentException("IpServer error");
            if (m.group(2).equals("TetheredState")) {
                if (down.contains(name)) throw new IllegalArgumentException("Duplicate downstream");
                down.add(name);
            } else if (!Arrays.asList("AvailableState","UnavailableState","LocalHotspotState").contains(m.group(2)))
                throw new IllegalArgumentException("Unknown IpServer state");
        }
        if (!upstreamSeen) throw new IllegalArgumentException("Upstream state missing");
        for (String name : down) if (up.contains(name)) throw new IllegalArgumentException("Looped interface roles");
        return new TetheringStatus(down, up, NetworkDiscovery.bpfEnabled(current),
            Pattern.compile("(?m)^\\s*Offload HALs not started\\s*$").matcher(current).find());
    }
    public String describe() {
        return "PARTAGE · observation Android ponctuelle\nInterfaces partagées actives : " + downstreams
            + "\nAmont déclaré par Android : " + upstreams
            + "\neBPF annoncé : " + (bpfEnabled == null ? "inconnu" : bpfEnabled)
            + "\nHAL : " + (halNotStarted ? "non démarrée au relevé" : "état à qualifier")
            + "\nClients/adresses/DNS : non mesurés par cette sonde"
            + "\nRègles AutoTunnel installées : non vérifiées"
            + "\nIPv4/IPv6 de sortie client : non mesurées"
            + "\nVPN téléphone ≠ preuve du routage hotspot. Parcours NON QUALIFIÉ.";
    }
}
