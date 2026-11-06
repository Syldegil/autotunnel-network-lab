package io.github.syldegil.autotunnel;

import java.util.*;
import static io.github.syldegil.autotunnel.RoutingProfile.*;
import static io.github.syldegil.autotunnel.NetworkPlan.*;
import static io.github.syldegil.autotunnel.NetworkSnapshot.*;

/** Dual-stack iptables compiler. No shell, global default, flush, NAT66 or WireGuard mutation. */
public final class NetfilterCompiler {
    public static final int TABLE = 5013;
    private final List<Operation> guards = new ArrayList<>(), policy = new ArrayList<>(), open = new ArrayList<>(), close = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private int sequence;
    private RoutingProfile profile;
    private NetworkSnapshot snapshot;
    private Link vpn;
    public NetworkPlan compile(RoutingProfile p, NetworkSnapshot s) {
        // Compiler instances are intentionally single-use: no leaked state between proposals.
        if (profile != null) throw new IllegalStateException("Compiler already used");
        profile = p; snapshot = s;
        if (p.mode == Mode.REQUIRE_TUNNEL) {
            vpn = s.link(Role.VPN);
            if (vpn.v4.isEmpty()) throw unsupported("Adresse IPv4 du tunnel absente");
            if (s.endpoint.isEmpty() || s.endpointPort < 1) throw unsupported("Exception extérieure WireGuard non identifiée");
            if (p.ipv6 == V6.REQUIRE_TUNNEL && vpn.v6.isEmpty()) throw unsupported("Aucune adresse IPv6 de tunnel qualifiée");
        }
        List<Link> clients = clients();
        for (Link a : clients) {
            if (a.v4.isEmpty()) throw unsupported("Préfixe client IPv4 absent");
            if (vpn != null && a.name.equals(vpn.name)) throw unsupported("Boucle de routage");
            for (Link b : clients) if (a != b && (new IpPrefix(a.v4).contains(new IpPrefix(b.v4)) || new IpPrefix(b.v4).contains(new IpPrefix(a.v4))))
                throw unsupported("Préfixes clients ambigus");
        }
        for (Rule r : p.rules) for (String iface : Arrays.asList(r.inputInterface, r.outputInterface))
            if (!iface.isEmpty() && s.links.stream().noneMatch(l -> l.name.equals(iface))) throw unsupported("Interface de règle non observée");
        boolean phone = NetworkSnapshot.paths(p.scope).contains(Path.PHONE);
        if (phone && !p.dns.isEmpty() && !p.dns.equals(s.dns)) throw unsupported("Modifier le DNS téléphone exige une modification WireGuard distincte");
        for (int family : new int[]{6, 4}) { // Close IPv6 first, never infer it from IPv4.
            for (String hook : new String[]{"INPUT", "OUTPUT", "FORWARD"}) {
                if (hook.equals("FORWARD") && clients.isEmpty()) continue;
                String suffix = family + hook.substring(0, 1);
                String g = "AT13_G" + suffix, route = "AT13_R" + suffix, user = "AT13_U" + suffix;
                create(guards, family, "filter", g); create(policy, family, "filter", route); create(policy, family, "filter", user);
                if (phone && !hook.equals("FORWARD")) rule(guards, family, "filter", g, hook.equals("OUTPUT") ? "-o" : "-i", "lo", "-j", "RETURN");
                transport(family, hook, g, phone);
                for (Link client : clients) {
                    if (family == 4 && hook.equals("INPUT")) rule(guards, family, "filter", g, "-i", client.name, "-p", "udp", "--sport", "68", "--dport", "67", "-j", "RETURN");
                    if (family == 4 && hook.equals("OUTPUT")) rule(guards, family, "filter", g, "-o", client.name, "-p", "udp", "--sport", "67", "--dport", "68", "-j", "RETURN");
                    if (family == 6 && p.ipv6 == V6.REQUIRE_TUNNEL && !hook.equals("FORWARD")) {
                        if (client.v6.isEmpty()) throw unsupported("Contrôle NDP/RA : préfixe IPv6 client absent");
                        for (String source : Arrays.asList("fe80::/10", hook.equals("INPUT") ? new IpPrefix(client.v6).network() : client.v6.split("/")[0] + "/128"))
                            for (String type : hook.equals("INPUT") ? new String[]{"133", "135", "136"} : new String[]{"134", "135", "136"})
                                rule(guards, family, "filter", g, hook.equals("INPUT") ? "-i" : "-o", client.name, "-s", source,
                                    "-p", "ipv6-icmp", "--icmpv6-type", type, "-m", "hl", "--hl-eq", "255", "-j", "RETURN");
                    }
                }
                rule(guards, family, "filter", g, "-j", "DROP");
                int gatePosition = positions.get(family + ":filter:" + g);
                if (phone && !hook.equals("FORWARD")) hook(family, "filter", hook, g);
                else for (Link client : clients) {
                    if (!hook.equals("OUTPUT")) hook(family, "filter", hook, g, "-i", client.name);
                    if (!hook.equals("INPUT")) hook(family, "filter", hook, g, "-o", client.name);
                }
                compileRouteGate(family, hook, route, user, phone, clients);
                List<Rule> sorted = new ArrayList<>(p.rules); sorted.sort(Comparator.comparingInt(r -> r.priority));
                for (Rule r : sorted) if (r.enabled && (r.family == Family.BOTH || r.family == (family == 4 ? Family.IPV4 : Family.IPV6)))
                    compileUser(family, hook, user, r);
                rule(policy, family, "filter", user, "-j", "RETURN");
                open.add(replace(family, g, gatePosition, "-g", route, "-j", "DROP"));
                close.add(replace(family, g, gatePosition, "-j", "DROP", "-g", route));
            }
            if (p.mode == Mode.REQUIRE_TUNNEL && !clients.isEmpty() && (family == 4 || p.ipv6 == V6.REQUIRE_TUNNEL)) routes(family, clients);
        }
        if (p.mode == Mode.REQUIRE_TUNNEL && !clients.isEmpty()) nat(clients);
        return new NetworkPlan(p, s, guards, policy, open, close);
    }
    private IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException(reason); }
    private List<Link> clients() {
        List<Link> out = new ArrayList<>();
        for (Path path : NetworkSnapshot.paths(profile.scope)) if (path != Path.PHONE)
            out.add(snapshot.link(path == Path.WIFI ? Role.WIFI_CLIENTS : Role.USB_CLIENTS));
        return out;
    }
    private List<String> allowed(int family) {
        List<String> inherited = family == 4 ? snapshot.routes4 : snapshot.routes6;
        List<String> wanted = family == 4 ? profile.routes4 : profile.routes6;
        if (wanted.isEmpty()) wanted = inherited;
        if (wanted.isEmpty()) throw unsupported("Routes WireGuard absentes pour la famille " + family);
        List<String> out = new ArrayList<>();
        for (String route : wanted) {
            RoutingProfile.cidr(route, family == 4 ? Family.IPV4 : Family.IPV6);
            if (inherited.stream().noneMatch(parent -> new IpPrefix(parent).contains(new IpPrefix(route)))) throw unsupported("Route hors AllowedIPs du pair");
            out.add(new IpPrefix(route).network());
        }
        return out;
    }
    private void transport(int family, String hook, String chain, boolean phone) {
        if (!phone || vpn == null || hook.equals("FORWARD") || snapshot.endpoint.contains(":") != (family == 6)) return;
        if (hook.equals("OUTPUT")) rule(guards, family, "filter", chain, "-m", "owner", "--uid-owner", Integer.toString(snapshot.vpnUid),
            "-p", "udp", "-d", snapshot.endpoint, "--dport", Integer.toString(snapshot.endpointPort), "-j", "RETURN");
        else rule(guards, family, "filter", chain, "-p", "udp", "-s", snapshot.endpoint, "--sport", Integer.toString(snapshot.endpointPort),
            "-m", "conntrack", "--ctstate", "ESTABLISHED", "-j", "RETURN");
    }
    private void compileRouteGate(int family, String hook, String chain, String user, boolean phone, List<Link> clients) {
        if (family == 6 && profile.ipv6 == V6.BLOCK) { rule(policy, family, "filter", chain, "-j", "DROP"); return; }
        if (profile.mode == Mode.DIRECT) { rule(policy, family, "filter", chain, "-g", user); return; }
        List<String> routes = allowed(family);
        if (phone && !hook.equals("FORWARD")) {
            for (String prefix : routes) rule(policy, family, "filter", chain,
                hook.equals("OUTPUT") ? "-o" : "-i", vpn.name, hook.equals("OUTPUT") ? "-d" : "-s", prefix, "-g", user);
        } else if (hook.equals("FORWARD")) for (Link client : clients) {
            String subnet = family == 4 ? client.v4 : client.v6;
            if (subnet.isEmpty()) throw unsupported("Préfixe client IPv6 absent");
            subnet = new IpPrefix(subnet).network();
            for (String route : routes) {
                rule(policy, family, "filter", chain, "-i", client.name, "-s", subnet, "-o", vpn.name, "-d", route, "-g", user);
                rule(policy, family, "filter", chain, "-i", vpn.name, "-s", route, "-o", client.name, "-d", subnet, "-g", user);
            }
        }
        rule(policy, family, "filter", chain, "-j", "DROP");
    }
    private void compileUser(int family, String hook, String chain, Rule r) {
        if (r.path == Path.PHONE) {
            if (!hook.equals(r.direction == Direction.IN ? "INPUT" : "OUTPUT")) return;
            userRule(family, chain, r, Collections.emptyList());
        } else if (hook.equals("FORWARD")) {
            String iface = snapshot.link(r.path == Path.WIFI ? Role.WIFI_CLIENTS : Role.USB_CLIENTS).name;
            if (r.direction != Direction.IN) userRule(family, chain, r, Arrays.asList("-i", iface));
            if (r.direction != Direction.OUT) userRule(family, chain, r, Arrays.asList("-o", iface));
        }
    }
    private void userRule(int family, String chain, Rule r, List<String> binding) {
        List<String> args = new ArrayList<>(binding);
        // Conflicting selectors are refused, never emitted as duplicate -i/-o arguments.
        for (String flag : new String[]{"-i", "-o"}) {
            String iface = flag.equals("-i") ? r.inputInterface : r.outputInterface;
            if (iface.isEmpty()) continue;
            int existing = args.indexOf(flag);
            if (existing >= 0 && !args.get(existing + 1).equals(iface)) throw unsupported("Interface incompatible avec le parcours de la règle");
            if (existing < 0) Collections.addAll(args, flag, iface);
        }
        Collections.addAll(args, "-p", r.protocol == Protocol.ICMPV6 ? "ipv6-icmp" : r.protocol.name().toLowerCase(Locale.ROOT));
        if (r.firstPort > 0) Collections.addAll(args, "--dport", r.firstPort == r.lastPort ? Integer.toString(r.firstPort) : r.firstPort + ":" + r.lastPort);
        if (!r.source.isEmpty()) Collections.addAll(args, "-s", new IpPrefix(r.source).network());
        if (!r.destination.isEmpty()) Collections.addAll(args, "-d", new IpPrefix(r.destination).network());
        Collections.addAll(args, "-j", r.action == Action.BLOCK ? "DROP" : "RETURN");
        rule(policy, family, "filter", chain, args.toArray(new String[0]));
    }
    private void nat(List<Link> clients) {
        String snat = "AT13_N4", dns = "AT13_D4";
        create(policy, 4, "nat", snat); create(policy, 4, "nat", dns);
        List<String> resolvers = profile.dns.isEmpty() ? snapshot.dns : profile.dns;
        if (resolvers.size() != 1 || RoutingProfile.address(resolvers.get(0)) != Family.IPV4) throw unsupported("DNAT clients : un résolveur IPv4 explicite/hérité requis");
        if (allowed(4).stream().noneMatch(r -> new IpPrefix(r).contains(new IpPrefix(resolvers.get(0) + "/32")))) throw unsupported("DNS hors routes tunnel");
        for (Link client : clients) {
            String prefix = new IpPrefix(client.v4).network();
            rule(policy, 4, "nat", snat, "-s", prefix, "-o", vpn.name, "-m", "addrtype", "!", "--src-type", "LOCAL", "-j", "SNAT", "--to-source", vpn.v4.split("/")[0]);
            for (String protocol : new String[]{"udp", "tcp"}) rule(policy, 4, "nat", dns, "-i", client.name, "-s", prefix, "-p", protocol, "--dport", "53", "-j", "DNAT", "--to-destination", resolvers.get(0));
        }
        hookPolicy(4, "nat", "POSTROUTING", snat); hookPolicy(4, "nat", "PREROUTING", dns);
    }
    private void routes(int family, List<Link> clients) {
        String f = "-" + family, table = Integer.toString(TABLE);
        ipRoute(f, Arrays.asList("unreachable", "default", "metric", "32760"), Arrays.asList("unreachable", "default"));
        for (String route : allowed(family)) ipRoute(f, Arrays.asList(route, "dev", vpn.name, "metric", "10"), Arrays.asList(route.equals("0.0.0.0/0") || route.equals("0:0:0:0:0:0:0:0/0") ? "default" : route, "dev", vpn.name));
        int i = 0;
        for (Link client : clients) {
            String prefix = new IpPrefix(family == 4 ? client.v4 : client.v6).network();
            ipRoute(f, Arrays.asList(prefix, "dev", client.name, "metric", "10"), Arrays.asList(prefix, "dev", client.name));
            ipRule(f, 17913 + i, Arrays.asList("from", prefix, "iif", client.name, "lookup", table));
            ipRule(f, 18013 + i, Arrays.asList("iif", client.name, "prohibit"));
            ipRule(f, 11913 + i, Arrays.asList("iif", vpn.name, "to", prefix, "lookup", table)); i++;
        }
    }
    private void ipRoute(String family, List<String> route, List<String> expected) {
        List<String> a = list(family, "route", "add"); a.addAll(route); Collections.addAll(a, "table", Integer.toString(TABLE));
        List<String> u = new ArrayList<>(a); u.set(2, "del");
        policy.add(new Operation(id(), Tool.IP, a, u, list(family, "route", "show", "table", Integer.toString(TABLE)), Check.ROUTE, expected));
    }
    private void ipRule(String family, int priority, List<String> selector) {
        List<String> a = list(family, "rule", "add", "priority", Integer.toString(priority)); a.addAll(selector);
        List<String> u = new ArrayList<>(a); u.set(2, "del");
        // ip rule output orders from/to/iif differently; verify via priority-specific query + selector in the backend.
        List<String> expected = list(priority + ":");
        policy.add(new Operation(id(), Tool.IP, a, u, list(family, "rule", "show"), Check.RULE, expected));
    }
    private Tool tool(int family) { return family == 4 ? Tool.IPTABLES : Tool.IP6TABLES; }
    private String id() { return String.format(Locale.ROOT, "op%04d", ++sequence); }
    private static List<String> list(String... values) { return new ArrayList<>(Arrays.asList(values)); }
    private List<String> base(String table) { return list("-w", "5", "-t", table); }
    private void create(List<Operation> dest, int family, String table, String chain) {
        List<String> a = base(table); Collections.addAll(a, "-N", chain);
        List<String> u = base(table); Collections.addAll(u, "-X", chain);
        List<String> q = base(table); Collections.addAll(q, "-S", chain);
        dest.add(new Operation(id(), tool(family), a, u, q, Check.EXIT_ZERO, Collections.emptyList()));
    }
    private void rule(List<Operation> dest, int family, String table, String chain, String... args) {
        List<String> a = base(table); Collections.addAll(a, "-A", chain); Collections.addAll(a, args);
        List<String> u = base(table); Collections.addAll(u, "-D", chain); Collections.addAll(u, args);
        List<String> q = base(table); Collections.addAll(q, "-S", chain);
        int position = positions.merge(family + ":" + table + ":" + chain, 1, Integer::sum);
        List<String> expected = list("-A", chain); Collections.addAll(expected, args);
        dest.add(new Operation(id(), tool(family), a, u, q, Check.RULE_AT_INDEX, expected, position));
    }
    private void hook(int family, String table, String hook, String chain, String... selector) { hookTo(guards, family, table, hook, chain, selector); }
    private void hookPolicy(int family, String table, String hook, String chain) { hookTo(policy, family, table, hook, chain); }
    private void hookTo(List<Operation> dest, int family, String table, String hook, String chain, String... selector) {
        List<String> a = base(table); Collections.addAll(a, "-I", hook, "1"); Collections.addAll(a, selector); Collections.addAll(a, "-j", chain);
        List<String> u = base(table); Collections.addAll(u, "-D", hook); Collections.addAll(u, selector); Collections.addAll(u, "-j", chain);
        List<String> q = new ArrayList<>(u); q.set(4, "-C");
        dest.add(new Operation(id(), tool(family), a, u, q, Check.EXIT_ZERO, Collections.emptyList()));
    }
    private Operation replace(int family, String chain, int position, String jump, String target, String oldJump, String oldTarget) {
        List<String> a = base("filter"); Collections.addAll(a, "-R", chain, Integer.toString(position), jump, target);
        List<String> u = base("filter"); Collections.addAll(u, "-R", chain, Integer.toString(position), oldJump, oldTarget);
        List<String> q = base("filter"); Collections.addAll(q, "-S", chain);
        return new Operation(id(), tool(family), a, u, q, Check.RULE_AT_INDEX, list("-A", chain, jump, target), position);
    }
}
