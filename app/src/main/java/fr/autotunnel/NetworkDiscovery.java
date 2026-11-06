package io.github.syldegil.autotunnel;

import java.util.*;
import java.util.regex.*;
import static io.github.syldegil.autotunnel.NetworkSnapshot.*;

/** Conservative parsers for read-only probes. Unknown OEM formats never imply a tethering role. */
public final class NetworkDiscovery {
    public enum Probe {
        ID, SELINUX, LINKS, ADDRESSES4, ADDRESSES6, ROUTES4, ROUTES6, RULES4, RULES6,
        NFT_VERSION, NFT_TABLES, IPTABLES_VERSION, IP6TABLES_VERSION, FILTER4, FILTER6,
        TETHERING, CONNECTIVITY, OFFLOAD_SETTING, TC, KSU_VERSION, SECURITY_CONTEXT, COUNTERS4, COUNTERS6,
        TUN_DEVICES, CONNTRACK_VERSION, CONNTRACK_COUNT, BPF_PROGRAMS, BPF_MAPS, FORWARD4, FORWARD6,
        BOOT_ID, BOOT_COMPLETE, MODULE_STATE, LINK_COUNTERS, SOCKETS4, SOCKETS6, UDP4, UDP6, IP_STATS4, IP_STATS6
    }
    public static final class Command {
        public final String executable;
        public final List<String> arguments;
        Command(String executable, String... arguments) { this.executable = executable; this.arguments = Arrays.asList(arguments); }
    }
    public static Command command(Probe p) {
        switch (p) {
            case ID: return new Command("/system/bin/id", "-u");
            case SELINUX: return new Command("/system/bin/getenforce");
            case KSU_VERSION: return new Command("/data/adb/ksu/bin/ksud", "--version");
            case SECURITY_CONTEXT: return new Command("/system/bin/id", "-Z");
            case TUN_DEVICES: return new Command("/system/bin/ip", "tuntap", "show");
            case CONNTRACK_VERSION: return new Command("/system/bin/conntrack", "-V");
            case CONNTRACK_COUNT: return new Command("/system/bin/cat", "/proc/sys/net/netfilter/nf_conntrack_count");
            case BPF_PROGRAMS: return new Command("/system/bin/bpftool", "prog", "show");
            case BPF_MAPS: return new Command("/system/bin/bpftool", "map", "show");
            case FORWARD4: return new Command("/system/bin/cat", "/proc/sys/net/ipv4/ip_forward");
            case FORWARD6: return new Command("/system/bin/cat", "/proc/sys/net/ipv6/conf/all/forwarding");
            case BOOT_ID: return new Command("/system/bin/cat", "/proc/sys/kernel/random/boot_id");
            case BOOT_COMPLETE: return new Command("/system/bin/getprop", "sys.boot_completed");
            case MODULE_STATE: return new Command("/system/bin/ls", "-ld", "/data/adb/modules/autotunnel-netd-ds014", "/data/adb/modules/autotunnel-netd-ds014/disable");
            case LINK_COUNTERS: return new Command("/system/bin/cat", "/proc/net/dev");
            case SOCKETS4: return new Command("/system/bin/cat", "/proc/net/tcp");
            case SOCKETS6: return new Command("/system/bin/cat", "/proc/net/tcp6");
            case UDP4: return new Command("/system/bin/cat", "/proc/net/udp");
            case UDP6: return new Command("/system/bin/cat", "/proc/net/udp6");
            case IP_STATS4: return new Command("/system/bin/cat", "/proc/net/netstat");
            case IP_STATS6: return new Command("/system/bin/cat", "/proc/net/snmp6");
            case COUNTERS4: return new Command("/system/bin/iptables-save", "-c", "-t", "filter");
            case COUNTERS6: return new Command("/system/bin/ip6tables-save", "-c", "-t", "filter");
            case LINKS: return new Command("/system/bin/ip", "-o", "link", "show");
            case ADDRESSES4: return new Command("/system/bin/ip", "-o", "-4", "address", "show");
            case ADDRESSES6: return new Command("/system/bin/ip", "-o", "-6", "address", "show");
            case ROUTES4: return new Command("/system/bin/ip", "-4", "route", "show", "table", "all");
            case ROUTES6: return new Command("/system/bin/ip", "-6", "route", "show", "table", "all");
            case RULES4: return new Command("/system/bin/ip", "-4", "rule", "show");
            case RULES6: return new Command("/system/bin/ip", "-6", "rule", "show");
            case NFT_VERSION: return new Command("/system/bin/nft", "--version");
            case NFT_TABLES: return new Command("/system/bin/nft", "list", "tables");
            case IPTABLES_VERSION: return new Command("/system/bin/iptables", "--version");
            case IP6TABLES_VERSION: return new Command("/system/bin/ip6tables", "--version");
            case FILTER4: return new Command("/system/bin/iptables", "-w", "5", "-t", "filter", "-S");
            case FILTER6: return new Command("/system/bin/ip6tables", "-w", "5", "-t", "filter", "-S");
            case TETHERING: return new Command("/system/bin/dumpsys", "tethering");
            case CONNECTIVITY: return new Command("/system/bin/dumpsys", "connectivity");
            case OFFLOAD_SETTING: return new Command("/system/bin/settings", "get", "global", "tether_offload_disabled");
            case TC: return new Command("/system/bin/tc", "qdisc", "show");
            default: throw new IllegalArgumentException("Unknown read-only probe");
        }
    }
    public static Map<String, Role> tetherRoles(String dump, List<Link> links) {
        String current = dump.split("  Log:", 2)[0];
        if (current.split("  Tether state:", -1).length != 2 || !current.contains("  Hardware offload:"))
            throw new IllegalArgumentException("Format tethering non qualifié");
        Map<Pattern, Role> patterns = new LinkedHashMap<>();
        for (String type : Arrays.asList("Wifi", "Usb", "Ncm")) {
            Matcher m = Pattern.compile("(?m)^\\s*tetherable" + type + "Regexs: \\[(.*)\\]$").matcher(current);
            if (!m.find() || m.group(1).length() > 1024) throw new IllegalArgumentException("Classification Android absente");
            for (String regex : m.group(1).split(",")) if (!regex.trim().isEmpty())
                patterns.put(Pattern.compile(regex.trim()), type.equals("Wifi") ? Role.WIFI_CLIENTS : Role.USB_CLIENTS);
        }
        Map<String, Role> roles = new HashMap<>();
        String section = current.split("  Tether state:", 2)[1].split("  Hardware offload:", 2)[0];
        for (String line : section.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Upstream wanted:") || line.startsWith("Current upstream interface(s):")) continue;
            Matcher m = Pattern.compile("(\\S+) - (\\w+) - lastError = (\\d+)").matcher(line);
            if (!m.matches()) throw new IllegalArgumentException("IpServer inconnu");
            if (Arrays.asList("AvailableState", "UnavailableState", "LocalHotspotState").contains(m.group(2))) continue;
            if (!m.group(2).equals("TetheredState") || !m.group(3).equals("0")) throw new IllegalArgumentException("IpServer en erreur");
            String name = m.group(1); RoutingProfile.interfaceName(name);
            Link link = links.stream().filter(l -> l.name.equals(name)).findFirst().orElseThrow(IllegalArgumentException::new);
            if (link.role != Role.OTHER || name.equals("lo") || link.v4.isEmpty()) throw new IllegalArgumentException("Rôle/adresse ambigu");
            Set<Role> matches = new HashSet<>(); for (Map.Entry<Pattern, Role> p : patterns.entrySet()) if (p.getKey().matcher(name).matches()) matches.add(p.getValue());
            if (matches.size() != 1 || roles.containsKey(name)) throw new IllegalArgumentException("Classification ambiguë");
            roles.put(name, matches.iterator().next());
        }
        return roles;
    }
    /** Current state only: Android 17 uses a colon; older builds may use '='. Never read log history. */
    public static Boolean bpfEnabled(String dump) {
        String current = dump.split("(?m)^\\s{0,2}Log:\\s*$", 2)[0];
        Matcher m = Pattern.compile("(?m)^\\s*mIsBpfEnabled\\s*[:=]\\s*(true|false)\\s*$").matcher(current);
        if (!m.find()) return null;
        boolean value = Boolean.parseBoolean(m.group(1));
        return m.find() ? null : value; // ambiguous duplicate state is unknown
    }
    /** Display-only fallback when RTM_GETLINK is denied. Never supplies a trusted routing snapshot. */
    public static List<Link> addressLinks(String v4, String v6) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : (v4 + "\n" + v6).split("\n")) {
            Matcher m = Pattern.compile("^(\\d+): ([a-zA-Z0-9_.-]+)(?:@\\S+)?\\s+inet6? .*\\bvalid_lft.*$").matcher(line.trim());
            if (m.matches()) rows.put(m.group(1) + ":" + m.group(2), m.group(1) + ": " + m.group(2) + ": address-only");
        }
        return links(String.join("\n", rows.values()), v4, v6, Collections.emptyMap());
    }
    public static List<Link> links(String linkOutput, String v4, String v6, Map<String, Role> trustedRoles) {
        List<Link> result = new ArrayList<>();
        for (String line : linkOutput.split("\n")) {
            Matcher m = Pattern.compile("^(\\d+): ([a-zA-Z0-9_.-]+)(?:@[^: ]+)?:.*$").matcher(line.trim());
            if (!m.matches()) continue;
            String name = m.group(2);
            // A tun-looking name is not proof of WireGuard ownership; AP roles require a trusted adapter.
            result.add(new Link(name, Integer.parseInt(m.group(1)), trustedRoles.getOrDefault(name, Role.OTHER),
                address(v4, name, "inet"), address(v6, name, "inet6")));
        }
        return result;
    }
    private static String address(String output, String name, String family) {
        String found = "";
        for (String line : output.split("\n")) {
            Matcher m = Pattern.compile("^\\d+: " + Pattern.quote(name) + "(?:@\\S+)?\\s+" + family + " ([0-9a-fA-F:./]+) .*$").matcher(line.trim());
            if (m.matches()) {
                String value = m.group(1);
                if (family.equals("inet6") && (value.startsWith("fe80:") || value.startsWith("::1/"))) continue;
                if (!found.isEmpty()) return ""; // Multiple addresses need explicit selection, not first-match routing.
                found = value;
            }
        }
        return found;
    }
    /** Exports categorical evidence only. Raw dumps, addresses, SSIDs, UIDs, endpoints and rule text never leave here. */
    public static String sanitized(Map<Probe, RootNetworkBackend.Result> results, int sdk, String kernel) {
        StringBuilder out = new StringBuilder("AutoTunnel DS015 · diagnostic nettoyé\nAndroid SDK=").append(sdk);
        out.append("\nNoyau=").append(kernel.matches("[a-zA-Z0-9._+-]{1,120}") ? kernel : "non communiqué");
        for (Probe probe : Probe.values()) {
            RootNetworkBackend.Result r = results.get(probe);
            out.append('\n').append(probe).append('=');
            if (r == null) out.append("NON_OBSERVÉ");
            else if (r.code != 0) out.append("INDISPONIBLE");
            else if (probe == Probe.ID) out.append(r.output.trim().equals("0") ? "ROOT" : "NON_ROOT");
            else if (probe == Probe.SELINUX) out.append(r.output.trim().equals("Enforcing") ? "ENFORCING" : "NON_QUALIFIÉ");
            else out.append("LECTURE_OK; efficacité NON_DÉMONTRÉE");
        }
        return out.append("\nBackend intégré AutoTunnel=GoBackend/VpnService ; propriétaire du VPN actif NON_ATTRIBUÉ\nMutation matérielle=VERROUILLÉE\nAucune preuve d’absence de fuite acquise").toString();
    }
}
