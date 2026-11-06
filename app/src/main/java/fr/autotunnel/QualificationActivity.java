package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local simulator and explicit read-only qualification. No real apply callback is enabled in DS013. */
public final class QualificationActivity extends Activity {
    private RoutingProfile profile;
    private SimulatedNetworkBackend simulator;
    private NetworkEngine engine;
    private NetworkPlan plan;
    private CapabilityMatrix matrix;
    private volatile String evidenceContext = NetworkPlan.hash("NOT_OBSERVED");
    private Spinner rootConnections;
    private long rootObservationAt;
    private TextView result;
    private volatile String export = "DS015\nAucune lecture root exécutée. Mutations matérielles verrouillées.";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved) {
        AppStyle.theme(this); super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        matrix = new QualificationStore(this).load();
        try { profile = DraftStore.decode(new org.json.JSONObject(getIntent().getStringExtra("profile"))); }
        catch (Exception ignored) { profile = RoutingProfile.defaults(RoutingProfile.Scope.PHONE); }
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 24, 24, 24);
        scroll.setBackgroundColor(AppStyle.bg(this)); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        TextView title = text(root, "Qualification matérielle · DS015"); title.setTextSize(26);
        text(root, "SOUHAITÉ : " + profile.mode + " / " + profile.scope + " / IPv6 " + profile.ipv6
            + "\nAPPLIQUÉ SUR APPAREIL : aucun objet DS013\nPARCOURS VÉRIFIÉ SUR APPAREIL : aucun\n"
            + "Les actions de simulation ci-dessous n’exécutent aucune commande. Les interfaces sim* sont fictives.");
        button(root, "Observer Android (lecture publique)", () -> worker.execute(() -> {
            NetworkDiagnostics d = new NetworkDiagnostics(getApplicationContext());
            String safe = NetworkDiscovery.sanitized(Collections.emptyMap(), Build.VERSION.SDK_INT, System.getProperty("os.version", "unknown"));
            show(d.device + "\n" + d.upstream + "\n" + d.physical + "\n" + d.vpn + "\n" + d.families + "\n" + d.tools);
            export = safe;
        }));
        text(root, "Niveau A · lectures autorisées individuellement. Aucun paquet de test envoyé.");
        Spinner probeChoice = new Spinner(this);
        probeChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, NetworkDiscovery.Probe.values())); root.addView(probeChoice);
        button(root, "Lire uniquement la sonde sélectionnée (root)", () -> {
            NetworkDiscovery.Probe probe = (NetworkDiscovery.Probe) probeChoice.getSelectedItem();
            NetworkDiscovery.Command cmd = NetworkDiscovery.command(probe);
            new AlertDialog.Builder(this)
            .setTitle("Lecture du helper déjà provisionné ?")
            .setMessage("Sonde " + probe + "\n" + cmd.executable + " " + String.join(" ", cmd.arguments)
                + "\nRisque : lecture de métadonnées privées, charge temporaire. Retour : arrêt de lecture, aucune règle à restaurer. Aucune demande su ni installation. Outil absent/refusé = inconnu.")
            .setNegativeButton("Annuler", null).setPositiveButton("Lire seulement", (d, w) -> worker.execute(() -> {
                try {
                    org.json.JSONObject reply = RootClient.qualify(probe); export = reply.getString("report");
                    evidenceContext = reply.getString("context"); long at = reply.getLong("at");
                    CapabilityMatrix.Capability cap = probe == NetworkDiscovery.Probe.FILTER4 ? CapabilityMatrix.Capability.FIREWALL_IPV4
                        : probe == NetworkDiscovery.Probe.FILTER6 ? CapabilityMatrix.Capability.FIREWALL_IPV6 : null;
                    if (cap != null && export.contains(probe.name() + "=LECTURE_OK")) {
                        for (CapabilityMatrix.Path path : CapabilityMatrix.Path.values()) matrix.recordObservation(new CapabilityMatrix.Evidence(
                            cap, path, CapabilityMatrix.Status.HARDWARE_OBSERVED, evidenceContext, NetworkPlan.hash(export), "READ_" + probe.name(), at, at + 900000));
                        new QualificationStore(this).save(matrix);
                    }
                    show(export + "\n\n" + reply.optString("local", "") + "\nLecture seule : aucune capacité HARDWARE_VERIFIED.");
                    org.json.JSONArray connections = reply.optJSONArray("connections");
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    if (connections != null) for (int i = 0; i < connections.length(); i++) labels.add(connections.getString(i));
                    runOnUiThread(() -> { if (!isDestroyed()) { rootObservationAt = at; rootConnections.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); } });
                }
                catch (Exception ignored) { show("Helper indisponible/refusé. Aucune permission élargie et aucun changement réseau."); }
            })).show();
        });
        button(root, "Matrice par capacité / parcours", () -> show(matrix.report(evidenceContext, System.currentTimeMillis())));
        rootConnections = new Spinner(this); root.addView(rootConnections);
        button(root, "Surligner la connexion root sélectionnée", () -> {
            Object selection = rootConnections.getSelectedItem();
            if (selection != null) startActivity(new Intent(this, RoutesActivity.class).putExtra("inspectorSelection",
                "PARTIELLEMENT OBSERVÉ (root) : " + selection + "\nSocket ↔ interface ? ↔ filtre ? ↔ tunnel/direct ? ↔ destination\nCONFIGURÉ : brouillon ; CALCULÉ : attribution indisponible ; VÉRIFIÉ : NON QUALIFIÉ")
                .putExtra("inspectorAt", rootObservationAt));
        });
        text(root, "Niveau B · procédures préparées, exécution matérielle désactivée");
        Spinner tests = new Spinner(this); tests.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, QualificationScenarios.Test.values())); root.addView(tests);
        button(root, "Examiner objectif / effets / retour / preuves", () -> show(QualificationScenarios.describe((QualificationScenarios.Test) tests.getSelectedItem())));
        text(root, "Niveau C · toutes les capacités requises doivent être vérifiées dans le contexte exact, puis essai autorisé séparément. Aucun statut observé ou simulé ne suffit.");
        button(root, "Inspecteur réseau / sélection de connexion", () -> startActivity(new Intent(this, InspectorActivity.class)));
        button(root, "Prévisualiser les opérations · SIMULATION", () -> {
            try { reset(); result.setText(plan.preview()); }
            catch (Exception ignored) { result.setText(R.string.engine_unsupported); }
        });
        button(root, "Appliquer au noyau SIMULÉ", () -> {
            try { reset(); engine.apply(plan, plan.digest, System.currentTimeMillis()); simulationStatus(); }
            catch (Exception ignored) { result.setText(R.string.engine_simulation_refused); }
        });
        button(root, "Simuler panne WireGuard", () -> event(NetworkEngine.Event.TUNNEL_LOST, false));
        button(root, "Simuler perte amont / service", () -> event(NetworkEngine.Event.SERVICE_DIED, false));
        button(root, "Simuler reprise avec preuves", () -> event(NetworkEngine.Event.RECOVER, true));
        button(root, "Retrait ciblé SIMULÉ / retour direct volontaire", () -> {
            if (engine == null) return;
            new AlertDialog.Builder(this).setMessage("Retirer uniquement les objets DS013 du simulateur ? Le retour direct est volontaire ; le profil enregistré reste conservé.")
                .setNegativeButton("Annuler", null).setPositiveButton("Retour direct simulé", (d, w) -> { engine.restore(true); simulationStatus(); }).show();
        });
        Button apply = button(root, "Appliquer sur l’appareil · qualification manquante", () -> { }); apply.setEnabled(false);
        text(root, "Verrous : liaison TUN/UID/ifindex, root/SELinux, ordre netd, boot et mort du service, premières trames, offload et conntrack ciblé, preuves USB/Wi-Fi/IPv4/IPv6/DNS. Une compilation ou un bouton ne les valide pas.");
        button(root, "Exporter le diagnostic nettoyé", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "autotunnel-ds015-diagnostic.txt"), 31));
        result = text(root, "Aucune sonde root exécutée. Choisir une observation ou une simulation.");
    }
    static void open(Activity activity, RoutingProfile profile) {
        try { activity.startActivity(new Intent(activity, QualificationActivity.class).putExtra("profile", DraftStore.encode(profile).toString())); }
        catch (Exception ignored) { new AlertDialog.Builder(activity).setMessage("Configuration invalide").setPositiveButton("OK", null).show(); }
    }
    private void reset() {
        simulator = new SimulatedNetworkBackend(System.currentTimeMillis()); engine = new NetworkEngine(simulator, new NetworkEngine.MemoryJournal());
        plan = new NetfilterCompiler().compile(profile, simulator.snapshot);
    }
    private void event(NetworkEngine.Event event, boolean proof) {
        if (engine == null || plan == null) return;
        simulator.snapshot = SimulatedNetworkBackend.fixture(System.currentTimeMillis(), true); simulator.proof = proof;
        engine.event(event, System.currentTimeMillis()); simulationStatus();
    }
    private void simulationStatus() {
        result.setText(getString(R.string.engine_simulation_state, engine.state(), engine.error(), simulator.ownedCount(), engine.operationIds()));
    }
    private void show(String text) { runOnUiThread(() -> { if (!isDestroyed()) result.setText(text); }); }
    private TextView text(LinearLayout parent, String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(16); v.setTextColor(AppStyle.ink(this)); v.setPadding(0, 12, 0, 12); parent.addView(v); return v; }
    private Button button(LinearLayout parent, String text, Runnable action) { Button v = new Button(this); v.setText(text); AppStyle.button(v); v.setOnClickListener(w -> action.run()); parent.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v; }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != 31 || code != RESULT_OK || data == null || data.getData() == null) return;
        String safe = export;
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new java.io.IOException(); out.write(safe.getBytes(StandardCharsets.UTF_8)); show("Diagnostic nettoyé exporté (sans adresses, clés, pair, SSID ou dumps bruts).");
            } catch (Exception ignored) { show("Export impossible ; aucune donnée sensible ajoutée."); }
        });
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
