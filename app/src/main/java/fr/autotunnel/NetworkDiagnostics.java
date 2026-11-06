package io.github.syldegil.autotunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import java.io.File;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;

/** Public API and fixed-path file presence only. Never invokes su, shell, or network probes. */
final class NetworkDiagnostics {
    final long at = System.currentTimeMillis();
    String upstream = "Amont inconnu", physical = "Réseaux non observés", vpn = "VPN non observé";
    String profile = "Aucun fichier WireGuard importé", families = "IPv4 : non vérifié\nIPv6 : non vérifié";
    String tools;
    final String device = Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE
        + " · SDK " + Build.VERSION.SDK_INT + "\nBuild " + Build.DISPLAY
        + "\nNoyau (propriété Java) : " + System.getProperty("os.version", "inconnu");

    @SuppressWarnings("deprecation")
    NetworkDiagnostics(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            List<String> physicals = new ArrayList<>(), vpns = new ArrayList<>();
            Network active = cm.getActiveNetwork();
            String activePhysical = null;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                LinkProperties link = cm.getLinkProperties(network);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpns.add(link(link));
                else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    String label = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile · génération 3G/4G/5G non observée"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Autre transport";
                    physicals.add(label + "\n" + link(link));
                    if (network.equals(active)) activePhysical = label;
                }
            }
            physical = physicals.isEmpty() ? "Aucun réseau physique visible" : String.join("\n\n", physicals);
            upstream = activePhysical != null ? activePhysical + " · réseau par défaut observé"
                : "Amont du VPN non attribuable par cette observation ; réseaux candidats ci-dessous";
            vpn = vpns.isEmpty() ? "Aucun VPN visible via Android" : String.join("\n\n", vpns)
                + "\nUn VPN visible peut appartenir à une autre application ; identité non déduite.";
        } catch (RuntimeException ignored) { physical = "Observation réseau refusée ou indisponible"; }
        if (Vault.file(context).exists()) {
            try {
                Config c = Vault.load(context);
                boolean a4 = false, a6 = false, r4 = false, r6 = false;
                List<String> addresses = new ArrayList<>(), routes = new ArrayList<>(), dns = new ArrayList<>();
                for (InetNetwork a : c.getInterface().getAddresses()) {
                    addresses.add(a.toString());
                    if (a.getAddress() instanceof Inet6Address) a6 = true; else a4 = true;
                }
                for (com.wireguard.config.Peer p : c.getPeers()) for (InetNetwork r : p.getAllowedIps()) {
                    routes.add(r.toString());
                    if (r.getAddress() instanceof Inet6Address) r6 = true; else r4 = true;
                }
                c.getInterface().getDnsServers().forEach(d -> dns.add(d.getHostAddress()));
                profile = "Lecture seule du fichier chiffré, pas une mesure de routage\nAdresses : " + String.join(", ", addresses)
                    + "\nPairs configurés : " + c.getPeers().size()
                    + "\nAllowedIPs : " + String.join(", ", routes) + "\nDNS conservé : " + String.join(", ", dns)
                    + "\nRoute Internet IPv4 déclarée /0 : " + routes.contains("0.0.0.0/0")
                    + " ; politique effective Android/retour à vérifier"
                    + "\nApplications exclues : " + c.getInterface().getExcludedApplications().size()
                    + " ; incluses : " + c.getInterface().getIncludedApplications().size()
                    + "\nClés et identité du pair non affichées.";
                families = "IPv4 : " + declared(a4, r4) + "\nIPv6 : " + declared(a6, r6)
                    + "\nDéclaration ≠ protection : serveur, DNS, retours et fuites non testés.";
            } catch (Exception ignored) { profile = "Lecture du profil indisponible ; aucun paramètre remplacé"; }
        }
        tools = "Présence visible uniquement (aucune commande exécutée) :\n";
        for (String name : new String[]{"iptables", "ip6tables", "nft"}) {
            boolean found = false;
            try {
                for (String dir : new String[]{"/system/bin/", "/system/xbin/", "/vendor/bin/"})
                    found |= new File(dir + name).isFile();
                tools += name + " : " + (found ? "fichier présent, exécution non vérifiée" : "non visible aux emplacements sondés") + "\n";
            } catch (SecurityException ignored) { tools += name + " : accès refusé\n"; }
        }
        tools += "Root KernelSU : non vérifié ; SELinux : non vérifié. Aucun su ni changement SELinux.\n"
            + "Extensions noyau, hooks, netd, BPF/HAL, NAT et barrières IPv4/IPv6 : non vérifiés. Pare-feu AutoTunnel indisponible.";
    }
    private static String declared(boolean address, boolean routes) {
        return address && routes ? "adresse et routes déclarées, chemin non vérifié" : "adresse ou routes absentes ; ne pas présumer de transport";
    }
    private static String link(LinkProperties link) {
        if (link == null) return "Interface/adresses/routes indisponibles";
        return "Interface : " + link.getInterfaceName() + "\nAdresses : " + link.getLinkAddresses()
            + "\nRoutes Android visibles : " + link.getRoutes()
            + "\nCompteurs par lien : indisponibles";
    }
}
