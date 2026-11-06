package io.github.syldegil.autotunnel;

import java.util.*;

/** Qualification is scoped to a path, exact environment and evidence; presence is not effectiveness. */
public final class CapabilityMatrix {
    public enum Capability { PHONE_IPV4_VPN, PHONE_IPV6_VPN, USB_IPV4_FORWARD, USB_IPV6_FORWARD,
        WIFI_IPV4_FORWARD, WIFI_IPV6_FORWARD, FIREWALL_IPV4, FIREWALL_IPV6, TETHER_OFFLOAD_SAFE,
        BOOT_FAIL_CLOSED, CONNTRACK_ISOLATION, DNS_PROTECTION, TUN_BINDING, FIRST_PACKET_GUARD,
        IPC_AUTHENTICATION, OWNED_ROLLBACK }
    public enum Status { UNSUPPORTED, UNKNOWN, SIMULATED, HARDWARE_OBSERVED, HARDWARE_VERIFIED, FAILED }
    public enum Path { PHONE_WIFI, PHONE_MOBILE, USB, WIFI }
    public static final class Evidence {
        public final Capability capability;
        public final Path path;
        public final Status status;
        public final String contextHash, artifactHash, testId;
        public final long at, expires;
        public Evidence(Capability capability, Path path, Status status, String contextHash, String artifactHash,
                        String testId, long at, long expires) {
            if (capability == null || path == null || status == null || !contextHash.matches("[0-9a-f]{64}")
                || !artifactHash.matches("[0-9a-f]{64}") || !testId.matches("[A-Z0-9_-]{1,64}") || at <= 0 || expires <= at)
                throw new IllegalArgumentException("Invalid evidence");
            this.capability = capability; this.path = path; this.status = status; this.contextHash = contextHash;
            this.artifactHash = artifactHash; this.testId = testId; this.at = at; this.expires = expires;
        }
    }
    private final Map<String, Evidence> records = new LinkedHashMap<>();
    private final Set<String> reviewed = new HashSet<>();
    private String key(Capability c, Path p) { return c.name() + "/" + p.name(); }
    /** Untrusted/local display import can NEVER authorize hardware, even if it says VERIFIED. */
    public synchronized void recordObservation(Evidence evidence) {
        String key = key(evidence.capability, evidence.path);
        reviewed.remove(key); records.put(key, evidence);
    }
    /** Root-owned evidence review adapter only. No UI/IPC/file import in this release calls this. */
    synchronized void recordReviewed(Evidence evidence) {
        recordObservation(evidence); reviewed.add(key(evidence.capability, evidence.path));
    }
    public synchronized Status status(Capability c, Path p, String context, long now) {
        Evidence e = records.get(key(c, p));
        if (e == null || !e.contextHash.equals(context) || now < e.at || now >= e.expires) return Status.UNKNOWN;
        if (e.status == Status.HARDWARE_VERIFIED && !reviewed.contains(key(c, p))) return Status.HARDWARE_OBSERVED;
        return e.status;
    }
    public synchronized List<Evidence> records() { return new ArrayList<>(records.values()); }
    public List<Capability> requirements(RoutingProfile p, Path path) {
        List<Capability> out = new ArrayList<>(Arrays.asList(Capability.FIREWALL_IPV4, Capability.FIREWALL_IPV6,
            Capability.FIRST_PACKET_GUARD, Capability.CONNTRACK_ISOLATION, Capability.IPC_AUTHENTICATION, Capability.OWNED_ROLLBACK));
        if (path == Path.USB || path == Path.WIFI) out.add(Capability.TETHER_OFFLOAD_SAFE);
        if (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL) {
            Collections.addAll(out, Capability.BOOT_FAIL_CLOSED, Capability.TUN_BINDING, Capability.DNS_PROTECTION);
            out.add(path == Path.USB ? Capability.USB_IPV4_FORWARD : path == Path.WIFI ? Capability.WIFI_IPV4_FORWARD : Capability.PHONE_IPV4_VPN);
            if (p.ipv6 == RoutingProfile.V6.REQUIRE_TUNNEL)
                out.add(path == Path.USB ? Capability.USB_IPV6_FORWARD : path == Path.WIFI ? Capability.WIFI_IPV6_FORWARD : Capability.PHONE_IPV6_VPN);
        }
        return out;
    }
    public boolean permits(RoutingProfile p, Collection<Path> paths, String context, long now, boolean explicitConsent) {
        if (!explicitConsent || paths.isEmpty()) return false;
        boolean phone = paths.contains(Path.PHONE_WIFI) || paths.contains(Path.PHONE_MOBILE);
        if (p.scope == RoutingProfile.Scope.USB && (paths.size() != 1 || !paths.contains(Path.USB))
            || p.scope == RoutingProfile.Scope.WIFI && (paths.size() != 1 || !paths.contains(Path.WIFI))
            || p.scope == RoutingProfile.Scope.PHONE && (!phone || paths.contains(Path.USB) || paths.contains(Path.WIFI))
            || p.scope == RoutingProfile.Scope.ALL && (!phone || !paths.contains(Path.USB) || !paths.contains(Path.WIFI))) return false;
        for (Path path : paths) for (Capability c : requirements(p, path))
            if (status(c, path, context, now) != Status.HARDWARE_VERIFIED) return false;
        return true;
    }
    public static Set<Path> paths(NetworkPlan plan) {
        Set<Path> out = EnumSet.noneOf(Path.class);
        for (RoutingProfile profile:plan.profiles) for (RoutingProfile.Path p : NetworkSnapshot.paths(profile.scope)) {
            if (p == RoutingProfile.Path.USB) out.add(Path.USB);
            else if (p == RoutingProfile.Path.WIFI) out.add(Path.WIFI);
            else {
                // Ambiguous upstream requires both rather than silently inheriting one qualification.
                out.add(Path.PHONE_WIFI); out.add(Path.PHONE_MOBILE);
            }
        }
        return out;
    }
    public Map<Path,List<Capability>> requirements(RoutingTopology topology){
        Map<Path,List<Capability>> out=new EnumMap<>(Path.class);
        for(RoutingTopology.Branch b:topology.branches){
            if(b.profile.scope==RoutingProfile.Scope.PHONE){out.put(Path.PHONE_WIFI,requirements(b.profile,Path.PHONE_WIFI));out.put(Path.PHONE_MOBILE,requirements(b.profile,Path.PHONE_MOBILE));}
            else{Path path=b.profile.scope==RoutingProfile.Scope.USB?Path.USB:Path.WIFI;out.put(path,requirements(b.profile,path));}
        }return out;
    }
    public boolean permits(RoutingTopology topology,String context,long now,boolean consent){
        if(!consent)return false;
        for(Map.Entry<Path,List<Capability>> e:requirements(topology).entrySet())for(Capability c:e.getValue())if(status(c,e.getKey(),context,now)!=Status.HARDWARE_VERIFIED)return false;
        return true;
    }
    public String report(String context, long now) {
        StringBuilder out = new StringBuilder("Capacités DS015 · aucune qualification par héritage\n");
        for (Path path : Path.values()) {
            out.append('\n').append(path);
            for (Capability c : Capability.values()) out.append('\n').append(c).append(" = ").append(status(c, path, context, now));
        }
        return out.toString();
    }
}
