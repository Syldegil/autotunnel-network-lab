package io.github.syldegil.autotunnel;

/** Individually selectable experimental procedures; descriptions are not executable requests. */
public final class QualificationScenarios {
    private QualificationScenarios() { }
    public enum Test { IPC_REFUSAL, FILTER_COUNTER, PHONE_WIFI, PHONE_MOBILE, USB, WIFI, VPN_FAILURE, BOOT, RECOVERY }
    public static String describe(Test test) {
        String common = "NIVEAU B · PRÉPARÉ, NON EXÉCUTABLE DANS CETTE VERSION\nAutorisation explicite de l’opérateur requise pour ce seul test.\n";
        switch (test) {
            case IPC_REFUSAL: return common + "Objectif : refuser mauvais UID/APK/signature/rejeu. Interface : socket local. Action : clients de test dédiés sans mutation. Effet : requêtes refusées, charge courte. Retour : fermer clients/helper. Preuve : aucune opération privilégiée acceptée et refus côté client ; ne pas journaliser les secrets.";
            case FILTER_COUNTER: return common + "Objectif : compter/bloquer un seul flux synthétique IPv4 OU IPv6 sur téléphone. Action future : chaîne AT13 dédiée et tuple exact, aperçu/retrait relus. Risque : perte du seul flux ; aucune qualification tethering. Retour : inverse exacte depuis checkpoint. Preuve : compteurs plus capture des deux extrémités, puis disparition des seuls objets créés.";
            case BOOT: return common + "Objectif : absence de première trame directe. Interfaces : un seul parcours. Action : reboot autorisé avec client externe émettant avant retour lien. Risque : interruption/recovery. Retour : accès indépendant déjà validé. Preuve : capture externe dès avant boot, garde antérieure au réseau, aucun contournement accéléré. Module service.sh seul insuffisant.";
            case RECOVERY: return common + "Objectif : retrait ciblé idempotent hors Activity. Action : fermer puis rollback checkpoint deux fois. Effet : retour direct volontaire. Preuve : invariants Android/tiers et paramètres offload identiques au baseline. Conntrack supprimé ne se restaure pas ; les connexions se recréent.";
            default: return common + "Parcours unique : " + test + ". Objectif : HTTPS/UDP via sortie Freebox, LAN, IPv4/IPv6/DNS séparés. Actions futures : sonde contrôlée puis coupure/reprise seulement si accord spécifique. Risque : interruption du parcours, fuite si protection défectueuse. Retour : garde fermée, retrait exact sur consentement direct. Preuve : IP publique Freebox + captures clients/destination, pas seulement handshake. Conditions : TUN, offload, premier paquet, conntrack et secours qualifiés.";
        }
    }
}
