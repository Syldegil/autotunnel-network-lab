package io.github.syldegil.autotunnel;

/** Process-local backend measurement. An UP interface never establishes reachability. */
final class TunnelObservation {
    static volatile TunnelObservation latest = new TunnelObservation("Non observé dans ce processus", -1, -1, 0);
    final String state;
    final long rx, tx, at, latestHandshakeMillis;
    final int peerCount;
    TunnelObservation(String state, long rx, long tx, long at) {
        this(state, rx, tx, at, -1, -1);
    }
    TunnelObservation(String state, long rx, long tx, long at, long latestHandshakeMillis, int peerCount) {
        this.state = state; this.rx = rx; this.tx = tx; this.at = at;
        this.latestHandshakeMillis = latestHandshakeMillis; this.peerCount = peerCount;
    }
    String describe() {
        return describeAt(System.currentTimeMillis());
    }
    boolean fresh(long now) { return at > 0 && now >= at && now - at <= 20000; }
    String describeAt(long now) {
        return (fresh(now) ? state : "État actuel inconnu · ancienne observation : " + state)
            + "\n" + (at == 0 ? "Aucune mesure" : "Mesure : " + new java.util.Date(at)
            + (!fresh(now) ? " · périmée ou horloge incohérente" : ""))
            + "\n" + (rx < 0 ? "Compteurs indisponibles" : "Reçu : " + rx + " octets ; émis : " + tx + " octets (tunnel entier)")
            + "\nPairs mesurés : " + (peerCount < 0 ? "indisponible" : peerCount)
            + " ; dernier handshake : " + (latestHandshakeMillis < 0 ? "indisponible"
                : latestHandshakeMillis == 0 ? "aucun dans cette mesure"
                : latestHandshakeMillis > now ? "horloge incohérente" : new java.util.Date(latestHandshakeMillis).toString())
            + "\nAucune attribution par famille, appareil ou lien. Le pair et le trajet Internet ne sont pas vérifiés.";
    }
}
