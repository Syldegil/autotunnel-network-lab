package io.github.syldegil.autotunnel;

/** Computes desired actions, NOT observations of packet enforcement. No executor is shipped. */
public final class RoutePolicy {
    private RoutePolicy() { }
    public enum Intent { BLOCK_REQUIRED, TUNNEL_REQUEST, DIRECT_REQUEST }
    public enum TunnelState { UP, DOWN, UNKNOWN }
    public static java.util.Map<RoutingProfile.Scope,Intent> intents(RoutingTopology topology,RoutingProfile.Family family,
            java.util.Map<RoutingProfile.Scope,Evidence> evidence,long now){
        java.util.Map<RoutingProfile.Scope,Intent> out=new java.util.EnumMap<>(RoutingProfile.Scope.class);
        for(RoutingTopology.Branch b:topology.branches)out.put(b.profile.scope,intent(b.profile,family,evidence.get(b.profile.scope),now));return out;
    }

    public static final class Evidence {
        public final TunnelState tunnel;
        public final boolean ipv4, ipv6, dns, guard, wifiGuard, usbGuard;
        public final long sampledAt;
        public Evidence(TunnelState tunnel, boolean ipv4, boolean ipv6, boolean dns,
                        boolean guard, boolean wifiGuard, boolean usbGuard, long sampledAt) {
            this.tunnel = tunnel; this.ipv4 = ipv4; this.ipv6 = ipv6; this.dns = dns;
            this.guard = guard; this.wifiGuard = wifiGuard; this.usbGuard = usbGuard; this.sampledAt = sampledAt;
        }
    }
    public static Intent intent(RoutingProfile p, RoutingProfile.Family family, Evidence e, long now) {
        if (family == RoutingProfile.Family.BOTH) throw new IllegalArgumentException("Évaluer chaque famille séparément.");
        if (family == RoutingProfile.Family.IPV6 && p.ipv6 == RoutingProfile.V6.BLOCK) return Intent.BLOCK_REQUIRED;
        if (p.mode == RoutingProfile.Mode.DIRECT) return Intent.DIRECT_REQUEST;
        if (e == null || now < e.sampledAt || now - e.sampledAt > 15000 || e.tunnel != TunnelState.UP
            || !e.guard || !e.dns) return Intent.BLOCK_REQUIRED;
        if ((p.scope == RoutingProfile.Scope.WIFI || p.scope == RoutingProfile.Scope.ALL) && !e.wifiGuard)
            return Intent.BLOCK_REQUIRED;
        if ((p.scope == RoutingProfile.Scope.USB || p.scope == RoutingProfile.Scope.ALL) && !e.usbGuard)
            return Intent.BLOCK_REQUIRED;
        return (family == RoutingProfile.Family.IPV4 ? e.ipv4 : e.ipv6) ? Intent.TUNNEL_REQUEST : Intent.BLOCK_REQUIRED;
    }
    public static String activationRefusal(RoutingProfile p) {
        return "Activation indisponible. Ce profil est un brouillon, aucune protection n’est appliquée.\n\n"
            + "Il manque un exécuteur qualifié avec blocage persistant IPv4/IPv6, restauration ciblée et preuves de fuite. "
            + (p.scope == RoutingProfile.Scope.PHONE ? "Le VPN permanent avec blocage Android n’est pas intégré. "
                : "Les interfaces de partage, netd et les accélérations BPF/matérielles doivent être qualifiés séparément en Wi-Fi et USB. ")
            + "Le mode automatique historique ne remplit pas ces conditions. Aucun repli direct n’est activé par ce bouton.";
    }
    public static String plan(RoutingProfile p) {
        return "BROUILLON NON APPLIQUÉ · " + p.scope + " · " + p.mode
            + "\nIPv4 : " + (p.mode == RoutingProfile.Mode.REQUIRE_TUNNEL ? "tunnel obligatoire ; bloquer si panne ou état inconnu" : "accès direct demandé explicitement")
            + "\nIPv6 : " + (p.ipv6 == RoutingProfile.V6.BLOCK ? "blocage demandé ; destinations IPv6-only inaccessibles"
                : p.mode == RoutingProfile.Mode.DIRECT ? "accès direct demandé explicitement" : "tunnel demandé ; bloquer tant que non vérifié")
            + "\nRoutes IPv4 : " + (p.mode==RoutingProfile.Mode.DIRECT?"routage Android existant ; aucun changement demandé":inherited(p.routes4)) + "\nRoutes IPv6 : "
            + (p.ipv6 == RoutingProfile.V6.BLOCK ? "aucune route ajoutée" : p.mode==RoutingProfile.Mode.DIRECT?"routage Android existant":inherited(p.routes6))
            + "\nDNS : " + (p.mode==RoutingProfile.Mode.DIRECT?"résolveurs Android existants ; aucun changement demandé":inherited(p.dns)) + "\nPare-feu : " + p.rules.size() + " règle(s) proposée(s)."
            + "\nLes routes proposées ne modifient pas les AllowedIPs du pair. Les destinations hors routes sont à bloquer en mode obligatoire."
            + "\nOrdre futur : barrière confirmée → routes/DNS/filtrage → vérification → ouverture ciblée. "
            + "Panne/arrêt/reboot : conserver la fermeture. Retrait : seulement les ressources possédées, après choix explicite du mode direct."
            + "\nÉtat réel : non appliqué ; ni blocage ni trafic protégé démontré.";
    }
    private static String inherited(java.util.List<String> values) {
        return values.isEmpty() ? "hériter du fichier WireGuard sans modification" : String.join(", ", values);
    }
}
