package io.github.syldegil.autotunnel;

import java.util.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

/** Declarative examples only, never evidence or Android configuration. */
public final class GraphTemplates {
    public static final String[] TITLES={"Internet direct","Téléphone via Freebox","USB via Freebox","Hotspot via Freebox","Routage combiné","Protection obligatoire","Routage mixte · téléphone direct explicite"};
    private GraphTemplates(){}
    public static GraphModel directCombined(){
        List<Node> nodes=new ArrayList<>();List<Link> links=new ArrayList<>();nodes.add(node("firewall",Kind.FIREWALL,true,Properties.defaults()));nodes.add(node("internet",Kind.INTERNET,true,Properties.defaults()));link(links,"firewall","internet");
        for(Kind kind:new Kind[]{Kind.PHONE_APPS,Kind.USB,Kind.HOTSPOT}){String id=kind.name();nodes.add(node(id,kind,true,policy(Policy.DIRECT)));link(links,id,"firewall");}
        return new GraphModel(nodes,links,new RoutingProfile(RoutingProfile.Scope.ALL,RoutingProfile.Mode.DIRECT,RoutingProfile.V6.BLOCK,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList()),0,0,1,ViewMode.LOGICAL).layout();
    }
    public static GraphModel create(int index){
        if(index<0||index>=TITLES.length)throw new IllegalArgumentException("Modèle inconnu");
        RoutingProfile.Scope scope=index==2?RoutingProfile.Scope.USB:index==3?RoutingProfile.Scope.WIFI:index>=4?RoutingProfile.Scope.ALL:RoutingProfile.Scope.PHONE;
        RoutingProfile profile=new RoutingProfile(scope,index==0?RoutingProfile.Mode.DIRECT:RoutingProfile.Mode.REQUIRE_TUNNEL,RoutingProfile.V6.BLOCK,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
        List<Node> nodes=new ArrayList<>();List<Link> links=new ArrayList<>();
        nodes.add(node("firewall",Kind.FIREWALL,true,Properties.defaults()));
        if(index==0){nodes.add(node("phone",Kind.PHONE_APPS,true,policy(Policy.DIRECT)));nodes.add(node("internet",Kind.INTERNET,true,Properties.defaults()));link(links,"phone","firewall");link(links,"firewall","internet");}
        else{
            nodes.add(node("wg",Kind.WIREGUARD,true,Properties.defaults()));nodes.add(node("freebox",Kind.FREEBOX,true,Properties.defaults()));nodes.add(node("remote",Kind.REMOTE_INTERNET,true,Properties.defaults()));nodes.add(node("home",Kind.HOME_LAN,true,Properties.defaults()));
            link(links,"wg","freebox");link(links,"freebox","remote");link(links,"freebox","home");
            if(index==5){nodes.add(node("required",Kind.KILL_SWITCH,true,policy(Policy.REQUIRE_TUNNEL)));link(links,"firewall","required");link(links,"required","wg");}else link(links,"firewall","wg");
            if(index==1||index>=4){nodes.add(node("phone",Kind.PHONE_APPS,true,policy(index==6?Policy.DIRECT:Policy.REQUIRE_TUNNEL)));
                if(index==6){nodes.add(node("direct_firewall",Kind.FIREWALL,true,Properties.defaults()));nodes.add(node("direct",Kind.INTERNET,true,Properties.defaults()));link(links,"phone","direct_firewall");link(links,"direct_firewall","direct");}else link(links,"phone","firewall");}
            if(index==2||index>=4){nodes.add(node("usb",Kind.USB,true,policy(Policy.REQUIRE_TUNNEL)));link(links,"usb","firewall");}
            if(index==3||index>=4){nodes.add(node("hotspot",Kind.HOTSPOT,true,policy(Policy.REQUIRE_TUNNEL)));link(links,"hotspot","firewall");}
            nodes.add(node("upstream",Kind.AUTO_UPLINK,false,Properties.defaults()));nodes.add(node("transport",Kind.INTERNET,false,Properties.defaults()));
            links.add(new Link("outer","wg",Port.TRANSPORT_OUT,"upstream",Port.UPLINK_IN,false,Properties.defaults()));links.add(new Link("physical","upstream",Port.PHYSICAL_OUT,"transport",Port.PHYSICAL_IN,false,Properties.defaults()));
        }
        return new GraphModel(nodes,links,profile,0,0,1,ViewMode.LOGICAL).layout();
    }
    private static Node node(String id,Kind kind,boolean enabled,Properties p){return new Node(id,kind,kind.title,40,40,enabled,p);}
    private static void link(List<Link> links,String a,String b){links.add(new Link(a+"_"+b,a,Port.OUT_IP,b,Port.IN_IP,true,Properties.defaults()));}
    public static Properties policy(Policy policy){return new Properties(null,policy,true,"","","","",Collections.emptyList(),Collections.emptyList(),Collections.emptyList());}
}
