package io.github.syldegil.autotunnel;

import java.util.*;

/** Multiple logical paths, one current GoBackend owner. No tunnel creation operation exists here. */
public final class RoutingTopology {
    public static final class Branch {
        public final String source, tunnel;
        public final RoutingProfile profile;
        public final List<String> nodes, destinations;
        public Branch(String source,String tunnel,RoutingProfile profile,List<String> nodes,List<String> destinations) {
            if(source==null||tunnel==null||profile==null||profile.scope==RoutingProfile.Scope.ALL||destinations.isEmpty())throw new IllegalArgumentException("Branche incomplète");
            if((profile.mode==RoutingProfile.Mode.REQUIRE_TUNNEL)!=!tunnel.isEmpty())throw new IllegalArgumentException("Dépendance WireGuard incohérente");
            this.source=source;this.tunnel=tunnel;this.profile=profile;this.nodes=NetworkPlan.immutable(nodes);this.destinations=NetworkPlan.immutable(destinations);
        }
    }
    public final List<Branch> branches;
    public RoutingTopology(List<Branch> branches) {
        if(branches==null||branches.isEmpty()||branches.size()>3)throw new IllegalArgumentException("Une à trois sources téléphone/USB/hotspot sont requises");
        Set<RoutingProfile.Scope> scopes=new HashSet<>();Set<String> tunnels=new HashSet<>();
        for(Branch b:branches){if(!scopes.add(b.profile.scope))throw new IllegalArgumentException("Sources ambiguës pour "+b.profile.scope);if(!b.tunnel.isEmpty())tunnels.add(b.tunnel);}
        if(tunnels.size()>1)throw new IllegalArgumentException("Un seul propriétaire/nœud WireGuard partagé autorisé");
        List<Branch> sorted=new ArrayList<>(branches);sorted.sort(Comparator.comparingInt(b->b.profile.scope.ordinal()));this.branches=NetworkPlan.immutable(sorted);
    }
    public RoutingProfile summary() {
        if(branches.size()==1)return branches.get(0).profile;
        boolean required=false,v6=false;List<RoutingProfile.Rule> rules=new ArrayList<>();
        for(Branch b:branches){required|=b.profile.mode==RoutingProfile.Mode.REQUIRE_TUNNEL;v6|=b.profile.ipv6==RoutingProfile.V6.REQUIRE_TUNNEL;rules.addAll(b.profile.rules);}
        return new RoutingProfile(RoutingProfile.Scope.ALL,required?RoutingProfile.Mode.REQUIRE_TUNNEL:RoutingProfile.Mode.DIRECT,
            v6?RoutingProfile.V6.REQUIRE_TUNNEL:RoutingProfile.V6.BLOCK,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),rules);
    }
    public String report(){StringBuilder s=new StringBuilder("Topologie déclarative · "+branches.size()+" branche(s) · NON APPLIQUÉE\n");for(Branch b:branches)s.append("\n").append(b.profile.scope).append(" : ").append(b.source).append(" → ").append(b.destinations).append("\nWG unique : ").append(b.tunnel.isEmpty()?"exception directe explicite":b.tunnel).append("\n").append(RoutePolicy.plan(b.profile));return s.toString();}
    public List<String> blockers(NetworkSnapshot snapshot,long now){List<String> out=new ArrayList<>();for(Branch b:branches)for(String reason:snapshot.blockers(b.profile,now))out.add(b.profile.scope+": "+reason);return out;}
}
