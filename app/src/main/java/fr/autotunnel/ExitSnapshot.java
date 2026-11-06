package io.github.syldegil.autotunnel;
import android.content.Context;
import android.net.*;
import android.os.Build;
import java.net.*;
import java.util.*;
/** Effective Android default network for this UID, never a configured WireGuard DNS guess. */
final class ExitSnapshot {
    final String identity, route, dns, lan;
    final boolean connected;
    ExitSnapshot(String identity,String route,String dns,String lan,boolean connected){this.identity=identity;this.route=route;this.dns=dns;this.lan=lan;this.connected=connected;}
    @SuppressWarnings("deprecation") static ExitSnapshot read(Context c){
        try {
            ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();
            LinkProperties lp=n==null?null:cm.getLinkProperties(n);NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            String route="Aucun réseau actif",dns="Indisponible";
            if(caps!=null){route=caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN Android":caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi direct":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Mobile direct":"Autre réseau";}
            if(lp!=null){List<String> servers=new ArrayList<>();for(InetAddress a:lp.getDnsServers())servers.add(a.getHostAddress());dns=servers.isEmpty()?"DNS non communiqué":String.join(", ",servers);
                if(Build.VERSION.SDK_INT>=28&&lp.isPrivateDnsActive())dns="DNS privé : "+(lp.getPrivateDnsServerName()==null?dns:lp.getPrivateDnsServerName());}
            Set<String> addresses=new TreeSet<>();
            for(Network network:cm.getAllNetworks()){LinkProperties l=cm.getLinkProperties(network);if(l!=null)for(LinkAddress a:l.getLinkAddresses())addresses.add(l.getInterfaceName()+" · "+a);}
            try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all!=null&&all.hasMoreElements()){NetworkInterface i=all.nextElement();if(i.isUp()&&!i.isLoopback())for(InterfaceAddress a:i.getInterfaceAddresses())addresses.add(i.getName()+" · "+a.getAddress().getHostAddress()+"/"+a.getNetworkPrefixLength());}}catch(Exception ignored){}
            String local=addresses.isEmpty()?"Aucune adresse visible":String.join("\n",addresses);
            return new ExitSnapshot(String.valueOf(n)+"|"+route+"|"+dns+"|"+(lp==null?"":lp.getRoutes()),route,dns,local,n!=null);
        }catch(RuntimeException e){return new ExitSnapshot("unknown","Réseau inconnu","Indisponible","Indisponible",false);}
    }
}
