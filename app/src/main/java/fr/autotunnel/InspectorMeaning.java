package io.github.syldegil.autotunnel;

/** Human-readable presentation only: never upgrades an observation into a guarantee. */
final class InspectorMeaning {
    static String connection(boolean known, boolean present, String role) {
        if (!known) return "Connexion inconnue";
        if (!present) return "Aucun réseau actif";
        if ("Wi-Fi".equals(role)) return "Connecté en Wi-Fi";
        if (role.startsWith("Mobile")) return "Connecté au réseau mobile";
        if (role.startsWith("VPN")) return "Réseau VPN détecté";
        return "Réseau connecté";
    }
    static String internet(boolean known, boolean present, boolean validated) {
        if (!known) return "Les informations ne sont pas disponibles.";
        if (!present) return "Activez le Wi-Fi ou les données mobiles.";
        return validated ? "Internet détecté par Android" : "Accès Internet à vérifier";
    }
    static String tunnel(boolean fresh, String state) {
        if (!fresh) return "État inconnu";
        if (state.equals("Interface locale active")) return "Tunnel actif";
        if (state.equals("Interface locale arrêtée")) return "Tunnel arrêté";
        return "État à vérifier";
    }
    static String stage(int index, DiagnosticSequence.Status state) {
        switch (state) {
            case PASS: return new String[]{"Adresse trouvée", "Serveur joint", "Certificat valide"}[index];
            case RUNNING: return "Vérification en cours…";
            case NOT_RUN: return "Pas encore testé";
            case TIMEOUT: return "Pas de réponse à temps";
            case CANCELLED: return "Test interrompu";
            case UNAVAILABLE: return "Réseau indisponible";
            default: return new String[]{"Adresse non trouvée", "Connexion impossible", "Connexion sécurisée refusée"}[index];
        }
    }
    static String verdict(DiagnosticSequence.Result result) {
        if (result == null) return "Aucun serveur testé";
        for (DiagnosticSequence.Step step : result.steps) {
            if (step.status == DiagnosticSequence.Status.RUNNING) return "Vérification en cours…";
            if (step.status == DiagnosticSequence.Status.TIMEOUT) return "La réponse se fait attendre";
            if (step.status == DiagnosticSequence.Status.UNAVAILABLE) return "Le réseau a changé ou manque";
            if (step.status == DiagnosticSequence.Status.CANCELLED) return "Test interrompu";
        }
        if (result.steps.get(0).status == DiagnosticSequence.Status.FAIL) return "Vérifiez l’adresse du serveur";
        if (result.steps.get(1).status == DiagnosticSequence.Status.FAIL) return "Le serveur n’est pas joignable";
        if (result.steps.get(2).status == DiagnosticSequence.Status.FAIL) return "La connexion sécurisée a échoué";
        if (result.steps.get(2).status == DiagnosticSequence.Status.PASS) return "Connexion chiffrée vérifiée";
        return "Vérification incomplète";
    }
    static String guidance(DiagnosticSequence.Result result) {
        if (result == null) return "Lancez un test pour vérifier une destination précise.";
        for (DiagnosticSequence.Step s : result.steps) {
            if (s.status == DiagnosticSequence.Status.RUNNING) return "Une seule destination, 15 secondes maximum.";
            if (s.status == DiagnosticSequence.Status.TIMEOUT) return "Réessayez. Ce délai ne prouve pas que le serveur est éteint.";
            if (s.status == DiagnosticSequence.Status.UNAVAILABLE) return "Vérifiez votre connexion, puis relancez le test.";
            if (s.status == DiagnosticSequence.Status.CANCELLED) return "Vous pouvez lancer un nouveau test.";
        }
        if (result.steps.get(0).status == DiagnosticSequence.Status.FAIL) return "Vérifiez le nom saisi et votre connexion.";
        if (result.steps.get(1).status == DiagnosticSequence.Status.FAIL) return "Vérifiez l’adresse, le port et la disponibilité du service.";
        if (result.steps.get(2).status == DiagnosticSequence.Status.FAIL) return result.steps.get(2).detail.contains("Certificat") ? "Le certificat ou le nom du serveur n’a pas été accepté." : "Le service ne termine pas la connexion sécurisée attendue.";
        return "Certificat et identité vérifiés pour ce serveur. Le contenu du site n’a pas été testé.";
    }
}
