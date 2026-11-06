package io.github.syldegil.autotunnel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native graph and shared RoutingProfile/rule editor. Local drafts only. */
public final class RoutesActivity extends Activity {
    private LinearLayout root;
    private DraftStore store;
    private DraftHistory history;
    private GraphStore graphStore;
    private GraphHistory graphHistory;
    private GraphEditor graphEditor;
    private InspectorCollector.Sample inspectorSample;
    private final android.os.Handler liveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean reading;
    private boolean visible;
    private boolean recenterAfterRotation;
    private int observationEpoch;
    private java.util.concurrent.Future<?> observationTask;
    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            if (graphEditor != null && graphHistory != null && graphHistory.current.view == GraphModel.ViewMode.LIVE) refreshObservation();
            liveHandler.postDelayed(this, 5000);
        }
    };
    private RoutingProfile.Scope scope = RoutingProfile.Scope.PHONE;
    private NetworkDiagnostics diagnostics;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private String page = "graph";
    private Spinner mode, ipv6;
    private EditText routes4, routes6, dns;
    private List<RoutingProfile.Rule> rules;
    private LinearLayout rulesBox;
    private Bundle pendingEditor;

    @Override public void onCreate(Bundle saved) {
        AppStyle.theme(this); super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::requestBack);
        }
        store = new DraftStore(this);
        graphStore = new GraphStore(this);
        if (saved != null) {
            recenterAfterRotation=saved.getInt("orientation",getResources().getConfiguration().orientation)!=getResources().getConfiguration().orientation;
            scope = RoutingProfile.Scope.valueOf(saved.getString("scope", "PHONE"));
            page = saved.getString("page", "graph");
            pendingEditor = saved.getBundle("editor");
        }
        load();
    }
    private void load() {
        graphEditor = null;
        try {
            graphHistory = graphStore.existing(scope);
            if (graphHistory == null) graphHistory = graphStore.load(store.load(scope).current);
            syncProfile(); render();
        }
        catch (Exception ignored) {
            history = null; graphHistory = null;
            shell("Brouillon illisible");
            text(root, "État inconnu : lecture refusée ou version incompatible. Le fichier existant est conservé. Aucun remplacement automatique.");
            button(root, "Choisir un autre parcours", v -> chooseProfile());
            button(root, "Réessayer la lecture", v -> load());
        }
    }
    private void refreshObservation() {
        if (reading || !visible) return;
        reading = true;
        final int generation = observationEpoch;
        observationTask = reader.submit(() -> {
            final NetworkDiagnostics snapshot;
            final InspectorCollector.Sample sample;
            try {
                snapshot = new NetworkDiagnostics(getApplicationContext());
                sample = InspectorCollector.sample(getApplicationContext());
            } catch (RuntimeException ignored) {
                runOnUiThread(() -> {
                    if (!visible || generation != observationEpoch) return;
                    reading = false;
                    if (!isFinishing() && !isDestroyed() && graphEditor != null) graphEditor.feedback("Observation indisponible ; graphe déclaré conservé.");
                });
                return;
            }
            runOnUiThread(() -> {
                if (!visible || generation != observationEpoch) return;
                reading = false;
                if (isFinishing() || isDestroyed()) return;
                diagnostics = snapshot;
                inspectorSample = sample;
                if (graphHistory == null) return;
                else if (graphEditor != null) graphEditor.observation(diagnostics, inspectorSample);
                else if ("diagnostic".equals(page)) render();
            });
        });
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (diagnostics == null || graphEditor != null && graphHistory != null && graphHistory.current.view == GraphModel.ViewMode.LIVE) refreshObservation();
        liveHandler.postDelayed(liveRefresh, 5000);
    }
    @Override protected void onPause() {
        visible = false; observationEpoch++; liveHandler.removeCallbacks(liveRefresh);
        if (observationTask != null) observationTask.cancel(true);
        observationTask = null; reading = false; super.onPause();
    }
    private void shell(String title) {
        ScrollView scroll = new ScrollView(this);
        root = column(); root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.setBackgroundColor(AppStyle.bg(this)); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView heading = text(root, title); heading.setTextSize(26);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    }
    private void render() {
        if (history == null) return;
        graphEditor = null;
        if ("edit".equals(page)) { editor(); return; }
        if ("graph".equals(page)) { interactiveGraph(); return; }
        shell("Diagnostic des parcours");
        text(root, "BROUILLONS · aucune règle réseau appliquée par cet écran.\n"
            + "Le mode automatique historique autorise Internet direct en Wi-Fi et ne garantit pas l’absence de fuite.");
        text(root, "Service historique : " + (AutoService.running ? AutoService.status : "arrêté dans ce processus"));
        button(root, "Choisir le profil · " + RoutingProfile.title(scope), v -> chooseProfile());
        button(root, "Configurer ce profil", v -> { page = "edit"; render(); });
        button(root, "Qualification matérielle / capacités DS015", v -> qualify(history.current));
        button(root, "Voir le graphe", v -> { page = "graph"; render(); });
        button(root, "Relire les observations", v -> refreshObservation());
        diagnostic();
    }
    private void chooseProfile() {
        String[] labels = new String[RoutingProfile.Scope.values().length];
        for (RoutingProfile.Scope s : RoutingProfile.Scope.values()) labels[s.ordinal()] = RoutingProfile.title(s);
        new AlertDialog.Builder(this).setTitle("Profil à visualiser (non appliqué)")
            .setItems(labels, (d, which) -> { scope = RoutingProfile.Scope.values()[which]; graphHistory = null; load(); }).show();
    }
    private void syncProfile() {
        history = new DraftHistory(graphHistory.current.profile,
            graphHistory.undo.isEmpty() ? null : graphHistory.undo.get(graphHistory.undo.size() - 1).profile, graphHistory.revision);
    }
    private boolean persistGraph(GraphHistory next) {
        try { graphStore.write(next); graphHistory = next; syncProfile(); return true; }
        catch (Exception ignored) {
            message("Enregistrement impossible", "Le graphe précédent est conservé. Une révision concurrente ou une erreur disque impose de recharger l’écran avant de réessayer."); return false;
        }
    }
    private void interactiveGraph() {
        graphEditor = new GraphEditor(this, graphHistory, new GraphEditor.Host() {
            public boolean persist(GraphHistory next) { return persistGraph(next); }
            public void profile() { page = "edit"; render(); }
            public void chooseProfile() { RoutesActivity.this.chooseProfile(); }
            public void diagnostics() { page = "diagnostic"; render(); }
            public void refresh() { refreshObservation(); }
            public void loadTemplate(GraphModel graph) {
                try {
                    GraphHistory target=graphStore.load(RoutingProfile.defaults(graph.profile.scope));
                    GraphHistory next=target.change(graph);graphStore.write(next);scope=graph.profile.scope;graphHistory=next;syncProfile();page="graph";render();
                }catch(Exception e){message("Modèle non chargé","Le brouillon existant est conservé : "+e.getMessage());}
            }
        });
        setContentView(graphEditor);
        graphEditor.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(8) + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                dp(8) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        graphEditor.observation(diagnostics, inspectorSample);
        graphEditor.inspectorSelection(getIntent().getStringExtra("inspectorSelection"), getIntent().getLongExtra("inspectorAt", 0));
        if(recenterAfterRotation){recenterAfterRotation=false;graphEditor.recenter();}
    }
    private void qualify(RoutingProfile profile) {
        GraphCompiler.Result result = GraphCompiler.compile(graphHistory.current.profile(profile));
        if (result.valid()) TopologyPreview.create(this,result.topology).show();
        else message("Compilation du graphe refusée", result.report());
    }
    private String stamp() {
        return diagnostics == null ? "Aucune observation Android disponible" : "Observation publique Android : " + new java.util.Date(diagnostics.at)
            + " ; ne date pas une vérification des chemins inconnus.";
    }
    private void diagnostic() {
        button(root, "Inspecteur réseau réel / connexions", v -> startActivity(new android.content.Intent(this, InspectorActivity.class)));
        text(root, stamp());
        if (diagnostics != null) {
            text(root, diagnostics.device); text(root, diagnostics.upstream + "\n" + diagnostics.physical);
            text(root, diagnostics.vpn); text(root, diagnostics.families); text(root, diagnostics.profile); text(root, diagnostics.tools);
        }
        text(root, TunnelObservation.latest.describe());
        text(root, "Pourquoi indisponible ? Le service VPN transporte les applications locales selon le .conf. "
            + "Il ne prouve pas le transport des clients USB/Wi-Fi. Root seul ne prouve pas un pare-feu efficace contre BPF/HAL. "
            + "Ni le profil IPv6, ni le pair, ni les tests de fuite ne sont validés. Le mode obligatoire doit refuser toute ouverture dans cet état.");
        text(root, "Graphe local : révision " + graphHistory.revision + " ; annulations " + graphHistory.undo.size() + " ; rétablissements " + graphHistory.redo.size());
        try { text(root, "Journal des profils antérieurs (migration en lecture seule)\n" + store.journal(scope)); } catch (Exception ignored) { text(root, "Ancien journal illisible, conservé."); }
        button(root, "Qualification et prévisualisation du moteur", v -> qualify(history.current));
    }
    private void editor() {
        shell("Configurer · " + RoutingProfile.title(scope));
        text(root, "Édition d’un brouillon seulement. Le fichier WireGuard, le DNS et les routes actifs ne changent pas. "
            + "IPv4 et IPv6 sont enregistrés ensemble. Les paramètres avancés sont conservés lorsqu’ils sont masqués.");
        RoutingProfile p = history.current;
        mode = spinner(root, "Politique IPv4 / coupure du tunnel", new String[]{"Tunnel obligatoire · tout bloquer si panne ou inconnu", "Accès direct explicite (non protégé)"}, p.mode.ordinal());
        ipv6 = spinner(root, "Politique IPv6", new String[]{"Bloquer IPv6 · accès IPv6-only perdu", "Demander le tunnel IPv6 (preuve requise), ou direct si choisi ci-dessus"}, p.ipv6.ordinal());
        LinearLayout advanced = column(); root.addView(advanced);
        routes4 = field(advanced, "Routes IPv4 CIDR · vide = hériter", String.join(", ", p.routes4));
        routes6 = field(advanced, "Routes IPv6 CIDR · vide si IPv6 bloqué", String.join(", ", p.routes6));
        dns = field(advanced, "DNS numériques · vide = conserver ceux du .conf", String.join(", ", p.dns));
        text(advanced, "Les DNS configurés sont conservés s’ils figurent dans le .conf. Aucun DNS public de secours. "
            + "Les routes n’ajoutent pas de capacité au pair ; une preuve distincte est requise pour chaque famille.");
        rules = new ArrayList<>(p.rules);
        rulesBox = column(); advanced.addView(rulesBox);
        button(advanced, "Ajouter une règle de pare-feu proposée", v -> ruleDialog(-1));
        button(advanced, "Modèle : autoriser le DNS IPv4 saisi TCP + UDP", v -> {
            try {
                RoutingProfile.Path path = scope == RoutingProfile.Scope.ALL ? RoutingProfile.Path.PHONE : RoutingProfile.Path.valueOf(scope.name());
                int priority = nextPriority();
                List<RoutingProfile.Rule> next = new ArrayList<>(rules);
                next.add(FirewallTemplates.homeDns(path, RoutingProfile.Protocol.UDP, priority, dns.getText().toString().trim()));
                next.add(FirewallTemplates.homeDns(path, RoutingProfile.Protocol.TCP, priority + 1, dns.getText().toString().trim()));
                new RoutingProfile(scope, RoutingProfile.Mode.REQUIRE_TUNNEL, RoutingProfile.V6.BLOCK,
                    java.util.Collections.emptyList(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), next);
                rules = next; showRules();
            } catch (Exception ignored) { invalid(); }
        });
        advanced.setVisibility(View.GONE);
        button(root, "Afficher / masquer la vue avancée", v -> advanced.setVisibility(advanced.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        if (pendingEditor != null) {
            mode.setSelection(pendingEditor.getInt("mode")); ipv6.setSelection(pendingEditor.getInt("ipv6"));
            routes4.setText(pendingEditor.getString("r4")); routes6.setText(pendingEditor.getString("r6")); dns.setText(pendingEditor.getString("dns"));
            try { rules = new ArrayList<>(DraftStore.decode(new org.json.JSONObject(pendingEditor.getString("rules"))).rules); }
            catch (Exception ignored) { message("Édition", "Règles non restaurées ; vérifiez le brouillon avant enregistrement."); }
            advanced.setVisibility(pendingEditor.getBoolean("advanced") ? View.VISIBLE : View.GONE);
            pendingEditor = null;
        }
        advanced.setTag("advanced");
        showRules();
        button(root, "Prévisualiser la configuration explicite", v -> {
            try { qualify(edited()); } catch (Exception ignored) { invalid(); }
        });
        button(root, "Enregistrer le brouillon", v -> {
            try {
                RoutingProfile next = edited();
                new AlertDialog.Builder(this).setTitle("Confirmer le brouillon")
                    .setMessage("Aucune activation réseau. Une future application de ce profil pourrait couper Internet ou le LAN. "
                        + "Le blocage IPv6 empêche les destinations IPv6-only.\n\nAVANT\n" + fullPlan(history.current) + "\n\nAPRÈS\n" + fullPlan(next))
                    .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer seulement", (d, w) -> persist(history.save(next), "SAVE")).show();
            } catch (Exception ignored) { invalid(); }
        });
        Button undo = button(root, "Annuler la dernière modification du graphe", v -> new AlertDialog.Builder(this)
            .setTitle("Restaurer la révision précédente ?").setMessage(history.previous == null ? "Aucune révision précédente" : "La révision comprend le graphe, sa disposition et son profil. Les champs non enregistrés seront abandonnés.\n\n" + fullPlan(history.previous))
            .setNegativeButton("Annuler", null).setPositiveButton("Restaurer seulement", (d, w) -> {
                if (persistGraph(graphHistory.undo())) { page = "graph"; render(); }
            }).show());
        undo.setEnabled(history.previous != null);
        button(root, "Qualification / simulation du profil", v -> {
            try { qualify(edited()); } catch (Exception ignored) { invalid(); }
        });
        button(root, "Quitter l’édition sans enregistrer", v -> new AlertDialog.Builder(this).setMessage("Abandonner les modifications non enregistrées ?")
            .setNegativeButton("Continuer l’édition", null).setPositiveButton("Abandonner", (d, w) -> { page = "graph"; render(); }).show());
    }
    private RoutingProfile edited() {
        return new RoutingProfile(scope, RoutingProfile.Mode.values()[mode.getSelectedItemPosition()],
            RoutingProfile.V6.values()[ipv6.getSelectedItemPosition()], RoutingProfile.entries(routes4.getText().toString()),
            RoutingProfile.entries(routes6.getText().toString()), RoutingProfile.entries(dns.getText().toString()), rules);
    }
    private String fullPlan(RoutingProfile p) {
        StringBuilder plan = new StringBuilder(RoutePolicy.plan(p));
        for (RoutingProfile.Rule rule : p.rules) plan.append("\n\n").append(rule.summary());
        return plan.toString();
    }
    private void persist(DraftHistory next, String operation) {
        if (persistGraph(graphHistory.change(graphHistory.current.profile(next.current)))) { page = "graph"; render(); }
    }
    private void showRules() {
        rulesBox.removeAllViews();
        text(rulesBox, "Pare-feu proposé : ordre croissant des priorités ; première correspondance. "
            + "Entrant = vers le téléphone/client choisi ; sortant = depuis celui-ci. Ports = destination du paquet. "
            + "Les règles utilisateur ne pourront jamais contourner la barrière tunnel/IPv6. Aucun filtrage effectif dans cette version.");
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            button(rulesBox, rules.get(i).summary() + "\nModifier / désactiver / supprimer", v -> ruleDialog(index));
        }
    }
    private void ruleDialog(int index) {
        RoutingProfile.Rule r = index < 0 ? null : rules.get(index);
        LinearLayout form = column(); form.setPadding(dp(16), 0, dp(16), 0);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        Spinner direction = spinner(form, "Direction", new String[]{"Entrant", "Sortant", "Transféré (deux sens, clients seulement)"}, r == null ? 1 : r.direction.ordinal());
        Spinner protocol = spinner(form, "Protocole", new String[]{"TCP", "UDP", "ICMP (IPv4)", "ICMPv6 (IPv6)"}, r == null ? 0 : r.protocol.ordinal());
        Spinner family = spinner(form, "Famille IP", new String[]{"IPv4", "IPv6", "Les deux (source/destination vides)"}, r == null ? 0 : r.family.ordinal());
        Spinner path = spinner(form, "Parcours concerné", new String[]{"Téléphone", "Partage Wi-Fi", "Partage USB"}, r == null ? (scope == RoutingProfile.Scope.ALL ? 0 : scope.ordinal()) : r.path.ordinal());
        Spinner action = spinner(form, "Action", new String[]{"Autoriser", "Bloquer"}, r == null ? 1 : r.action.ordinal());
        EditText first = field(form, "Port destination initial (1–65535)", r == null ? "443" : Integer.toString(r.firstPort));
        EditText last = field(form, "Port destination final (identique pour port unique)", r == null ? "443" : Integer.toString(r.lastPort));
        first.setInputType(InputType.TYPE_CLASS_NUMBER); last.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText source = field(form, "Source CIDR · vide = toutes", r == null ? "" : r.source);
        EditText destination = field(form, "Destination CIDR · vide = toutes", r == null ? "" : r.destination);
        EditText inputInterface = field(form, "Interface d’entrée · vide = parcours détecté", r == null ? "" : r.inputInterface);
        EditText outputInterface = field(form, "Interface de sortie · vide = parcours détecté", r == null ? "" : r.outputInterface);
        text(form, "ICMP/ICMPv6 : saisir 0 dans les deux champs de ports. Une interface absente ou incompatible sera refusée par le moteur. Intention seulement ; appliquée = non ; efficacité démontrée = non. Les commandes apparaissent dans Qualification réseau.");
        EditText priority = field(form, "Priorité distincte (1–65535, plus petite d’abord)", r == null ? Integer.toString(nextPriority()) : Integer.toString(r.priority));
        priority.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox enabled = new CheckBox(this); enabled.setText(R.string.rule_draft_enabled); enabled.setChecked(r == null || r.enabled); form.addView(enabled);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Règle proposée, non appliquée").setView(scroll)
            .setNegativeButton("Annuler", null).setPositiveButton("Garder dans l’édition", null)
            .setNeutralButton(r == null ? "Fermer" : "Supprimer de l’édition", (d, w) -> {
                if (index >= 0) { rules.remove(index); showRules(); }
            }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                RoutingProfile.Rule next = new RoutingProfile.Rule(RoutingProfile.Direction.values()[direction.getSelectedItemPosition()],
                    RoutingProfile.Protocol.values()[protocol.getSelectedItemPosition()], RoutingProfile.Family.values()[family.getSelectedItemPosition()],
                    RoutingProfile.Path.values()[path.getSelectedItemPosition()], RoutingProfile.Action.values()[action.getSelectedItemPosition()],
                    Integer.parseInt(first.getText().toString()), Integer.parseInt(last.getText().toString()),
                    source.getText().toString().trim(), destination.getText().toString().trim(), Integer.parseInt(priority.getText().toString()), enabled.isChecked(),
                    inputInterface.getText().toString().trim(), outputInterface.getText().toString().trim());
                List<RoutingProfile.Rule> nextRules = new ArrayList<>(rules);
                if (index < 0) nextRules.add(next); else nextRules.set(index, next);
                // Validate path and duplicate priorities before accepting the rule.
                new RoutingProfile(scope, RoutingProfile.Mode.REQUIRE_TUNNEL, RoutingProfile.V6.BLOCK,
                    java.util.Collections.emptyList(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), nextRules);
                nextRules.sort(Comparator.comparingInt(rule -> rule.priority)); rules = nextRules; showRules(); dialog.dismiss();
            } catch (Exception ignored) { invalid(); }
        }));
        dialog.show();
    }
    private int nextPriority() {
        int candidate = 10;
        for (RoutingProfile.Rule rule : rules) candidate = Math.max(candidate, rule.priority + 10);
        return Math.min(candidate, 65535);
    }
    private void invalid() {
        message("Configuration invalide", "Vérifiez les adresses numériques, préfixes CIDR, familles, ports 1–65535 et priorités distinctes. "
            + "IPv6 bloqué exige des routes IPv6 vides. Une règle « les deux » exige source et destination vides ; "
            + "sinon créez une règle pour chaque famille. Le parcours doit appartenir au profil. Aucun changement enregistré.");
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(16); view.setTextColor(AppStyle.ink(this)); view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(LinearLayout parent, String value, View.OnClickListener click) {
        Button button = new Button(this); button.setText(value); AppStyle.button(button); button.setMinHeight(dp(48)); button.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(5), 0, dp(5)); parent.addView(button, params); return button;
    }
    private Spinner spinner(LinearLayout parent, String label, String[] values, int selected) {
        TextView title = text(parent, label); Spinner spinner = new Spinner(this); spinner.setId(View.generateViewId()); title.setLabelFor(spinner.getId());
        spinner.setContentDescription(label); spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.route_choice, values);
        spinner.setAdapter(adapter); spinner.setSelection(selected); parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2)); return spinner;
    }
    private EditText field(LinearLayout parent, String label, String initial) {
        TextView title = text(parent, label); EditText input = new EditText(this); input.setId(View.generateViewId()); title.setLabelFor(input.getId());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); input.setMinHeight(dp(48));
        input.setText(initial); parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private void message(String title, String value) { new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("Compris", null).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("scope", scope.name()); out.putString("page", page);out.putInt("orientation",getResources().getConfiguration().orientation);
        if ("edit".equals(page) && mode != null) {
            Bundle form = new Bundle(); form.putInt("mode", mode.getSelectedItemPosition()); form.putInt("ipv6", ipv6.getSelectedItemPosition());
            form.putString("r4", routes4.getText().toString()); form.putString("r6", routes6.getText().toString()); form.putString("dns", dns.getText().toString());
            form.putBoolean("advanced", root.findViewWithTag("advanced").getVisibility() == View.VISIBLE);
            try { form.putString("rules", DraftStore.encode(new RoutingProfile(scope, RoutingProfile.Mode.REQUIRE_TUNNEL,
                RoutingProfile.V6.BLOCK, java.util.Collections.emptyList(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), rules)).toString()); }
            catch (Exception ignored) { /* Validated rules only; no config or secret in instance state. */ }
            out.putBundle("editor", form);
        }
    }
    @android.annotation.SuppressLint("GestureBackNavigation") // API33+ uses the native dispatcher registered in onCreate; this override is the API26–32 fallback.
    @Override public void onBackPressed() { requestBack(); }
    private void requestBack() {
        if ("edit".equals(page)) new AlertDialog.Builder(this).setMessage("Abandonner les modifications non enregistrées ?")
            .setNegativeButton("Continuer", null).setPositiveButton("Abandonner", (d, w) -> { page = "graph"; render(); }).show();
        else finish();
    }
    @Override public void onDestroy() { liveHandler.removeCallbacks(liveRefresh); reader.shutdownNow(); super.onDestroy(); }
}
