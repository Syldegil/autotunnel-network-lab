package io.github.syldegil.autotunnel;

import java.io.*;
import java.util.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

/** Versioned binary allowlist, no object deserialization and no WG configuration. */
public final class GraphCodec {
    public static final int MAX_BYTES = 262144;
    private GraphCodec() { }
    public static byte[] encode(GraphModel g) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x41544732); out.writeInt(1); profile(out, g.profile);
        out.writeFloat(g.panX); out.writeFloat(g.panY); out.writeFloat(g.zoom); out.writeUTF(g.view.name());
        out.writeInt(g.nodes.size());
        for (Node n : g.nodes) {
            out.writeUTF(n.id); out.writeUTF(n.kind.name()); out.writeUTF(n.label); out.writeFloat(n.x); out.writeFloat(n.y);
            out.writeBoolean(n.enabled); properties(out, n.properties);
        }
        out.writeInt(g.links.size());
        for (Link l : g.links) {
            out.writeUTF(l.id); out.writeUTF(l.from); out.writeUTF(l.output.name()); out.writeUTF(l.to); out.writeUTF(l.input.name());
            out.writeBoolean(l.enabled); properties(out, l.properties);
        }
        out.flush(); if (bytes.size() > MAX_BYTES) throw new IOException("Graphe trop volumineux"); return bytes.toByteArray();
    }
    public static GraphModel decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("Taille invalide");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x41544732 || in.readInt() != 1) throw new IOException("Version inconnue");
            RoutingProfile p = profile(in); float x = in.readFloat(), y = in.readFloat(), zoom = in.readFloat(); ViewMode view = ViewMode.valueOf(in.readUTF());
            List<Node> nodes = new ArrayList<>(); int count = count(in, 100);
            for (int i = 0; i < count; i++) nodes.add(new Node(in.readUTF(), Kind.valueOf(in.readUTF()), in.readUTF(), in.readFloat(), in.readFloat(), in.readBoolean(), properties(in)));
            List<Link> links = new ArrayList<>(); count = count(in, 200);
            for (int i = 0; i < count; i++) links.add(new Link(in.readUTF(), in.readUTF(), Port.valueOf(in.readUTF()), in.readUTF(), Port.valueOf(in.readUTF()), in.readBoolean(), properties(in)));
            if (in.available() != 0) throw new IOException("Données supplémentaires");
            return new GraphModel(nodes, links, p, x, y, zoom, view);
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Graphe invalide", e); }
    }
    private static void properties(DataOutputStream out, Properties p) throws IOException {
        out.writeUTF(p.family == null ? "INHERIT" : p.family.name()); out.writeUTF(p.policy.name()); out.writeBoolean(p.blockUnavailable);
        out.writeUTF(p.source); out.writeUTF(p.destination); out.writeUTF(p.inputInterface); out.writeUTF(p.outputInterface);
        strings(out, p.dns); strings(out, p.routes4); strings(out, p.routes6);
    }
    private static Properties properties(DataInputStream in) throws IOException {
        String family = in.readUTF(); return new Properties(family.equals("INHERIT") ? null : RoutingProfile.Family.valueOf(family), Policy.valueOf(in.readUTF()), in.readBoolean(),
            in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), strings(in), strings(in), strings(in));
    }
    private static void strings(DataOutputStream out, List<String> values) throws IOException { out.writeInt(values.size()); for (String s : values) out.writeUTF(s); }
    private static List<String> strings(DataInputStream in) throws IOException { int count = count(in, 128); List<String> out = new ArrayList<>(); for (int i = 0; i < count; i++) out.add(in.readUTF()); return out; }
    static int count(DataInputStream in, int max) throws IOException { int n = in.readInt(); if (n < 0 || n > max) throw new IOException("Limite dépassée"); return n; }
    private static void profile(DataOutputStream out, RoutingProfile p) throws IOException {
        out.writeUTF(p.scope.name()); out.writeUTF(p.mode.name()); out.writeUTF(p.ipv6.name()); strings(out, p.routes4); strings(out, p.routes6); strings(out, p.dns);
        out.writeInt(p.rules.size());
        for (RoutingProfile.Rule r : p.rules) {
            out.writeUTF(r.direction.name()); out.writeUTF(r.protocol.name()); out.writeUTF(r.family.name()); out.writeUTF(r.path.name()); out.writeUTF(r.action.name());
            out.writeInt(r.firstPort); out.writeInt(r.lastPort); out.writeUTF(r.source); out.writeUTF(r.destination); out.writeInt(r.priority); out.writeBoolean(r.enabled);
            out.writeUTF(r.inputInterface); out.writeUTF(r.outputInterface);
        }
    }
    private static RoutingProfile profile(DataInputStream in) throws IOException {
        RoutingProfile.Scope scope = RoutingProfile.Scope.valueOf(in.readUTF()); RoutingProfile.Mode mode = RoutingProfile.Mode.valueOf(in.readUTF()); RoutingProfile.V6 v6 = RoutingProfile.V6.valueOf(in.readUTF());
        List<String> r4 = strings(in), r6 = strings(in), dns = strings(in); List<RoutingProfile.Rule> rules = new ArrayList<>(); int count = count(in, 64);
        for (int i = 0; i < count; i++) rules.add(new RoutingProfile.Rule(RoutingProfile.Direction.valueOf(in.readUTF()), RoutingProfile.Protocol.valueOf(in.readUTF()),
            RoutingProfile.Family.valueOf(in.readUTF()), RoutingProfile.Path.valueOf(in.readUTF()), RoutingProfile.Action.valueOf(in.readUTF()),
            in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readInt(), in.readBoolean(), in.readUTF(), in.readUTF()));
        return new RoutingProfile(scope, mode, v6, r4, r6, dns, rules);
    }
}
