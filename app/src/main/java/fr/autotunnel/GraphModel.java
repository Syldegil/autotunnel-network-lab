package io.github.syldegil.autotunnel;

import java.util.*;

/** Immutable, bounded declarative topology. It has no executor, Vault or Android dependency. */
public final class GraphModel {
    public enum Kind {
        INTERNET("Internet"), WIFI("Wi-Fi amont"), MOBILE_3G("Mobile 3G"), MOBILE_4G("Mobile 4G"), MOBILE_5G("Mobile 5G"),
        INTERFACE("Interface réseau"), AUTO_UPLINK("Amont automatique"), FIREWALL("Pare-feu"), ROUTER("Routeur"),
        WIREGUARD("WireGuard · overlay"), KILL_SWITCH("Coupe-circuit"), DNS("DNS"), NAT4("NAT IPv4"), IPV6("Règles IPv6"),
        PHONE_APPS("Applications téléphone"), PHONE_INTERFACE("Interface téléphone"), USB("Partage USB"), HOTSPOT("Hotspot Wi-Fi"),
        EXTERNAL_CLIENT("Client externe"), FREEBOX("Freebox"), HOME_LAN("LAN domestique"), REMOTE_INTERNET("Internet distant"),
        INSPECTOR("Inspecteur"), COUNTER("Compteur"), RATE("Débit"), LEAK_DETECTION("Détection de fuite"), LOGS("Journaux");
        public final String title;
        Kind(String title) { this.title = title; }
        public boolean upstream() { return this == WIFI || this == MOBILE_3G || this == MOBILE_4G || this == MOBILE_5G || this == AUTO_UPLINK; }
        public boolean monitor() { return ordinal() >= INSPECTOR.ordinal(); }
        public List<Port> ports() {
            if (upstream()) return Arrays.asList(Port.UPLINK_IN, Port.PHYSICAL_OUT);
            if (monitor()) return Collections.singletonList(Port.MONITOR_IN);
            if (this == PHONE_APPS || this == USB || this == HOTSPOT || this == EXTERNAL_CLIENT) return Arrays.asList(Port.OUT_IP, Port.MONITOR_OUT);
            if (this == INTERNET) return Arrays.asList(Port.IN_IP, Port.PHYSICAL_IN, Port.PHYSICAL_OUT, Port.MONITOR_OUT);
            if (this == FREEBOX) return Arrays.asList(Port.IN_IP, Port.OUT_IP, Port.PHYSICAL_IN, Port.MONITOR_OUT);
            if (this == INTERFACE || this == PHONE_INTERFACE) return Arrays.asList(Port.IN_IP, Port.OUT_IP, Port.PHYSICAL_IN, Port.PHYSICAL_OUT, Port.MONITOR_OUT);
            if (this == HOME_LAN || this == REMOTE_INTERNET) return Arrays.asList(Port.IN_IP, Port.MONITOR_OUT);
            if (this == WIREGUARD) return Arrays.asList(Port.IN_IP, Port.OUT_IP, Port.TRANSPORT_OUT, Port.MONITOR_OUT);
            return Arrays.asList(Port.IN_IP, Port.OUT_IP, Port.MONITOR_OUT);
        }
    }
    public enum Port {
        IN_IP("IP entrée", false, 0), OUT_IP("IP sortie", true, 0),
        TRANSPORT_OUT("UDP chiffré", true, 1), UPLINK_IN("Transport amont", false, 1),
        PHYSICAL_OUT("Physique sortie", true, 2), PHYSICAL_IN("Physique entrée", false, 2),
        MONITOR_OUT("Mesure source", true, 3), MONITOR_IN("Mesure lecture", false, 3);
        public final String title; public final boolean output; public final int type;
        Port(String title, boolean output, int type) { this.title = title; this.output = output; this.type = type; }
        public boolean accepts(Port target) { return output && !target.output && type == target.type; }
    }
    public enum Policy { INHERIT, DIRECT, REQUIRE_TUNNEL }
    public enum ViewMode { LOGICAL, PHYSICAL, LIVE }
    public static final class Properties {
        public final RoutingProfile.Family family; // null = inherit profile, including its IPv6 blocking policy
        public final Policy policy;
        public final boolean blockUnavailable;
        public final String source, destination, inputInterface, outputInterface;
        public final List<String> dns, routes4, routes6;
        public Properties(RoutingProfile.Family family, Policy policy, boolean blockUnavailable, String source,
                          String destination, String inputInterface, String outputInterface,
                          List<String> dns, List<String> routes4, List<String> routes6) {
            if (policy == null) throw bad("Politique absente");
            this.family = family; this.policy = policy; this.blockUnavailable = blockUnavailable;
            this.source = selector(source, family); this.destination = selector(destination, family);
            this.inputInterface = iface(inputInterface); this.outputInterface = iface(outputInterface);
            this.dns = strings(dns); this.routes4 = strings(routes4); this.routes6 = strings(routes6);
            for (String s : dns) RoutingProfile.address(s);
            for (String s : routes4) RoutingProfile.cidr(s, RoutingProfile.Family.IPV4);
            for (String s : routes6) RoutingProfile.cidr(s, RoutingProfile.Family.IPV6);
        }
        public static Properties defaults() { return new Properties(null, Policy.INHERIT, true, "", "", "", "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()); }
        private static String selector(String s, RoutingProfile.Family f) {
            if (s == null) throw bad("Sélecteur absent");
            if (!s.isEmpty()) {
                if (f == null || f == RoutingProfile.Family.BOTH) throw bad("Un sélecteur exige une famille explicite");
                RoutingProfile.cidr(s, f);
            }
            return s;
        }
        private static String iface(String s) { if (s == null) throw bad("Interface absente"); if (!s.isEmpty()) RoutingProfile.interfaceName(s); return s; }
        private static List<String> strings(List<String> list) {
            if (list == null || list.size() > 128) throw bad("Liste trop volumineuse");
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }
    public static final class Node {
        public final String id, label; public final Kind kind; public final float x, y;
        public final boolean enabled; public final Properties properties;
        public Node(String id, Kind kind, String label, float x, float y, boolean enabled, Properties properties) {
            identifier(id); if (kind == null || properties == null || label == null || label.trim().isEmpty() || label.length() > 80 || label.matches("(?s).*[\\p{Cntrl}].*")) throw bad("Nœud invalide");
            coordinate(x); coordinate(y); this.id = id; this.kind = kind; this.label = label;
            this.x = x; this.y = y; this.enabled = enabled; this.properties = properties;
        }
        public Node move(float x, float y) { return new Node(id, kind, label, x, y, enabled, properties); }
    }
    public static final class Link {
        public final String id, from, to; public final Port output, input;
        public final boolean enabled; public final Properties properties;
        public Link(String id, String from, Port output, String to, Port input, boolean enabled, Properties properties) {
            identifier(id); identifier(from); identifier(to);
            if (output == null || input == null || !output.accepts(input) || properties == null) throw bad("Ports incompatibles : direction ou type");
            this.id = id; this.from = from; this.to = to; this.output = output; this.input = input;
            this.enabled = enabled; this.properties = properties;
        }
    }
    public final List<Node> nodes; public final List<Link> links; public final RoutingProfile profile;
    public final float panX, panY, zoom; public final ViewMode view;
    public GraphModel(List<Node> nodes, List<Link> links, RoutingProfile profile, float panX, float panY, float zoom, ViewMode view) {
        if (nodes == null || links == null || nodes.size() > 100 || links.size() > 200 || profile == null || view == null) throw bad("Graphe trop volumineux ou invalide");
        coordinate(panX); coordinate(panY); if (!Float.isFinite(zoom) || zoom < .2f || zoom > 3f) throw bad("Zoom invalide");
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes)); this.links = Collections.unmodifiableList(new ArrayList<>(links));
        this.profile = profile; this.panX = panX; this.panY = panY; this.zoom = zoom; this.view = view;
        Set<String> ids = new HashSet<>(), pairs = new HashSet<>();
        for (Node n : nodes) if (n == null || !ids.add(n.id)) throw bad("Identifiant de nœud dupliqué");
        for (Link l : links) {
            if (l == null || !ids.add(l.id) || l.from.equals(l.to)) throw bad("Lien invalide ou boucle locale");
            Node a = node(l.from), b = node(l.to);
            if (a == null || b == null || !a.kind.ports().contains(l.output) || !b.kind.ports().contains(l.input)) throw bad("Port ou nœud absent");
            if (!pairs.add(l.from + "/" + l.output + "/" + l.to + "/" + l.input)) throw bad("Connexion déjà présente");
        }
        // Even disabled design edges cannot hide a cycle. Monitoring edges terminate at a sink.
        Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
        for (Node n : nodes) visit(n.id, visited, visiting);
    }
    private void visit(String id, Set<String> visited, Set<String> visiting) {
        if (visiting.contains(id)) throw bad("Cycle refusé : le moteur ne représente pas cette topologie");
        if (!visited.add(id)) return;
        visiting.add(id); for (Link l : links) if (l.from.equals(id)) visit(l.to, visited, visiting); visiting.remove(id);
    }
    public Node node(String id) { for (Node n : nodes) if (n.id.equals(id)) return n; return null; }
    public Link link(String id) { for (Link l : links) if (l.id.equals(id)) return l; return null; }
    public GraphModel replace(Node n) { List<Node> out = new ArrayList<>(nodes); out.removeIf(x -> x.id.equals(n.id)); out.add(n); return with(out, links); }
    public GraphModel replace(Link l) { List<Link> out = new ArrayList<>(links); out.removeIf(x -> x.id.equals(l.id)); out.add(l); return with(nodes, out); }
    public GraphModel remove(String id) {
        List<Node> ns = new ArrayList<>(nodes); ns.removeIf(n -> n.id.equals(id));
        List<Link> ls = new ArrayList<>(links); ls.removeIf(l -> l.id.equals(id) || l.from.equals(id) || l.to.equals(id)); return with(ns, ls);
    }
    public GraphModel with(List<Node> ns, List<Link> ls) { return new GraphModel(ns, ls, profile, panX, panY, zoom, view); }
    public GraphModel profile(RoutingProfile p) { return new GraphModel(nodes, links, p, panX, panY, zoom, view); }
    public GraphModel camera(float x, float y, float z, ViewMode v) { return new GraphModel(nodes, links, profile, x, y, z, v); }
    public GraphModel layout() {
        Map<String, Integer> ranks = new HashMap<>(); for (Node n : nodes) rank(n.id, ranks);
        Map<Integer, Integer> rows = new HashMap<>(); List<Node> ns = new ArrayList<>();
        for (Node n : nodes) { int r = ranks.get(n.id), row = rows.getOrDefault(r, 0); rows.put(r, row + 1); ns.add(n.move(40 + r * 310, 40 + row * 230)); }
        return with(ns, links);
    }
    private int rank(String id, Map<String, Integer> ranks) {
        if (ranks.containsKey(id)) return ranks.get(id);
        int rank = 0; for (Link l : links) if (l.to.equals(id)) rank = Math.max(rank, rank(l.from, ranks) + 1);
        ranks.put(id, rank); return rank;
    }
    public static GraphModel initial(RoutingProfile p) {
        if(p.scope==RoutingProfile.Scope.ALL)return (p.mode==RoutingProfile.Mode.DIRECT?GraphTemplates.directCombined():GraphTemplates.create(4)).profile(p);
        Kind source = p.scope == RoutingProfile.Scope.WIFI ? Kind.HOTSPOT : p.scope == RoutingProfile.Scope.USB ? Kind.USB : Kind.PHONE_APPS;
        List<Node> ns = new ArrayList<>(); List<Link> ls = new ArrayList<>();
        Kind[] chain = p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL ? new Kind[]{source, Kind.FIREWALL, Kind.WIREGUARD, Kind.REMOTE_INTERNET} : new Kind[]{source, Kind.FIREWALL, Kind.INTERNET};
        for (int i = 0; i < chain.length; i++) {
            ns.add(new Node("n" + i, chain[i], chain[i].title, 40 + i * 310, 40, true, Properties.defaults()));
            if (i > 0) ls.add(new Link("e" + i, "n" + (i - 1), Port.OUT_IP, "n" + i, Port.IN_IP, true, Properties.defaults()));
        }
        if (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL) {
            ns.add(new Node("upstream", Kind.AUTO_UPLINK, Kind.AUTO_UPLINK.title, 970, 300, false, Properties.defaults()));
            ns.add(new Node("physical", Kind.INTERNET, "Internet · transport extérieur", 1280, 300, false, Properties.defaults()));
            ns.add(new Node("freebox", Kind.FREEBOX, "Freebox · extrémité déclarée", 1590, 300, false, Properties.defaults()));
            ls.add(new Link("transport", "n2", Port.TRANSPORT_OUT, "upstream", Port.UPLINK_IN, false, Properties.defaults()));
            ls.add(new Link("physicalLink", "upstream", Port.PHYSICAL_OUT, "physical", Port.PHYSICAL_IN, false, Properties.defaults()));
            ls.add(new Link("freeboxTransport", "physical", Port.PHYSICAL_OUT, "freebox", Port.PHYSICAL_IN, false, Properties.defaults()));
        }
        return new GraphModel(ns, ls, p, 0, 0, 1, ViewMode.LOGICAL);
    }
    private static void identifier(String id) { if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) throw bad("Identifiant invalide"); }
    private static void coordinate(float value) { if (!Float.isFinite(value) || Math.abs(value) > 100000) throw bad("Position invalide"); }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
