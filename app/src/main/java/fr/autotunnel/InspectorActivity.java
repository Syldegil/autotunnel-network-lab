package io.github.syldegil.autotunnel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only inspector. No implicit root collection, probe packets or capability promotion. */
public final class InspectorActivity extends Activity {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();
    private ScheduledFuture<?> timer;
    private Future<?> rootTask;
    private RootClient.Call rootCall;
    private AlertDialog consent;
    private volatile boolean visible;
    private boolean resumeObservation=true;
    private volatile int epoch;
    private volatile int rootEpoch;
    private TextView report, graph, rootReport;
    private InspectorDashboard dashboard;
    private String tetherReport;
    private long tetherAt;
    private Spinner selector;
    private InspectorCollector.Sample previous, current;
    @Override public void onCreate(Bundle state) {
        setTheme((getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES ? R.style.GraphTheme : R.style.AppTheme);
        super.onCreate(state);
        try { NetworkWatchService.autoStart(this); } catch (RuntimeException ignored) { } getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(android.graphics.Color.parseColor((getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES?"#0c121b":"#f2f5f8")); LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(0,0,0,0); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, i) -> { v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(), i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom()); return i; });
        dashboard = new InspectorDashboard(this);root.addView(dashboard);
        dashboard.collectorActions(this::start,()->{resumeObservation=false;stop();});
        LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setPadding(24,0,24,24);advanced.setVisibility(android.view.View.GONE);
        button(root,"Options avancées",()->advanced.setVisibility(advanced.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));root.addView(advanced);
        button(advanced,"Démarrer / reprendre les lectures publiques",this::start);
        button(advanced,"Arrêter les collecteurs",()->{resumeObservation=false;stop();});
        button(advanced, "Qualification matérielle · niveaux A/B/C", () -> QualificationActivity.open(this, RoutingProfile.defaults(RoutingProfile.Scope.PHONE)));
        button(advanced, "Lecture root ponctuelle · choisir et consentir", this::chooseRootProbe);
        button(advanced, "Partager l'observation hotspot avec le graphe", () -> {
            if (tetherReport != null && GraphEvidence.fresh(tetherAt, System.currentTimeMillis()))
                startActivity(new android.content.Intent(this, RoutesActivity.class)
                    .putExtra("inspectorSelection", tetherReport).putExtra("inspectorAt", tetherAt));
            else new AlertDialog.Builder(this).setMessage("Lire d'abord la sonde TETHERING ; aucune observation fraîche disponible.")
                .setPositiveButton("Fermer", null).show();
        });
        button(advanced, "Graphe et profils configurés", () -> startActivity(new android.content.Intent(this, RoutesActivity.class)));
        button(advanced, "Surligner la connexion sélectionnée dans le graphe", () -> {
            if (current != null && selector.getSelectedItemPosition() >= 0 && selector.getSelectedItemPosition() < current.connections.size())
                startActivity(new android.content.Intent(this, RoutesActivity.class).putExtra("inspectorSelection", connectionGraph(current.connections.get(selector.getSelectedItemPosition())))
                    .putExtra("inspectorAt", current.at));
        });
        text(advanced, "Suivi DNS/IP indépendant : HTTPS ipify toutes les 30 s et sur changement. Diagnostics de destination sur le bouton dédié. Actualisation publique toutes les 3 s, arrêt hors écran. Root : une seule sonde choisie, sur consentement, sans actualisation automatique. Connexions = sockets visibles, pas inventaire exhaustif Android/clients.");
        report = text(advanced, "Collecteurs arrêtés. Appuyer sur Démarrer.");
        rootReport = text(advanced, "Lecture root arrêtée. Helper préprovisionné requis ; aucune capacité qualifiée.");
        selector = new Spinner(this); advanced.addView(selector);
        graph = text(advanced, "Sélectionner une connexion visible pour son parcours partiellement observé.");
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (current != null && position >= 0 && position < current.connections.size()) graph.setText(connectionGraph(current.connections.get(position)));
            }
        });
    }
    private void start() {
        if (!visible) return;
        resumeObservation=true;stop();dashboard.collecting(); final int generation = epoch;
        timer = worker.scheduleWithFixedDelay(() -> {
            if (!visible || generation != epoch) return;
            InspectorCollector.Sample next = InspectorCollector.sample(getApplicationContext());
            runOnUiThread(() -> { if (visible && generation == epoch) display(next); });
        }, 0, 3, TimeUnit.SECONDS);
    }
    private void stop() {
        epoch++; rootEpoch++; tetherReport = null; tetherAt = 0;
        if (rootCall != null) rootCall.close(); // close first: interrupts blocked socket I/O immediately
        rootCall = null;
        if (rootTask != null) rootTask.cancel(true);
        rootTask = null;
        if (timer != null) timer.cancel(true);
        timer = null; previous = null; current = null; RootInspection.publish(null);
        if (consent != null) consent.dismiss();
        consent = null;
        if (dashboard != null) dashboard.clear();
        if (report != null) report.setText("Collecteurs arrêtés. Débits et observations courantes effacés.");
        if (rootReport != null) rootReport.setText("Lecture root arrêtée. Aucun résultat courant conservé.");
        if (selector != null) selector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Collections.singletonList("Collecteurs arrêtés")));
        if (graph != null) graph.setText("Collecteurs arrêtés ; aucun parcours courant.");
    }
    private void chooseRootProbe() {
        if (!visible) return;
        final NetworkDiscovery.Probe[] probes = { NetworkDiscovery.Probe.LINK_COUNTERS, NetworkDiscovery.Probe.SOCKETS4,
            NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6,
            NetworkDiscovery.Probe.IP_STATS4, NetworkDiscovery.Probe.IP_STATS6, NetworkDiscovery.Probe.TETHERING };
        String[] labels = new String[probes.length];
        for (int i = 0; i < probes.length; i++) labels[i] = probes[i] + " · " + probeLabel(probes[i]);
        final int generation = epoch;
        consent = new AlertDialog.Builder(this).setTitle("Une seule sonde root en lecture")
            .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                dialog.dismiss();
                if (!visible || generation != epoch) return;
                consent = new AlertDialog.Builder(this).setTitle("Autoriser cette lecture ponctuelle ?")
                    .setMessage(labels[which] + "\nLe helper déjà provisionné lira uniquement ce fichier. Les métadonnées restent en mémoire à l’écran. Aucune preuve de trajet, de protection ou de qualification n’en résulte.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Lire une fois", (d, w) -> { if (visible && generation == epoch) readRoot(probes[which]); }).show();
            }).setNegativeButton("Annuler", null).show();
    }
    private static String probeLabel(NetworkDiscovery.Probe probe) {
        return probe == NetworkDiscovery.Probe.TETHERING ? "dumpsys tethering · état courant uniquement" : RootInspection.path(probe);
    }
    private void readRoot(NetworkDiscovery.Probe probe) {
        // Do not queue a root read behind the periodic public collector, nor poll the privileged helper.
        // Freeze the public view so a 3-second tick cannot silently replace the selected root sockets.
        stop();
        rootEpoch++;
        if (rootCall != null) rootCall.close();
        if (rootTask != null) rootTask.cancel(true);
        RootInspection.publish(null);
        final int generation = epoch;
        final int rootGeneration = rootEpoch;
        final RootClient.Call call = new RootClient.Call(); rootCall = call;
        rootReport.setText("Lecture root ponctuelle en cours : " + probe + " · " + probeLabel(probe));
        rootTask = rootWorker.submit(() -> {
            try (RootClient.Call ignored = call) {
                org.json.JSONObject reply = RootClient.qualify(probe, call);
                if (!reply.getBoolean("ok") || reply.getBoolean("hardwareEnabled")) throw new java.io.IOException("Invalid read-only helper response");
                if (probe == NetworkDiscovery.Probe.TETHERING) {
                    if (!probe.name().equals(reply.getString("probe"))) throw new java.io.IOException("Wrong probe");
                    String local = reply.getString("local"); long at = reply.getLong("at");
                    if (local.length() > 12000 || !GraphEvidence.fresh(at, System.currentTimeMillis())) throw new java.io.IOException("Stale observation");
                    runOnUiThread(() -> {
                        if (!visible || generation != epoch || rootGeneration != rootEpoch) return;
                        rootCall = null; rootTask = null; tetherReport = local; tetherAt = at;
                        rootReport.setText(local + "\nRelevé : " + new Date(at));
                    });
                    return;
                }
                if (!reply.has("inspection")) throw new java.io.IOException("Helper incompatible: structured inspection payload absent");
                RootInspection.Snapshot snapshot = RootInspection.decode(reply.getJSONObject("inspection"), probe);
                if (!visible || generation != epoch || rootGeneration != rootEpoch) return;
                InspectorCollector.Sample next = InspectorCollector.sample(getApplicationContext());
                merge(next, snapshot);
                runOnUiThread(() -> {
                    if (!visible || generation != epoch || rootGeneration != rootEpoch) return;
                    rootCall = null; rootTask = null; RootInspection.publish(snapshot);
                    previous = null; rootReport.setText(snapshot.describe()); display(next);
                });
            } catch (Exception e) {
                String error = RootInspection.error(e);
                runOnUiThread(() -> {
                    if (!visible || generation != epoch || rootGeneration != rootEpoch) return;
                    rootCall = null; rootTask = null;
                    rootReport.setText("Lecture root échouée : " + probe + "\n" + error + "\nHelper non démarré/provisionné automatiquement. Aucune qualification acquise.");
                });
            }
        });
    }
    private static void merge(InspectorCollector.Sample sample, RootInspection.Snapshot snapshot) {
        sample.availability += "\n" + RootInspection.SOURCE + " : " + snapshot.probe + " " + snapshot.status + "\n" + snapshot.error + "\n";
        if (snapshot.status.equals("ERROR")) return;
        sample.source = "API/procfs application + " + RootInspection.SOURCE + " " + snapshot.probe;
        for (Map.Entry<String, long[]> e : snapshot.counters.entrySet()) {
            sample.counters.put(e.getKey(), e.getValue().clone()); sample.counterSources.put(e.getKey(), RootInspection.SOURCE);
        }
        if (snapshot.probe == NetworkDiscovery.Probe.IP_STATS4 || snapshot.probe == NetworkDiscovery.Probe.IP_STATS6) {
            sample.families = new TreeMap<>(sample.families);
            sample.families.putAll(snapshot.families);
        }
        if (Arrays.asList(NetworkDiscovery.Probe.SOCKETS4, NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6).contains(snapshot.probe)) {
            String protocol = (snapshot.probe.name().startsWith("UDP") ? "UDP" : "TCP") + (snapshot.probe.name().endsWith("6") ? "6" : "4");
            sample.connections.removeIf(c -> c.protocol.equals(protocol));
            sample.connections.addAll(snapshot.connections);
        }
    }
    private void display(InspectorCollector.Sample next) {
        if (current != null && next.elapsed < current.elapsed) return;
        current = next; StringBuilder out = new StringBuilder("Observation : ").append(new java.util.Date(next.at)).append("\nSource : ").append(next.source).append('\n').append(next.network).append('\n').append(next.availability);
        out.append("Collecte application : ").append(next.durationMillis).append(" ms écoulées / ").append(next.cpuMillis).append(" ms CPU du thread ; coût UI et lecture root exclus\n");
        if (previous != null) {
            Set<String> added = new TreeSet<>(next.indices.keySet()); added.removeAll(previous.indices.keySet());
            if (!added.isEmpty()) out.append("CHANGEMENT observé : nouvelles interfaces ").append(added).append(" ; intention et couverture inconnues\n");
        }
        for (Map.Entry<String, long[]> e : next.counters.entrySet()) {
            long[] old = previous == null ? null : previous.counters.get(e.getKey());
            boolean same = previous != null && Objects.equals(next.source, previous.source)
                && Objects.equals(next.counterSources.get(e.getKey()), previous.counterSources.get(e.getKey()))
                && next.contextHash.equals(previous.contextHash) && next.indices.containsKey(e.getKey()) && Objects.equals(next.indices.get(e.getKey()), previous.indices.get(e.getKey()));
            long rx = old == null ? -1 : InspectorData.rate(old[0], e.getValue()[0], next.elapsed - previous.elapsed, same);
            long tx = old == null ? -1 : InspectorData.rate(old[1], e.getValue()[1], next.elapsed - previous.elapsed, same);
            out.append(e.getKey()).append(" RX/TX octets=").append(e.getValue()[0]).append('/').append(e.getValue()[1])
                .append(" débits octets/s=").append(rx < 0 ? "inconnu" : rx).append('/').append(tx < 0 ? "inconnu" : tx)
                .append(" source=").append(next.counterSources.getOrDefault(e.getKey(), next.source)).append('\n');
        }
        out.append("Compteurs IP globaux (pas par client, pas somme des interfaces) : ").append(next.families.isEmpty() ? "indisponibles" : next.families)
            .append("\nWireGuard officiel : ").append(TunnelObservation.latest.describe())
            .append("\nUSB/Wi-Fi clients : attribution nécessite lecture root et qualification ; aucune déduction depuis un nom d’interface")
            .append("\nRègles/compteurs/blocages : lecture root explicite dans Qualification ; aucun journal de paquets activé")
            .append("\nAlertes : DNS non conforme, IPv6/direct interdit, tethering non couvert et filtre inefficace : INCONNUS sans politique appliquée et attribution de trafic.")
            .append("\nTunnel/compteurs : état propre à AutoTunnel ci-dessus ; aucun état déduit pour com.wireguard.android.")
            .append("\nAbsence d’alerte ≠ absence de fuite. Aucun parcours VÉRIFIÉ.");
        report.setText(out.toString());
        dashboard.update(next);
        List<String> labels = new ArrayList<>(); for (InspectorData.Connection c : next.connections) labels.add(c.label());
        if (labels.isEmpty()) { labels.add("Aucune connexion visible ; ne prouve pas l’absence de trafic"); graph.setText(labels.get(0)); }
        selector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        previous = next;
    }
    private String connectionGraph(InspectorData.Connection connection) {
        return "Source du relevé : " + current.source + "\n" + current.graph(connection);
    }
    private TextView text(LinearLayout p, String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(16); t.setTextColor(AppStyle.ink(this)); t.setPadding(0,12,0,12); p.addView(t); return t; }
    private void button(LinearLayout p, String s, Runnable r) { Button b = new Button(this); b.setText(s); AppStyle.button(b); b.setOnClickListener(v -> r.run()); p.addView(b, new LinearLayout.LayoutParams(-1,-2)); }
    @Override public void onResume() { super.onResume(); visible = true; if(resumeObservation) start(); }
    @Override public void onPause() { visible = false; stop(); current = null; super.onPause(); }
    @Override public void onDestroy() { stop(); if (dashboard != null) dashboard.destroy(); worker.shutdownNow(); rootWorker.shutdownNow(); super.onDestroy(); }
}
