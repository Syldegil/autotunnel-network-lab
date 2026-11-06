package io.github.syldegil.autotunnel;

import java.util.*;

/** Immutable observations. Capability proofs are supplied by the backend, never by the UI. */
public final class NetworkSnapshot {
    public enum Capability {
        ROOT, ENFORCING, IPTABLES4, IPTABLES6, NFT, POLICY_ROUTING, OWNER_MATCH, CONNTRACK_MATCH,
        SNAT4, BOOT_GUARD, NEW_INTERFACE_GUARD, SERVICE_DEATH_GUARD, QUOTA_ORDER,
        OFFLOAD_WIFI, OFFLOAD_USB, CONNTRACK_DRAIN, PHONE_VPN_ROUTE, WIFI_TUN_INGRESS, USB_TUN_INGRESS,
        IPV4_RETURN, IPV6_RETURN, DNS_PATH, TRANSPORT_EXCEPTION, IPV6_ND_CONTROL
    }
    public enum Role { WIFI_UPSTREAM, MOBILE_UPSTREAM, OTHER, VPN, WIFI_CLIENTS, USB_CLIENTS }
    public static final class Link {
        public final String name, v4, v6;
        public final int ifindex;
        public final Role role;
        public Link(String name, int ifindex, Role role, String v4, String v6) {
            RoutingProfile.interfaceName(name);
            if (ifindex < 1 || role == null) throw new IllegalArgumentException("Interface non identifiée");
            if (!v4.isEmpty()) RoutingProfile.cidr(v4, RoutingProfile.Family.IPV4);
            if (!v6.isEmpty()) RoutingProfile.cidr(v6, RoutingProfile.Family.IPV6);
            this.name = name; this.ifindex = ifindex; this.role = role; this.v4 = v4; this.v6 = v6;
        }
        public String identity() { return role + ":" + name + ":" + ifindex + ":" + v4 + ":" + v6; }
    }
    public final String boot, generation, endpoint;
    public final int endpointPort, vpnUid;
    public final long sampledAt;
    public final boolean simulated, tunnelUp;
    public final List<Link> links;
    public final Set<Capability> capabilities;
    public final List<String> routes4, routes6, rules4, rules6, dns;
    public NetworkSnapshot(String boot, String generation, long sampledAt, boolean simulated, boolean tunnelUp,
                           List<Link> links, Set<Capability> capabilities, String endpoint, int endpointPort, int vpnUid,
                           List<String> routes4, List<String> routes6, List<String> rules4, List<String> rules6, List<String> dns) {
        if (!boot.matches("[a-zA-Z0-9_-]{1,64}") || !generation.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException();
        if (!endpoint.isEmpty()) RoutingProfile.address(endpoint);
        if (endpointPort < 0 || endpointPort > 65535 || vpnUid < 0) throw new IllegalArgumentException();
        Set<String> names = new HashSet<>(); Set<Integer> ids = new HashSet<>();
        for (Link l : links) if (!names.add(l.name) || !ids.add(l.ifindex)) throw new IllegalArgumentException("Rôles ambigus");
        this.boot = boot; this.generation = generation; this.sampledAt = sampledAt; this.simulated = simulated;
        this.tunnelUp = tunnelUp; this.links = Collections.unmodifiableList(new ArrayList<>(links));
        this.capabilities = Collections.unmodifiableSet(new HashSet<>(capabilities));
        this.endpoint = endpoint; this.endpointPort = endpointPort; this.vpnUid = vpnUid;
        this.routes4 = immutable(routes4); this.routes6 = immutable(routes6);
        this.rules4 = immutable(rules4); this.rules6 = immutable(rules6); this.dns = immutable(dns);
        for (String d : dns) RoutingProfile.address(d);
    }
    private static List<String> immutable(List<String> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    public Link link(Role role) {
        Link found = null;
        for (Link l : links) if (l.role == role) {
            if (found != null) throw new IllegalArgumentException("Plusieurs interfaces pour un rôle : qualification requise");
            found = l;
        }
        if (found == null) throw new IllegalArgumentException("Interface absente : " + role);
        return found;
    }
    public String identity() {
        List<String> identities = new ArrayList<>(); for (Link l : links) identities.add(l.identity()); Collections.sort(identities);
        return boot + "/" + generation + "/" + NetworkPlan.hash(String.join(";", identities)
            + "|" + endpoint + ":" + endpointPort + "|" + vpnUid + "|" + routes4 + "|" + routes6
            + "|" + rules4 + "|" + rules6 + "|" + dns);
    }
    public boolean fresh(long now) { return sampledAt > 0 && now >= sampledAt && now - sampledAt <= 15000; }
    public static List<RoutingProfile.Path> paths(RoutingProfile.Scope scope) {
        if (scope == RoutingProfile.Scope.ALL) return Arrays.asList(RoutingProfile.Path.values());
        return Collections.singletonList(RoutingProfile.Path.valueOf(scope.name()));
    }
    public List<String> blockers(RoutingProfile p, long now) {
        List<String> result = new ArrayList<>();
        if (!fresh(now)) result.add("Observation absente/périmée");
        EnumSet<Capability> required = EnumSet.of(Capability.ROOT, Capability.ENFORCING, Capability.IPTABLES4,
            Capability.IPTABLES6, Capability.CONNTRACK_MATCH, Capability.BOOT_GUARD, Capability.NEW_INTERFACE_GUARD,
            Capability.SERVICE_DEATH_GUARD, Capability.QUOTA_ORDER);
        if (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL) {
            required.addAll(EnumSet.of(Capability.TRANSPORT_EXCEPTION, Capability.IPV4_RETURN, Capability.DNS_PATH));
            if (endpoint.isEmpty() || endpointPort == 0) result.add("Transport WireGuard extérieur non identifié");
            if (p.ipv6 == RoutingProfile.V6.REQUIRE_TUNNEL) required.addAll(EnumSet.of(Capability.IPV6_RETURN, Capability.IPV6_ND_CONTROL));
        }
        for (RoutingProfile.Path path : paths(p.scope)) {
            if (path == RoutingProfile.Path.PHONE) {
                if (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL) required.addAll(EnumSet.of(Capability.OWNER_MATCH, Capability.PHONE_VPN_ROUTE));
            } else {
                required.add(path == RoutingProfile.Path.WIFI ? Capability.OFFLOAD_WIFI : Capability.OFFLOAD_USB);
                if (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL) {
                    required.addAll(EnumSet.of(Capability.POLICY_ROUTING, Capability.SNAT4, Capability.CONNTRACK_DRAIN));
                    required.add(path == RoutingProfile.Path.WIFI ? Capability.WIFI_TUN_INGRESS : Capability.USB_TUN_INGRESS);
                }
            }
        }
        for (Capability c : required) if (!capabilities.contains(c)) result.add("Capacité non qualifiée : " + c);
        return result;
    }
}
