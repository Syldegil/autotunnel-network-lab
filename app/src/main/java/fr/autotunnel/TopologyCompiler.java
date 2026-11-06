package io.github.syldegil.autotunnel;

import java.util.*;
import static io.github.syldegil.autotunnel.NetworkPlan.*;

/** One all-or-nothing transaction, disjoint owned namespaces and tables, single shared VPN binding. */
public final class TopologyCompiler {
    public NetworkPlan compile(RoutingTopology topology,NetworkSnapshot snapshot){
        if(topology.branches.size()==1)return new NetfilterCompiler().compile(topology.branches.get(0).profile,snapshot);
        List<Operation> guards=new ArrayList<>(),policy=new ArrayList<>(),open=new ArrayList<>(),close=new ArrayList<>();
        List<RoutingProfile> profiles=new ArrayList<>();Map<RoutingProfile.Scope,String> interfaces=new LinkedHashMap<>();
        Set<String> selected=new HashSet<>();List<IpPrefix> prefixes=new ArrayList<>();
        long owners=snapshot.links.stream().filter(l->l.role==NetworkSnapshot.Role.VPN).count();
        if(owners>1)throw new IllegalArgumentException("Plusieurs interfaces VPN candidates; propriétaire unique non établi");
        for(RoutingTopology.Branch b:topology.branches){
            RoutingProfile.Scope scope=b.profile.scope;profiles.add(b.profile);
            if(scope!=RoutingProfile.Scope.PHONE){
                NetworkSnapshot.Link link=snapshot.link(scope==RoutingProfile.Scope.USB?NetworkSnapshot.Role.USB_CLIENTS:NetworkSnapshot.Role.WIFI_CLIENTS);
                if(!selected.add(link.name))throw new IllegalArgumentException("Branches liées à la même interface cliente");
                IpPrefix prefix=new IpPrefix(link.v4);for(IpPrefix existing:prefixes)if(existing.contains(prefix)||prefix.contains(existing))throw new IllegalArgumentException("Préfixes clients se chevauchant");prefixes.add(prefix);interfaces.put(scope,link.name);
            }
            NetworkPlan one=new NetfilterCompiler().compile(b.profile,snapshot);
            for(Operation op:one.guards)if(!(op.apply.contains("filter")&&op.apply.contains("-I")))guards.add(rename(op,scope));
            for(Operation op:one.policy)policy.add(rename(op,scope));for(Operation op:one.open)open.add(rename(op,scope));for(Operation op:one.close)close.add(rename(op,scope));
        }
        // A shared dispatch chain uses goto: a client RETURN never falls into the phone's global guard.
        for(int family:new int[]{6,4})for(String hook:new String[]{"INPUT","OUTPUT","FORWARD"}){
            if(hook.equals("FORWARD")&&interfaces.isEmpty())continue;
            String chain="AT16_D"+family+hook.charAt(0);NetworkPlan.Tool tool=family==4?Tool.IPTABLES:Tool.IP6TABLES;
            guards.add(op(chain+"_create",tool,args("-N",chain),args("-X",chain),args("-S",chain),Check.EXIT_ZERO,Collections.emptyList(),0));
            int index=0;
            if(hook.equals("FORWARD"))for(String a:interfaces.values())for(String b:interfaces.values())if(!a.equals(b))guards.add(rule(chain,tool,++index,"-i",a,"-o",b,"-j","DROP"));
            for(Map.Entry<RoutingProfile.Scope,String> e:interfaces.entrySet()){
                String gate=namespace(e.getKey())+"G"+family+hook.charAt(0);
                if(!hook.equals("OUTPUT"))guards.add(rule(chain,tool,++index,"-i",e.getValue(),"-g",gate));
                if(!hook.equals("INPUT"))guards.add(rule(chain,tool,++index,"-o",e.getValue(),"-g",gate));
            }
            if(!hook.equals("FORWARD")&&profiles.stream().anyMatch(p->p.scope==RoutingProfile.Scope.PHONE))guards.add(rule(chain,tool,++index,"-g",namespace(RoutingProfile.Scope.PHONE)+"G"+family+hook.charAt(0)));
            guards.add(rule(chain,tool,++index,"-j","RETURN"));
            guards.add(op(chain+"_hook",tool,args("-I",hook,"1","-j",chain),args("-D",hook,"-j",chain),args("-C",hook,"-j",chain),Check.EXIT_ZERO,Collections.emptyList(),0));
        }
        return new NetworkPlan(topology.summary(),snapshot,guards,policy,open,close,profiles);
    }
    private static String namespace(RoutingProfile.Scope scope){return "AT16_"+scope.name().charAt(0)+"_";}
    private static List<String> remap(List<String> values,RoutingProfile.Scope scope){
        List<String> out=new ArrayList<>(values);
        for(int i=0;i<out.size();i++){
            String v=out.get(i);if(v.startsWith("AT13_"))out.set(i,namespace(scope)+v.substring(5));
            else if(i>0&&(out.get(i-1).equals("table")||out.get(i-1).equals("lookup"))&&v.equals("5013"))out.set(i,Integer.toString(5013+scope.ordinal()));
            else if(i>0&&out.get(i-1).equals("priority"))out.set(i,Integer.toString(Integer.parseInt(v)+scope.ordinal()*10));
        }return out;
    }
    private static Operation rename(Operation op,RoutingProfile.Scope scope){return new Operation(scope.name()+"/"+op.id,op.tool,remap(op.apply,scope),remap(op.undo,scope),remap(op.inspect,scope),op.check,remap(op.expected,scope),op.position);}
    private static List<String> args(String... values){List<String> args=new ArrayList<>(Arrays.asList("-w","5","-t","filter"));Collections.addAll(args,values);return args;}
    private static Operation op(String id,Tool tool,List<String> apply,List<String> undo,List<String> inspect,Check check,List<String> expected,int index){return new Operation(id,tool,apply,undo,inspect,check,expected,index);}
    private static Operation rule(String chain,Tool tool,int index,String... values){
        List<String> a=args("-A",chain);Collections.addAll(a,values);List<String> u=args("-D",chain);Collections.addAll(u,values);List<String> expected=new ArrayList<>(Arrays.asList("-A",chain));Collections.addAll(expected,values);
        return op(chain+"_"+index,tool,a,u,args("-S",chain),Check.RULE_AT_INDEX,expected,index);
    }
}
