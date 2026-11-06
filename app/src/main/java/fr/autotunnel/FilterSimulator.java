package io.github.syldegil.autotunnel;

import java.util.*;

/** Filter-only symbolic packet evaluator. RETURN means Android still decides; never proof of delivery. */
public final class FilterSimulator {
    public enum Verdict { DROP, RETURN_TO_ANDROID }
    public static final class Packet {
        public final int family, port, sourcePort, uid, icmpType, hopLimit;
        public final String input, output, source, destination, protocol;
        public final boolean established;
        public Packet(int family, String input, String output, String source, String destination, String protocol,
                      int port, int sourcePort, int uid, boolean established) {
            this(family, input, output, source, destination, protocol, port, sourcePort, uid, established, -1, 0);
        }
        public Packet(int family, String input, String output, String source, String destination, String protocol,
                      int port, int sourcePort, int uid, boolean established, int icmpType, int hopLimit) {
            this.family = family; this.input = input; this.output = output; this.source = source; this.destination = destination;
            this.protocol = protocol; this.port = port; this.sourcePort = sourcePort; this.uid = uid; this.established = established;
            this.icmpType = icmpType; this.hopLimit = hopLimit;
        }
    }
    private final Map<String, List<List<String>>> chains = new HashMap<>();
    private final Map<String, Long> counts = new HashMap<>();
    public FilterSimulator(NetworkPlan plan, boolean opened) {
        for (int f : new int[]{4, 6}) for (String c : new String[]{"INPUT", "OUTPUT", "FORWARD"}) chains.put(f + c, new ArrayList<>());
        for (List<NetworkPlan.Operation> ops : Arrays.asList(plan.guards, plan.policy, opened ? plan.open : plan.close))
            for (NetworkPlan.Operation op : ops) load(op);
    }
    private void load(NetworkPlan.Operation op) {
        List<String> a = op.apply;
        if (op.tool == NetworkPlan.Tool.IP || !a.get(3).equals("filter")) return;
        String family = op.tool == NetworkPlan.Tool.IPTABLES ? "4" : "6", action = a.get(4), chain = family + a.get(5);
        if (action.equals("-N")) chains.put(chain, new ArrayList<>());
        else if (action.equals("-A")) chains.get(chain).add(new ArrayList<>(a.subList(6, a.size())));
        else if (action.equals("-I")) chains.get(chain).add(Integer.parseInt(a.get(6)) - 1, new ArrayList<>(a.subList(7, a.size())));
        else if (action.equals("-R")) chains.get(chain).set(Integer.parseInt(a.get(6)) - 1, new ArrayList<>(a.subList(7, a.size())));
    }
    public Verdict evaluate(String hook, Packet packet) { return visit(packet.family + hook, packet, 0); }
    public long matches() { long total = 0; for (long v : counts.values()) total += v; return total; }
    private Verdict visit(String chain, Packet p, int depth) {
        if (depth > 32 || !chains.containsKey(chain)) throw new IllegalStateException("Unsupported simulated target");
        int index = 0;
        for (List<String> rule : chains.get(chain)) {
            index++; if (!match(rule, p)) continue;
            counts.merge(chain + ":" + index, 1L, Long::sum);
            int jump = rule.indexOf("-j"), go = rule.indexOf("-g");
            String target = rule.get((jump >= 0 ? jump : go) + 1);
            if (target.equals("DROP")) return Verdict.DROP;
            if (target.equals("RETURN")) return Verdict.RETURN_TO_ANDROID;
            Verdict result = visit(p.family + target, p, depth + 1);
            if (result == Verdict.DROP || go >= 0) return result;
        }
        return Verdict.RETURN_TO_ANDROID;
    }
    private boolean match(List<String> a, Packet p) {
        for (int i = 0; i < a.size(); i++) {
            String flag = a.get(i);
            if (flag.equals("-j") || flag.equals("-g")) break;
            String value = a.get(++i);
            switch (flag) {
                case "-i": if (!value.equals(p.input)) return false; break;
                case "-o": if (!value.equals(p.output)) return false; break;
                case "-p": if (!value.equals(p.protocol)) return false; break;
                case "-s": if (!contains(value, p.source, p.family)) return false; break;
                case "-d": if (!contains(value, p.destination, p.family)) return false; break;
                case "--dport": if (!port(value, p.port)) return false; break;
                case "--sport": if (!port(value, p.sourcePort)) return false; break;
                case "--uid-owner": if (Integer.parseInt(value) != p.uid) return false; break;
                case "--ctstate": if (!p.established) return false; break;
                case "--icmpv6-type": if (Integer.parseInt(value) != p.icmpType) return false; break;
                case "--hl-eq": if (Integer.parseInt(value) != p.hopLimit) return false; break;
                case "-m": break;
                default: throw new IllegalStateException("Unsupported match in simulation");
            }
        }
        return true;
    }
    private boolean contains(String prefix, String address, int family) {
        if (!prefix.contains("/")) prefix += family == 4 ? "/32" : "/128";
        return new IpPrefix(prefix).contains(new IpPrefix(address + (family == 4 ? "/32" : "/128")));
    }
    private boolean port(String value, int port) {
        String[] range = value.split(":"); return port >= Integer.parseInt(range[0]) && port <= Integer.parseInt(range[range.length - 1]);
    }
}
