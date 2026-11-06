package io.github.syldegil.autotunnel;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Declarative intent only. Never contains keys, peers, endpoints or shell commands. */
public final class RoutingProfile {
    public enum Scope { PHONE, WIFI, USB, ALL }
    public enum Mode { REQUIRE_TUNNEL, DIRECT }
    public enum V6 { BLOCK, REQUIRE_TUNNEL }
    public enum Family { IPV4, IPV6, BOTH }
    public enum Direction { IN, OUT, FORWARD }
    public enum Protocol { TCP, UDP, ICMP, ICMPV6 }
    public enum Action { ALLOW, BLOCK }
    public enum Path { PHONE, WIFI, USB }

    public final Scope scope;
    public final Mode mode;
    public final V6 ipv6;
    // Empty routes/DNS mean inherit, never a fabricated full tunnel or DNS fallback.
    public final List<String> routes4, routes6, dns;
    public final List<Rule> rules;

    public RoutingProfile(Scope scope, Mode mode, V6 ipv6, List<String> routes4,
                          List<String> routes6, List<String> dns, List<Rule> rules) {
        if (scope == null || mode == null || ipv6 == null) throw invalid();
        this.scope = scope; this.mode = mode; this.ipv6 = ipv6;
        this.routes4 = copy(routes4); this.routes6 = copy(routes6); this.dns = copy(dns);
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        if (this.rules.size() > 64) throw invalid();
        for (String value : this.routes4) cidr(value, Family.IPV4);
        for (String value : this.routes6) cidr(value, Family.IPV6);
        for (String value : this.dns) address(value);
        if (ipv6 == V6.BLOCK && !this.routes6.isEmpty())
            throw new IllegalArgumentException("IPv6 bloqué : retirez les routes IPv6 proposées.");
        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : this.rules) {
            if (rule == null || !priorities.add(rule.priority))
                throw new IllegalArgumentException("Chaque règle doit avoir une priorité distincte.");
            if (scope != Scope.ALL && !scope.name().equals(rule.path.name()))
                throw new IllegalArgumentException("La règle doit concerner le parcours du profil.");
        }
    }

    public static RoutingProfile defaults(Scope scope) {
        return new RoutingProfile(scope, Mode.REQUIRE_TUNNEL, V6.BLOCK,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
    public static String title(Scope scope) {
        switch (scope) {
            case PHONE: return "Téléphone via WireGuard";
            case WIFI: return "Partage Wi-Fi via WireGuard";
            case USB: return "Partage USB via WireGuard";
            default: return "Combiné · téléphone / USB / hotspot";
        }
    }
    public static List<String> entries(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        Collections.addAll(result, trimmed.split("[\\s,;]+"));
        return result;
    }
    private static List<String> copy(List<String> values) {
        if (values == null || values.size() > 128) throw invalid();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Configuration invalide ou trop volumineuse.");
    }

    /** Numeric literals only. Hostnames, zones and command fragments are rejected before parsing. */
    public static Family address(String value) {
        if (value == null || value.length() > 45) throw invalid();
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:]+")) throw invalid();
            try {
                if (InetAddress.getByName(value) instanceof Inet6Address) return Family.IPV6;
            } catch (Exception ignored) { /* Never echo input or exception messages. */ }
            throw invalid();
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw invalid();
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) throw invalid();
        }
        return Family.IPV4;
    }
    public static void cidr(String value, Family expected) {
        if (value == null) throw invalid();
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !parts[1].matches("0|[1-9][0-9]{0,2}")) throw invalid();
        Family actual = address(parts[0]);
        if (expected != actual || Integer.parseInt(parts[1]) > (actual == Family.IPV4 ? 32 : 128)) throw invalid();
    }
    public static void interfaceName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,14}")) throw invalid();
    }

    public static final class Rule {
        public final Direction direction;
        public final Protocol protocol;
        public final Family family;
        public final Path path;
        public final Action action;
        public final int firstPort, lastPort, priority;
        public final String source, destination;
        public final String inputInterface, outputInterface;
        public final boolean enabled;
        public Rule(Direction direction, Protocol protocol, Family family, Path path, Action action,
                    int firstPort, int lastPort, String source, String destination, int priority, boolean enabled) {
            this(direction, protocol, family, path, action, firstPort, lastPort, source, destination, priority, enabled, "", "");
        }
        public Rule(Direction direction, Protocol protocol, Family family, Path path, Action action,
                    int firstPort, int lastPort, String source, String destination, int priority, boolean enabled,
                    String inputInterface, String outputInterface) {
            if (direction == null || protocol == null || family == null || path == null || action == null
                || priority < 1 || priority > 65535)
                throw invalid();
            boolean ports = protocol == Protocol.TCP || protocol == Protocol.UDP;
            if (ports ? firstPort < 1 || lastPort > 65535 || firstPort > lastPort : firstPort != 0 || lastPort != 0) throw invalid();
            if (protocol == Protocol.ICMP && family != Family.IPV4 || protocol == Protocol.ICMPV6 && family != Family.IPV6) throw invalid();
            if (inputInterface == null || outputInterface == null) throw invalid();
            if (!inputInterface.isEmpty()) interfaceName(inputInterface);
            if (!outputInterface.isEmpty()) interfaceName(outputInterface);
            if (path == Path.PHONE && (direction == Direction.FORWARD
                || direction == Direction.IN && !outputInterface.isEmpty()
                || direction == Direction.OUT && !inputInterface.isEmpty())) throw invalid();
            if (source == null || destination == null) throw invalid();
            if (!source.isEmpty() && !source.contains("/")) source += address(source) == Family.IPV4 ? "/32" : "/128";
            if (!destination.isEmpty() && !destination.contains("/")) destination += address(destination) == Family.IPV4 ? "/32" : "/128";
            // BOTH with family-specific selectors would silently omit half the policy.
            if (!source.isEmpty()) cidr(source, family);
            if (!destination.isEmpty()) cidr(destination, family);
            this.direction = direction; this.protocol = protocol; this.family = family; this.path = path;
            this.action = action; this.firstPort = firstPort; this.lastPort = lastPort;
            this.source = source; this.destination = destination; this.priority = priority; this.enabled = enabled;
            this.inputInterface = inputInterface; this.outputInterface = outputInterface;
        }
        public String summary() {
            return priority + " · " + (enabled ? "activée dans le brouillon" : "désactivée") + " · "
                + (direction == Direction.IN ? "Entrant" : direction == Direction.OUT ? "Sortant" : "Transféré") + " · " + protocol
                + (firstPort == 0 ? " · sans port" : " · port destination " + firstPort + (lastPort == firstPort ? "" : "–" + lastPort)) + " · " + family + " · " + path
                + " · " + (action == Action.ALLOW ? "Autoriser" : "Bloquer")
                + "\nSource : " + (source.isEmpty() ? "toutes" : source)
                + " ; destination : " + (destination.isEmpty() ? "toutes" : destination)
                + "\nInterfaces entrée/sortie : " + (inputInterface.isEmpty() ? "selon parcours" : inputInterface)
                + " / " + (outputInterface.isEmpty() ? "selon parcours" : outputInterface);
        }
    }
}
