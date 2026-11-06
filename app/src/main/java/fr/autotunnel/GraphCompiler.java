package io.github.syldegil.autotunnel;

import java.util.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

/** Bounded DAG lowering. Convergence shares one tunnel; unsupported active intent is never discarded. */
public final class GraphCompiler {
    public static final boolean HARDWARE_ACTIVATION_ENABLED=false;
    public static final class Result {
        public final RoutingProfile profile; public final RoutingTopology topology; public final List<String> errors;
        Result(RoutingTopology topology,List<String> errors){this.topology=topology;this.profile=topology==null?null:topology.summary();this.errors=NetworkPlan.immutable(errors);}
        public boolean valid(){return topology!=null&&errors.isEmpty();}
        public String report(){return valid()?topology.report():"Compilation refusée\n• "+String.join("\n• ",errors);}
    }
    private static boolean source(Kind k){return k==Kind.PHONE_APPS||k==Kind.USB||k==Kind.HOTSPOT;}
    private static boolean sink(Kind k){return k==Kind.INTERNET||k==Kind.REMOTE_INTERNET||k==Kind.HOME_LAN;}
    private static RoutingProfile.Scope scope(Kind k){return k==Kind.USB?RoutingProfile.Scope.USB:k==Kind.HOTSPOT?RoutingProfile.Scope.WIFI:RoutingProfile.Scope.PHONE;}
    public static Result compile(GraphModel g){
        List<String> errors=new ArrayList<>();Map<String,List<Link>> edges=new LinkedHashMap<>();List<Node> sources=new ArrayList<>();Set<String> wg=new HashSet<>();
        for(Node n:g.nodes)if(n.enabled){edges.put(n.id,new ArrayList<>());if(source(n.kind))sources.add(n);if(n.kind==Kind.WIREGUARD)wg.add(n.id);}
        for(Link l:g.links)if(l.enabled){if(!edges.containsKey(l.from)||!edges.containsKey(l.to))errors.add(l.id+": lien actif sur nœud descriptif");else edges.get(l.from).add(l);if(l.output.type!=0)errors.add(l.id+": transport physique/mesure descriptif seulement");}
        if(wg.size()>1)errors.add("Plusieurs propriétaires WireGuard : convergez vers le même nœud unique");
        if(sources.isEmpty())errors.add("Source téléphone, USB ou hotspot absente");
        if(!errors.isEmpty())return new Result(null,errors);
        Set<String> visited=new HashSet<>();List<RoutingTopology.Branch> branches=new ArrayList<>();
        for(Node src:sources){
            RoutingProfile.Scope scope=scope(src.kind);
            if(g.profile.scope!=RoutingProfile.Scope.ALL&&g.profile.scope!=scope){errors.add(src.id+": choisissez le parcours combiné ALL pour plusieurs sources");continue;}
            for(Link l:g.links)if(l.enabled&&l.to.equals(src.id))errors.add(src.id+": une source ne reçoit pas de trafic logique");
            List<List<Node>> paths=new ArrayList<>();List<List<Link>> pathLinks=new ArrayList<>();
            enumerate(g,src,edges,new ArrayList<>(),new ArrayList<>(),paths,pathLinks,errors,new int[]{0});
            RoutingProfile compiled=null;String tunnel="";Set<String> branchNodes=new LinkedHashSet<>(),destinations=new LinkedHashSet<>();
            for(int i=0;i<paths.size();i++){
                List<Node> path=paths.get(i);List<Properties> props=new ArrayList<>();int fw=0;boolean after=false;String pathTunnel="";List<String> dns=new ArrayList<>(g.profile.dns),r4=new ArrayList<>(g.profile.routes4),r6=new ArrayList<>(g.profile.routes6);
                for(Node n:path){visited.add(n.id);branchNodes.add(n.id);props.add(n.properties);
                    if(after&&!sink(n.kind)&&n.kind!=Kind.FREEBOX)errors.add(n.id+": traitement distant après WG non représentable");
                    switch(n.kind){
                        case PHONE_APPS:case USB:case HOTSPOT:case INTERNET:case REMOTE_INTERNET:case HOME_LAN:case ROUTER:case DNS:case IPV6:case KILL_SWITCH:break;
                        case FREEBOX:if(!after)errors.add(n.id+": Freebox logique exige un tunnel en amont");break;
                        case FIREWALL:fw++;break;
                        case WIREGUARD:after=true;pathTunnel=n.id;break;
                        default:errors.add(n.id+": fonction active non représentable; conserver descriptive");
                    }
                    if(!n.properties.dns.isEmpty()){if(n.kind!=Kind.DNS||!dns.isEmpty())errors.add(n.id+": propriétaire DNS ambigu");else dns.addAll(n.properties.dns);}
                    if(!n.properties.routes4.isEmpty()||!n.properties.routes6.isEmpty()){
                        if(n.kind!=Kind.ROUTER||!r4.isEmpty()||!r6.isEmpty())errors.add(n.id+": propriétaire routes ambigu; utiliser un routeur local");else{r4.addAll(n.properties.routes4);r6.addAll(n.properties.routes6);}
                    }
                }
                for(Link l:pathLinks.get(i)){props.add(l.properties);if(!l.properties.dns.isEmpty()||!l.properties.routes4.isEmpty()||!l.properties.routes6.isEmpty())errors.add(l.id+": DNS/routes par lien non représentables; utiliser nœuds DNS/routeur");}
                Set<Policy> policies=new HashSet<>();Set<RoutingProfile.Family> families=new HashSet<>();
                for(Properties p:props){if(p.policy!=Policy.INHERIT)policies.add(p.policy);if(p.family!=null)families.add(p.family);
                    if(!p.blockUnavailable)errors.add(src.id+": repli permissif non représentable");
                    if(!p.source.isEmpty()||!p.destination.isEmpty()||!p.inputInterface.isEmpty()||!p.outputInterface.isEmpty())errors.add(src.id+": sélecteurs locaux non représentables; utiliser les règles typées");}
                if(policies.size()>1)errors.add(src.id+": politiques DIRECT/WG contradictoires sur le même chemin");
                if(families.size()>1||families.contains(RoutingProfile.Family.IPV6))errors.add(src.id+": familles contradictoires ou IPv6 seul non représentable");
                RoutingProfile.Mode mode=policies.isEmpty()?g.profile.mode:policies.contains(Policy.DIRECT)?RoutingProfile.Mode.DIRECT:RoutingProfile.Mode.REQUIRE_TUNNEL;
                RoutingProfile.V6 v6=families.isEmpty()?g.profile.ipv6:families.contains(RoutingProfile.Family.BOTH)?RoutingProfile.V6.REQUIRE_TUNNEL:RoutingProfile.V6.BLOCK;
                if((mode==RoutingProfile.Mode.REQUIRE_TUNNEL)!=!pathTunnel.isEmpty())errors.add(src.id+": route protégée sans WG ou sortie directe traversant WG");
                for(Node n:path)if(n.kind==Kind.KILL_SWITCH&&mode!=RoutingProfile.Mode.REQUIRE_TUNNEL)errors.add(n.id+": protection obligatoire incompatible avec exception directe");
                if(fw>1)errors.add(src.id+": plusieurs pare-feux séquentiels avec le même jeu de règles");
                List<RoutingProfile.Rule> rules=new ArrayList<>();for(RoutingProfile.Rule r:g.profile.rules)if(r.path.name().equals(scope.name()))rules.add(r);
                if(!rules.isEmpty()&&fw!=1)errors.add(src.id+": règles sans pare-feu sur le chemin");
                if(mode==RoutingProfile.Mode.DIRECT&&(!dns.isEmpty()||!r4.isEmpty()||!r6.isEmpty()))errors.add(src.id+": le mode direct ne programme pas DNS/routes explicites");
                try{
                    RoutingProfile next=new RoutingProfile(scope,mode,v6,r4,r6,dns,rules);
                    if(compiled!=null&&!RoutePolicy.plan(compiled).equals(RoutePolicy.plan(next)))errors.add(src.id+": destinations divergent en politique/routes; sélecteurs exclusifs non représentés");
                    compiled=next;tunnel=pathTunnel;destinations.add(path.get(path.size()-1).id);
                }catch(IllegalArgumentException e){errors.add(src.id+": "+e.getMessage());}
            }
            if(compiled!=null)try{branches.add(new RoutingTopology.Branch(src.id,tunnel,compiled,new ArrayList<>(branchNodes),new ArrayList<>(destinations)));}catch(IllegalArgumentException e){errors.add(src.id+": "+e.getMessage());}
        }
        for(String id:edges.keySet())if(!visited.contains(id))errors.add(id+": nœud actif isolé ou chemin impossible");
        Set<RoutingProfile.Path> used=new HashSet<>();for(RoutingTopology.Branch b:branches)used.add(RoutingProfile.Path.valueOf(b.profile.scope.name()));
        for(RoutingProfile.Rule r:g.profile.rules)if(r.enabled&&!used.contains(r.path))errors.add("Règle active pour branche absente : "+r.path);
        RoutingTopology topology=null;if(errors.isEmpty())try{topology=new RoutingTopology(branches);}catch(IllegalArgumentException e){errors.add(e.getMessage());}
        return new Result(topology,errors);
    }
    private static void enumerate(GraphModel g,Node node,Map<String,List<Link>> edges,List<Node> before,List<Link> links,List<List<Node>> paths,List<List<Link>> pathLinks,List<String> errors,int[] visits){
        if(++visits[0]>4096){if(visits[0]==4097)errors.add("Budget de parcours dépassé (4096 visites par source)");return;}
        if(paths.size()>=64){errors.add("Limite de64chemins atteinte");return;}
        List<Node> path=new ArrayList<>(before);path.add(node);List<Link> next=edges.get(node.id);
        if(sink(node.kind)){if(!next.isEmpty())errors.add(node.id+": destination avec sortie logique");paths.add(path);pathLinks.add(links);return;}
        if(next.isEmpty()){errors.add(node.id+": chemin interrompu sans destination");return;}
        for(Link l:next){List<Link> ls=new ArrayList<>(links);ls.add(l);enumerate(g,g.node(l.to),edges,path,ls,paths,pathLinks,errors,visits);}
    }
    public static NetworkPlan preview(GraphModel graph,NetworkEngine engine)throws Exception{Result result=compile(graph);if(!result.valid())throw bad(result.report());return engine.preview(result.topology);}
}
