package io.github.syldegil.autotunnel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compiler-produced operations only. Not accepted as IPC input. */
public final class NetworkPlan {
    public enum Tool { IPTABLES, IP6TABLES, IP }
    public enum Check { EXIT_ZERO, TOKENS, RULE_AT_INDEX, ROUTE, RULE }
    public static final class Operation {
        public final String id;
        public final Tool tool;
        public final List<String> apply, undo, inspect, expected;
        public final Check check;
        public final int position;
        Operation(String id, Tool tool, List<String> apply, List<String> undo, List<String> inspect, Check check, List<String> expected) {
            this(id, tool, apply, undo, inspect, check, expected, 0);
        }
        Operation(String id, Tool tool, List<String> apply, List<String> undo, List<String> inspect, Check check, List<String> expected, int position) {
            this.id = id; this.tool = tool; this.apply = immutable(apply); this.undo = immutable(undo);
            this.inspect = immutable(inspect); this.check = check; this.expected = immutable(expected);
            this.position = position;
        }
        public boolean matches(int code, String output) {
            if (code != 0) return false;
            if (check == Check.EXIT_ZERO) return true;
            if (check == Check.RULE) {
                String priority = apply.get(apply.indexOf("priority") + 1) + ":";
                long samePriority = Arrays.stream(output.split("\n"))
                    .map(String::trim).filter(line -> line.split("\\s+", 2)[0].equals(priority)).count();
                if (samePriority != 1) return false;
            }
            int ruleIndex = 0;
            for (String line : output.split("\n")) {
                List<String> words = Arrays.asList(line.trim().split("\\s+"));
                if (check == Check.RULE) {
                    String priority = apply.get(apply.indexOf("priority") + 1) + ":";
                    if (!words.get(0).equals(priority)) continue;
                    List<String> selectors = new ArrayList<>(apply.subList(apply.indexOf("priority") + 2, apply.size()));
                    if (!selectors.contains("from")) { selectors.add("from"); selectors.add("all"); }
                    List<String> observed = new ArrayList<>(words.subList(1, words.size()));
                    if (rpdbSelectors(selectors).equals(rpdbSelectors(observed)) && !rpdbSelectors(selectors).contains("INVALID")) return true;
                    continue;
                }
                if (check == Check.ROUTE) {
                    if (routeMatches(words)) return true;
                    continue;
                }
                if (check == Check.RULE_AT_INDEX) {
                    if (words.size() > 1 && words.get(0).equals("-A") && words.get(1).equals(expected.get(1))) {
                        ruleIndex++;
                        if (ruleIndex == position) return canonicalRule(words).equals(canonicalRule(expected));
                    }
                    continue;
                }
                if (Collections.indexOfSubList(words, expected) >= 0) return true;
            }
            return false;
        }
        private boolean routeMatches(List<String> words) {
            boolean unreachable = apply.get(3).equals("unreachable"), ipv6 = apply.get(0).equals("-6");
            int offset = unreachable ? 1 : 0;
            if (unreachable && (words.isEmpty() || !words.get(0).equals("unreachable"))) return false;
            if (words.size() <= offset || !samePrefix(apply.get(3 + offset), words.get(offset))) return false;
            Map<String, String> attributes = new HashMap<>();
            for (int i = offset + 1; i < words.size(); i += 2) {
                if (i + 1 >= words.size() || attributes.put(words.get(i), words.get(i + 1)) != null) return false;
            }
            int metric = apply.indexOf("metric");
            if (metric < 0 || !apply.get(metric + 1).equals(attributes.remove("metric"))) return false;
            int dev = apply.indexOf("dev");
            String observedDev = attributes.remove("dev");
            if (dev >= 0 ? !apply.get(dev + 1).equals(observedDev)
                : observedDev != null && !(ipv6 && unreachable && observedDev.equals("lo"))) return false;
            // These are defaults observed on the isolated Linux kernel, not arbitrary
            // extra selectors. Gateway/source/MTU/unknown attributes change ownership.
            String scope = attributes.remove("scope"), proto = attributes.remove("proto"), pref = attributes.remove("pref");
            if (scope != null && !(scope.equals("link") && !ipv6 && !unreachable)) return false;
            if (proto != null && !proto.equals("boot")) return false;
            if (pref != null && !(ipv6 && pref.equals("medium"))) return false;
            return attributes.isEmpty();
        }
        private List<String> rpdbSelectors(List<String> tokens) {
            List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (int i = 0; i < tokens.size(); i++) {
                String flag = tokens.get(i);
                if (!seen.add(flag)) return Collections.singletonList("INVALID");
                if (flag.equals("prohibit")) { result.add(flag); continue; }
                if (!Arrays.asList("from", "to", "iif", "lookup").contains(flag) || ++i >= tokens.size())
                    return Collections.singletonList("INVALID");
                String value = tokens.get(i);
                if ((flag.equals("from") || flag.equals("to")) && !value.equals("all")) {
                    try { value = new IpPrefix(value).network(); } catch (IllegalArgumentException e) { return Collections.singletonList("INVALID"); }
                }
                result.add(flag + "=" + value);
            }
            Collections.sort(result); return result;
        }
        private boolean samePrefix(String a, String b) {
            if (a.equals("default")) a = apply.get(0).equals("-6") ? "::/0" : "0.0.0.0/0";
            if (b.equals("default")) b = apply.get(0).equals("-6") ? "::/0" : "0.0.0.0/0";
            try { return new IpPrefix(a).network().equals(new IpPrefix(b).network()); }
            catch (IllegalArgumentException e) { return false; }
        }
        private List<String> canonicalRule(List<String> words) {
            // iptables-save reorders conjunctive selectors (e.g. -s before -p).
            // Preserve every option/value and attached negation, but not their presentation order.
            List<String> result = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                boolean negated = words.get(i).equals("!");
                if (negated && ++i >= words.size()) return Collections.singletonList("INVALID:" + words);
                String option = words.get(i);
                if (!option.startsWith("-") || i + 1 >= words.size()) return Collections.singletonList("INVALID:" + words);
                String value = words.get(++i);
                if (option.equals("-m") && !negated && Arrays.asList("tcp", "udp", "icmp", "icmp6").contains(value)) continue;
                if (option.equals("-s") || option.equals("-d")) {
                    if (!value.contains("/")) value += value.contains(":") ? "/128" : "/32";
                    try { value = new IpPrefix(value).network(); }
                    catch (IllegalArgumentException e) { return Collections.singletonList("INVALID:" + words); }
                    if (!negated && (value.equals("0.0.0.0/0") || value.equals("::/0"))) continue;
                }
                result.add((negated ? "! " : "") + option + " " + value);
            }
            Collections.sort(result);
            return result;
        }
        public String preview() { return tool + " " + String.join(" ", apply); }
    }
    public final RoutingProfile profile;
    public final NetworkSnapshot snapshot;
    public final List<Operation> guards, policy, open, close;
    public final String digest;
    public final List<RoutingProfile> profiles;
    NetworkPlan(RoutingProfile profile, NetworkSnapshot snapshot, List<Operation> guards,
                 List<Operation> policy, List<Operation> open, List<Operation> close) {
        this(profile,snapshot,guards,policy,open,close,Collections.singletonList(profile));
    }
    NetworkPlan(RoutingProfile profile, NetworkSnapshot snapshot, List<Operation> guards,
                List<Operation> policy, List<Operation> open, List<Operation> close,List<RoutingProfile> profiles) {
        this.profile = profile; this.snapshot = snapshot;
        this.profiles=immutable(profiles);
        this.guards = immutable(guards); this.policy = immutable(policy); this.open = immutable(open); this.close = immutable(close);
        StringBuilder canonical = new StringBuilder(snapshot.identity()).append(profile.mode).append(profile.ipv6);
        for(RoutingProfile p:profiles)canonical.append(p.scope).append(RoutePolicy.plan(p));
        for (List<Operation> ops : Arrays.asList(this.guards, this.policy, this.open, this.close))
            for (Operation op : ops) canonical.append(op.tool).append(op.apply).append(op.undo);
        digest = hash(canonical.toString());
    }
    static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public String preview() {
        StringBuilder out = new StringBuilder(snapshot.simulated ? "SIMULATION · interfaces de laboratoire\n" : "PROPOSITION · non appliquée\n");
        out.append("Plan ").append(digest).append("\nSouhaité : ").append(profile.mode).append(" / IPv6 ").append(profile.ipv6);
        for(RoutingProfile p:profiles)out.append("\nBranche ").append(p.scope).append(" : ").append(p.mode).append(" / IPv6 ").append(p.ipv6);
        int n = 0;
        for (List<Operation> ops : Arrays.asList(guards, policy, open)) {
            out.append("\n\n").append(new String[]{"FERMER / barrières", "PRÉPARER / filtrage et routage", "OUVRIR / après preuves"}[n++]);
            for (Operation op : ops) {
                String command = op.preview();
                if (!snapshot.endpoint.isEmpty()) command = command.replace(snapshot.endpoint, "<endpoint-WG>");
                out.append("\n").append(op.id).append(": ").append(command);
            }
        }
        return out.append("\n\nAppliqué : non. Efficacité du parcours : non démontrée. Aucun NAT66. Aucun changement de clé/pair/DNS WireGuard.").toString();
    }
    public List<String> blockers(NetworkSnapshot current,long now){List<String> reasons=new ArrayList<>();for(RoutingProfile p:profiles)for(String reason:current.blockers(p,now))reasons.add(p.scope+": "+reason);return reasons;}
}
