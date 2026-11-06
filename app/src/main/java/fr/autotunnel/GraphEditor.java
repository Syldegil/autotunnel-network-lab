package io.github.syldegil.autotunnel;

import android.app.*;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

/** Native editor controller; persistence callback is the sole mutation boundary (local draft only). */
final class GraphEditor extends LinearLayout implements GraphCanvas.Listener {
    interface Host {
        boolean persist(GraphHistory next);
        void profile();
        void chooseProfile();
        void diagnostics();
        void refresh();
        void loadTemplate(GraphModel graph);
    }
    private final Activity activity;
    private final Host host;
    private GraphHistory history;
    private final GraphCanvas canvas;
    private final TextView status, selection;
    private String selected;
    private Button undo, redo;
    private boolean expert;
    private NetworkDiagnostics diagnostics;
    private InspectorCollector.Sample sample;
    private InspectorCollector.Sample previousSample;
    private AlertDialog activeDialog;
    private LinearLayout compactBar;
    GraphEditor(Activity activity, GraphHistory history, Host host) {
        super(activity); this.activity = activity; this.history = history; this.host = host;
        canvas = new GraphCanvas(activity, history.current, this);
        expert = activity.getSharedPreferences("graph-ui", android.content.Context.MODE_PRIVATE).getBoolean("expert", false);
        setOrientation(VERTICAL); setPadding(dp(8), 0, dp(8), 0); setBackgroundColor(AppStyle.bg(activity));
        DashboardUi.header(activity,this,"Parcours");
        LinearLayout bar=DashboardUi.row(this);
        button(bar,"＋ Ajouter",this::palette);button(bar,"Relier",this::connectDialog);button(bar,"Éditer",()->edit(selected));
        button(bar,"⋯ Outils",this::tools);
        LinearLayout actions=DashboardUi.row(this);
        undo=button(actions,"↶",()->save(this.history.undo()));undo.setContentDescription("Annuler la dernière modification");
        redo=button(actions,"↷",()->save(this.history.redo()));redo.setContentDescription("Rétablir la modification");
        button(actions,"Cadrer",()->canvas.fit());button(actions,"Vue",this::viewDialog);button(actions,"Aperçu",this::preview);
        for(LinearLayout row:new LinearLayout[]{bar,actions})for(int i=0;i<row.getChildCount();i++){Button b=(Button)row.getChildAt(i);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(3),dp(6),dp(3),dp(6));LayoutParams lp=new LayoutParams(0,-2,1);lp.setMargins(dp(2),dp(3),dp(2),dp(3));if(b==undo||b==redo){lp.width=dp(48);lp.weight=0;}b.setLayoutParams(lp);}
        status = text(this, ""); status.setTextSize(12); status.setMaxLines(2);
        selection = text(this, "Aucune sélection · double toucher : propriétés"); selection.setTextSize(12); selection.setMaxLines(2);
        selection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(canvas, new LayoutParams(-1, 0, 1));
        text(this, "Brouillon local · aucune activation matérielle · pointillés = descriptif").setTextSize(11);

        update();
    }
    private void tools(){
        String[] labels={"Objets et liens","Supprimer la sélection","Modèles et copies","Profil et règles","Choisir le parcours","Ranger les nœuds","Zoom avant","Zoom arrière","Zoom sur la sélection","Enregistrer un repère","Restaurer le repère",expert?"Passer en mode simplifié":"Passer en mode expert","Actualiser l’observation","Diagnostic","Gestes et légende","Déplacer la vue"};
        new AlertDialog.Builder(activity).setTitle("Outils du brouillon").setItems(labels,(d,n)->{switch(n){
          case 0:objects();break;case 1:delete();break;case 2:templates();break;case 3:host.profile();break;case 4:host.chooseProfile();break;case 5:change(history.current.layout());break;
          case 6:canvas.zoom(1.25f);break;case 7:canvas.zoom(.8f);break;case 8:canvas.focusSelected();break;case 9:if(save(history.checkpoint()))feedback("Repère enregistré");break;
          case 10:new AlertDialog.Builder(activity).setMessage("Restaurer le dernier repère ? Cette action reste annulable.").setNegativeButton("Annuler",null).setPositiveButton("Restaurer",(x,y)->save(history.restore())).show();break;
          case 11:expert=!expert;activity.getSharedPreferences("graph-ui",0).edit().putBoolean("expert",expert).apply();feedback(expert?"Mode expert":"Mode simplifié");break;
          case 12:host.refresh();break;case 13:host.diagnostics();break;case 14:message("Gestes et légende","Glisser : déplacer un nœud ou la vue. Port vers port : créer un lien. Double toucher : propriétés.\n\nBleu : VPN interne. Orange : direct. Violet : UDP chiffré. Vert : LAN. Rose : DNS. Rouge : blocage demandé. Pointillés : descriptif.\n\nBrouillon local, aucune règle appliquée.");break;
          case 15:new AlertDialog.Builder(activity).setTitle("Déplacer la vue").setItems(new String[]{"Gauche","Droite","Haut","Bas"},(x,y)->canvas.pan(y==0?90:y==1?-90:0,y==2?90:y==3?-90:0)).show();break;
        }}).setNegativeButton("Fermer",null).show();
    }
    void observation(NetworkDiagnostics diagnostics, InspectorCollector.Sample sample) {
        this.diagnostics = diagnostics;
        if (sample != this.sample) { previousSample = this.sample; this.sample = sample; }
        Map<String, String> labels = new HashMap<>();
        for (Node n : history.current.nodes) {
            if (n.kind == Kind.HOTSPOT) labels.put(n.id, "Hotspot : parcours non qualifié · voir Inspecteur");
            if (n.kind == Kind.WIREGUARD) labels.put(n.id, TunnelObservation.latest.fresh(System.currentTimeMillis())?"WG : mesure locale datée, trajet inconnu":"WG : mesure absente/périmée");
            String iface = !n.properties.inputInterface.isEmpty() ? n.properties.inputInterface : n.properties.outputInterface;
            if (sample != null && !iface.isEmpty() && sample.indices.containsKey(iface)) labels.put(n.id, iface + " observée · aucun lien attribué");
            if (n.kind.monitor()) labels.put(n.id, sample == null ? "Pas de mesure locale" : "Échantillon local · ouvrir détails");
            if (sample != null && !iface.isEmpty() && sample.counters.containsKey(iface)) {
                long[] bytes = sample.counters.get(iface);
                labels.put(n.id, "RX " + bytes[0] + " / TX " + bytes[1] + " o · interface");
            }
        }
        canvas.observations(labels);
        if (history.current.view == ViewMode.LIVE && sample != null) status.setText("LIVE · échantillon " + new Date(sample.at) + (GraphEvidence.fresh(sample.at,System.currentTimeMillis())?" · frais":" · PÉRIMÉ") + " · r" + history.revision + " · NON APPLIQUÉ / NON VÉRIFIÉ");
    }
    void inspectorSelection(String text, long at) {
        if (text == null || text.length() > 12000) return;
        button(bar(), "Connexion sélectionnée · parcours inconnu", () -> message("Observation ponctuelle · " + new Date(at), text));
    }
    private boolean save(GraphHistory next) {
        if (next == history) return true;
        if (!host.persist(next)) { canvas.model(history.current); return false; }
        history = next; update(); observation(diagnostics, sample); return true;
    }
    private void update() {
        canvas.model(history.current); undo.setEnabled(!history.undo.isEmpty()); redo.setEnabled(!history.redo.isEmpty());
        GraphCompiler.Result result = GraphCompiler.compile(history.current);
        status.setText("r" + history.revision + " · " + (result.valid() ? "Brouillon compilable" : result.errors.size() + " erreurs · voir Aperçu") + " · NON APPLIQUÉ");
        status.setTextColor(result.valid() ? AppStyle.green(activity) : AppStyle.color(activity,"#925100","#ffc664"));
        if (selected != null && history.current.node(selected) == null && history.current.link(selected) == null) selected = null;
        selection(selected);
    }
    @Override public void change(GraphModel next) { save(history.change(next)); }
    @Override public void selection(String id) {
        selected = id; Node n = history.current.node(id); Link l = history.current.link(id);
        selection.setText(n != null ? n.label + " · " + n.kind.title : l != null ? label(l) : "Aucune sélection · glisser nœud / port / fond");
    }
    @Override public void feedback(String text) { selection.setText(text); selection.announceForAccessibility(text); }
    @Override public void connect(String from, Port output, String to, Port input) {
        try {
            boolean active = output.type == 0 && history.current.node(from).enabled && history.current.node(to).enabled;
            Link link = new Link(id(), from, output, to, input, active, Properties.defaults());
            if (save(history.change(history.current.replace(link)))) { canvas.select(link.id); feedback(active ? "Lien IP créé ; compilation à vérifier." : "Lien descriptif créé : transport/mesure non piloté."); }
        } catch (IllegalArgumentException e) { message("Connexion refusée", e.getMessage()); }
    }
    private void palette() {
        Kind[] kinds = Kind.values(); String[] labels = new String[kinds.length]; for (int i = 0; i < labels.length; i++) labels[i] = kinds[i].title;
        showTracked(new AlertDialog.Builder(activity).setTitle("Ajouter un nœud libre").setItems(labels, (d, which) -> {
            Kind kind = kinds[which]; boolean active = !(kind.upstream() || kind.monitor());
            try {
                Node n = new Node(id(), kind, kind.title, canvas.centerX(), canvas.centerY(), active, Properties.defaults());
                GraphModel next = history.current.replace(n);
                if (next.view == ViewMode.PHYSICAL && !(kind.upstream() || kind == Kind.WIREGUARD || kind == Kind.INTERNET || kind == Kind.INTERFACE || kind == Kind.PHONE_INTERFACE || kind == Kind.USB || kind == Kind.HOTSPOT || kind == Kind.FREEBOX))
                    next = next.camera(next.panX, next.panY, next.zoom, ViewMode.LOGICAL);
                if (save(history.change(next))) canvas.select(n.id);
            } catch (IllegalArgumentException e) { message("Ajout refusé", e.getMessage()); }
        }).create());
    }
    private void templates(){
        GraphLibrary library=new GraphLibrary(activity);List<String> names=library.names();List<String> labels=new ArrayList<>(Arrays.asList(GraphTemplates.TITLES));
        labels.add("Dupliquer ce graphe dans la bibliothèque");for(int i=0;i<names.size();i++)labels.add("Copie "+(i+1)+" · "+names.get(i).substring(5,13));
        showTracked(new AlertDialog.Builder(activity).setTitle("Modèles déclaratifs · aucun réseau appliqué").setItems(labels.toArray(new String[0]),(d,which)->{
            try{
                if(which==GraphTemplates.TITLES.length){library.duplicate(history.current);feedback("Copie privée enregistrée dans la bibliothèque.");return;}
                GraphModel model=which<GraphTemplates.TITLES.length?GraphTemplates.create(which):library.load(names.get(which-GraphTemplates.TITLES.length-1));
                new AlertDialog.Builder(activity).setMessage("Charger dans le brouillon "+model.profile.scope+" ? Le brouillon précédent reste dans Annuler. Aucun changement réseau.")
                    .setNegativeButton("Annuler",null).setPositiveButton("Charger",(dd,w)->host.loadTemplate(model)).show();
            }catch(Exception e){message("Bibliothèque",e.getMessage());}
        }).create());
    }
    private String label(Link l) { return history.current.node(l.from).label + " / " + l.output.title + " → " + history.current.node(l.to).label + " / " + l.input.title; }
    private void objects() {
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        for (Node n : history.current.nodes) { ids.add(n.id); labels.add("Nœud · " + n.label + (n.enabled ? " · actif" : " · descriptif")); }
        for (Link l : history.current.links) { ids.add(l.id); labels.add("Lien · " + label(l) + (l.enabled ? " · actif" : " · descriptif")); }
        new AlertDialog.Builder(activity).setTitle("Sélection accessible · nœuds et liens").setItems(labels.toArray(new String[0]), (d, which) -> {
            canvas.select(ids.get(which)); edit(ids.get(which));
        }).setNegativeButton("Fermer", null).show();
    }
    private void connectDialog() {
        List<Node> nodes = new ArrayList<>(); List<Port> ports = new ArrayList<>(); List<String> labels = new ArrayList<>();
        for (Node n : history.current.nodes) for (Port p : n.kind.ports()) if (p.output) { nodes.add(n); ports.add(p); labels.add(n.label + " · " + p.title); }
        if (labels.isEmpty()) { message("Connecter", "Ajoutez un nœud avec un port de sortie."); return; }
        new AlertDialog.Builder(activity).setTitle("1 · Choisir la sortie typée").setItems(labels.toArray(new String[0]), (d, w) -> {
            Node source = nodes.get(w); Port output = ports.get(w); List<Node> targets = new ArrayList<>(); List<Port> inputs = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (Node n : history.current.nodes) if (!n.id.equals(source.id)) for (Port p : n.kind.ports()) if (output.accepts(p)) { targets.add(n); inputs.add(p); names.add(n.label + " · " + p.title); }
            if (names.isEmpty()) { message("Connecter", "Aucun port d’entrée compatible. Ajoutez un nœud adapté à ce type de transport."); return; }
            new AlertDialog.Builder(activity).setTitle("2 · Entrée compatible").setItems(names.toArray(new String[0]), (dd, ww) -> connect(source.id, output, targets.get(ww).id, inputs.get(ww))).show();
        }).show();
    }
    private void delete() {
        if (selected == null) { feedback("Sélectionnez un nœud ou un lien."); return; }
        String target = selected; new AlertDialog.Builder(activity).setMessage("Supprimer la sélection et ses liens éventuels ? Annuler permettra de les restaurer.")
            .setNegativeButton("Annuler", null).setPositiveButton("Supprimer", (d, w) -> change(history.current.remove(target))).show();
    }
    private void viewDialog() {
        new AlertDialog.Builder(activity).setTitle("Vue du même graphe").setItems(new String[]{"Logique · intentions de routage", "Physique · UDP extérieur et amont", "Live · observations locales séparées"}, (d, w) -> {
            change(history.current.camera(history.current.panX, history.current.panY, history.current.zoom, ViewMode.values()[w]));
            if (w == 2) host.refresh();
        }).show();
    }
    @Override public void edit(String id) {
        Node node = history.current.node(id); Link link = history.current.link(id);
        if (node == null && link == null) { feedback("Sélectionnez un objet ou ouvrez Objets."); return; }
        Properties p = node != null ? node.properties : link.properties;
        LinearLayout form = column(); ScrollView scroll = new ScrollView(activity); scroll.addView(form); form.setPadding(dp(16), dp(8), dp(16), dp(8));
        form.setBackgroundColor(AppStyle.panel(activity));
        EditText name = node == null ? null : field(form, "Nom affiché (aucune clé ni secret)", node.label);
        if(link!=null)button(form,"État du lien / preuves",()->message("Niveaux de preuve du lien",GraphEvidence.link(history.current,link)));
        if(node!=null)button(form,"Observations datées du nœud",()->details(node));
        CheckBox enabled = new CheckBox(activity); enabled.setText("Actif dans la compilation (sinon descriptif)"); enabled.setTextColor(AppStyle.ink(activity)); enabled.setChecked(node != null ? node.enabled : link.enabled); form.addView(enabled);
        text(form, "Descriptif = conservé et dessiné, explicitement exclu du moteur. Une fonction active non représentable bloque la compilation.");
        Spinner family = spinner(form, "Famille", new String[]{"Hériter du profil", "IPv4", "IPv6", "IPv4 + IPv6"}, p.family == null ? 0 : p.family.ordinal() + 1);
        Spinner policy = spinner(form, "Politique", new String[]{"Hériter du profil", "Direct explicite", "WireGuard requis"}, p.policy.ordinal());
        CheckBox block = new CheckBox(activity); block.setText("Bloquer si indisponible / inconnu"); block.setTextColor(AppStyle.ink(activity)); block.setChecked(p.blockUnavailable); form.addView(block);
        LinearLayout advanced = column(); form.addView(advanced); advanced.setVisibility(expert ? View.VISIBLE : View.GONE);
        button(form, "Afficher / masquer les propriétés expertes", () -> advanced.setVisibility(advanced.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        EditText source = field(advanced, "Source CIDR · famille explicite requise", p.source), destination = field(advanced, "Destination CIDR", p.destination);
        EditText input = field(advanced, "Interface source/entrée", p.inputInterface), output = field(advanced, "Interface destination/sortie", p.outputInterface);
        text(advanced, "Les sélecteurs locaux sont conservés mais refusés à la compilation : utilisez les règles pare-feu pour une traduction exacte. Interface indiquée ≠ attribution observée.");
        boolean dnsOwner = node == null || node.kind == Kind.DNS;
        boolean routeOwner = node == null || node.kind == Kind.ROUTER;
        EditText dns = dnsOwner ? field(form, "DNS numériques · vide = hériter, sans secours", String.join(", ", p.dns)) : null;
        EditText routes4 = routeOwner ? field(form, "Routes IPv4 CIDR", String.join(", ", p.routes4)) : null;
        EditText routes6 = routeOwner ? field(form, "Routes IPv6 CIDR", String.join(", ", p.routes6)) : null;
        EditText x = node == null ? null : field(advanced, "Position X (alternative accessible au glisser)", Float.toString(node.x));
        EditText y = node == null ? null : field(advanced, "Position Y", Float.toString(node.y));
        if (x != null) { x.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL); y.setInputType(x.getInputType()); }
        Button profileButton = null;
        if (node != null) {
            text(form, role(node));
            if (node.kind == Kind.WIREGUARD) {
                text(form, TunnelObservation.latest.describe());
                text(form, diagnostics == null ? "Profil importé : observation indisponible" : diagnostics.profile + "\n" + diagnostics.families);
                text(form, "Politique du brouillon : " + history.current.profile.mode + " ; aucun fail-closed matériel qualifié.");
            }
            if (node.kind == Kind.FIREWALL || node.kind == Kind.IPV6 || node.kind == Kind.KILL_SWITCH) profileButton = button(form, "Éditer le profil / règles typées du pare-feu", () -> { });
            button(form, "Observations et limites de ce nœud", () -> details(node));
        } else {
            text(form, "Lien déclaré " + link.output.title + " → " + link.input.title + ". Aucun paquet ni retour attribué. DNS/routes par lien restent non représentables. Les règles sont celles du pare-feu du profil, pas un filtre indépendant attaché au lien.");
            profileButton = button(form, "Éditer les règles pare-feu du profil", () -> { });
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(node != null ? node.kind.title : "Propriétés du lien").setView(scroll)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        if (profileButton != null) profileButton.setOnClickListener(v -> { dialog.dismiss(); host.profile(); });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Properties next = new Properties(family.getSelectedItemPosition() == 0 ? null : RoutingProfile.Family.values()[family.getSelectedItemPosition() - 1],
                    Policy.values()[policy.getSelectedItemPosition()], block.isChecked(), value(source), value(destination), value(input), value(output),
                    dns == null ? p.dns : RoutingProfile.entries(value(dns)), routes4 == null ? p.routes4 : RoutingProfile.entries(value(routes4)), routes6 == null ? p.routes6 : RoutingProfile.entries(value(routes6)));
                GraphModel model = node != null ? history.current.replace(new Node(node.id, node.kind, value(name), Float.parseFloat(value(x)), Float.parseFloat(value(y)), enabled.isChecked(), next))
                    : history.current.replace(new Link(link.id, link.from, link.output, link.to, link.input, enabled.isChecked(), next));
                if (save(history.change(model))) dialog.dismiss();
            } catch (IllegalArgumentException e) { message("Propriétés invalides", "Vérifiez famille, CIDR numériques, interfaces (15 caractères maximum), nom et positions. " + e.getMessage()); }
        })); showTracked(dialog);
    }
    private String role(Node n) {
        switch (n.kind) {
            case WIREGUARD: return "Overlay IP distinct du port UDP chiffré extérieur. Profil importé consultable en diagnostic ; aucune clé, pair ou configuration Vault dans le graphe. UP/handshake ne certifient pas les trajets.";
            case FIREWALL: return "Les règles du profil sont celles de cet unique pare-feu actif : protocole, plage de ports destination, source/destination, interfaces, famille, sens, action, priorité. Aucun second pare-feu actif représentable.";
            case DNS: return "DNS du nœud ou du profil, un seul propriétaire. Vide hérite ; aucun DNS de secours ajouté.";
            case ROUTER: return "Routes déclaratives locales ; ne changent pas les AllowedIPs. Le routage par source/interface doit passer par une règle ou reste refusé.";
            case NAT4: return "NAT IPv4 personnalisé non représentable. La politique de partage compilée gère son NAT sous contrôle du moteur, sans module NAT libre.";
            case IPV6: return "Politique de famille héritée du profil ; IPv6 seule ne représente pas le profil IPv4/IPv6 du moteur.";
            case KILL_SWITCH: return "Intention tunnel obligatoire et fermeture sur panne ; efficacité matérielle non qualifiée.";
            case FREEBOX: case HOME_LAN: case EXTERNAL_CLIENT: return "Équipement distant déclaré, identité et routes de retour inconnues. Aucun accès distant ni administration depuis ce nœud. Fonction active non représentable.";
            default: return n.kind.upstream() ? "Transport candidat déclaré. Aucun choix d’amont, SSID ou génération mobile déduit ; port transport séparé de l’overlay. Descriptif uniquement."
                : n.kind.monitor() ? "Vue de mesures locales existantes, sans sonde active, limitation de débit ni preuve de fuite. Descriptif uniquement."
                : "Configuration souhaitée ; état observé présenté séparément. Aucun trajet vérifié par ce nœud.";
        }
    }
    private void details(Node node) {
        StringBuilder out = new StringBuilder(role(node)).append("\n\nCONFIGURÉ : ").append(node.enabled ? "actif dans brouillon" : "descriptif").append("\nAPPLIQUÉ : non\nVÉRIFIÉ : non qualifié\n");
        if (node.kind == Kind.WIREGUARD) {
            out.append(TunnelObservation.latest.describe()).append("\nPolitique du brouillon : ").append(history.current.profile.mode)
                .append(" ; fermeture demandée, efficacité matérielle inconnue\n");
            if (diagnostics != null) out.append("Profil importé (paramètres non secrets) :\n").append(diagnostics.profile)
                .append('\n').append(diagnostics.families).append('\n').append(diagnostics.vpn).append('\n').append(diagnostics.upstream);
            else out.append("Paramètres du profil non observés ; utiliser Observer.");
        }
        else if (node.kind.upstream() && diagnostics != null) out.append("Échantillon Android ").append(new Date(diagnostics.at)).append('\n').append(diagnostics.upstream).append('\n').append(diagnostics.physical);
        else if (sample != null) {
            out.append("Échantillon ponctuel ").append(new Date(sample.at)).append(" (ne prouve pas l’état courant)\n");
            if (node.kind == Kind.COUNTER || node.kind == Kind.RATE) {
                out.append("Compteurs d’interface cumulés RX/TX, non attribués aux liens ; débit : ouvrir Inspecteur pour les deltas.\n");
                for (Map.Entry<String, long[]> entry : sample.counters.entrySet()) {
                    out.append(entry.getKey()).append(" : ").append(Arrays.toString(entry.getValue())).append(" octets");
                    long[] old = previousSample == null ? null : previousSample.counters.get(entry.getKey());
                    long dt = previousSample == null ? 0 : sample.elapsed - previousSample.elapsed;
                    if (old != null && dt > 0 && dt <= 15000 && Objects.equals(sample.contextHash, previousSample.contextHash)
                        && Objects.equals(sample.indices.get(entry.getKey()), previousSample.indices.get(entry.getKey()))
                        && sample.indices.containsKey(entry.getKey()) && entry.getValue()[0] >= old[0] && entry.getValue()[1] >= old[1])
                        out.append(" ; RX/TX ").append((entry.getValue()[0] - old[0]) * 1000.0 / dt).append(" / ").append((entry.getValue()[1] - old[1]) * 1000.0 / dt).append(" o/s calculés sur l’intervalle");
                    else out.append(" ; débit inconnu (première mesure, contexte changé ou compteurs réinitialisés)");
                    out.append('\n');
                }
            } else if (node.kind == Kind.INSPECTOR) out.append("Sockets visibles : ").append(sample.connections.size()).append('\n').append(sample.availability);
            else if (node.kind == Kind.LEAK_DETECTION) out.append("Aucun test de fuite réalisé ; compteurs, socket ou état UP ne démontrent pas l’absence de fuite.");
            else if (node.kind == Kind.LOGS) out.append("Révision locale du graphe : ").append(history.revision).append(" ; journal matériel indisponible. Aucun trafic journalisé par cet éditeur.");
            else out.append(sample.network);
        } else out.append("Aucune observation publique disponible. Utiliser Observer.");
        if(sample!=null){
            out.append("\nHorodatage : ").append(new Date(sample.at)).append(GraphEvidence.fresh(sample.at,System.currentTimeMillis())?" · frais (15s)":" · PÉRIMÉ : état courant inconnu");
            for(Map.Entry<String,String> entry:sample.types.entrySet())if(node.kind==Kind.WIFI&&entry.getValue().equals("Wi-Fi")
                ||(node.kind==Kind.MOBILE_3G||node.kind==Kind.MOBILE_4G||node.kind==Kind.MOBILE_5G)&&entry.getValue().startsWith("Mobile")){
                out.append("\nInterface candidate ").append(entry.getKey()).append("\n").append(sample.interfaceDetails.get(entry.getKey()));
                long[] bytes=sample.counters.get(entry.getKey());if(bytes!=null)out.append("\nRX/TX interface ").append(bytes[0]).append('/').append(bytes[1]).append(" · ").append(sample.counterSources.get(entry.getKey()));
                out.append("\nType radio 3G/4G/5G exact et attribution du lien non attestés.");
            }
        }
        if(node.kind==Kind.FIREWALL){out.append("\nRègles prévues :\n");for(RoutingProfile.Rule r:history.current.profile.rules)out.append(r.summary()).append('\n');out.append("Règles réellement présentes / compteurs de rejet : non attribués dans ce relevé public. Lecture root de qualification nécessaire, aucun rejet inventé.");}
        if(node.kind==Kind.USB||node.kind==Kind.HOTSPOT)out.append("\nClients/baux/forwarding : non attribués par les API publiques. Qualifier le chemin via tethering/root, pas par un nom d’interface supposé.");
        message(node.label, out.toString());
    }
    private void preview() {
        GraphCompiler.Result result = GraphCompiler.compile(history.current);
        if (!result.valid()) { message("Compilation stricte", result.report()); return; }
        String report;
        try {
            SimulatedNetworkBackend backend = new SimulatedNetworkBackend(System.currentTimeMillis());
            NetworkEngine engine = new NetworkEngine(backend, new NetworkEngine.MemoryJournal());
            NetworkPlan plan = GraphCompiler.preview(history.current, engine);
            String context = sample == null ? NetworkPlan.hash("NOT_OBSERVED") : sample.contextHash;
            CapabilityMatrix matrix = new QualificationStore(activity).load();
            StringBuilder capabilities = new StringBuilder();
            for (CapabilityMatrix.Path path : CapabilityMatrix.paths(plan)) {
                capabilities.append('\n').append(path).append(':');
                for (CapabilityMatrix.Capability cap : matrix.requirements(result.topology).get(path)) capabilities.append('\n').append(cap).append(" = ").append(matrix.status(cap, path, context, System.currentTimeMillis()));
            }
            report = result.report() + "\n\nRoutePolicy sans preuves : IPv4=" + RoutePolicy.intents(result.topology, RoutingProfile.Family.IPV4, Collections.emptyMap(), System.currentTimeMillis())
                + " ; IPv6=" + RoutePolicy.intents(result.topology, RoutingProfile.Family.IPV6, Collections.emptyMap(), System.currentTimeMillis())
                + "\n\nPRÉVISUALISATION SIMULÉE · interfaces sim*, aucune observation matérielle utilisée comme autorisation\n" + plan.preview()
                + "\n\nCapacités dans le contexte observé (aucune autorisation)" + capabilities + "\n\nVerrou matériel = " + GraphCompiler.HARDWARE_ACTIVATION_ENABLED;
        } catch (Exception e) {
            message("Prévisualisation refusée", "Graphe traduisible, mais le moteur refuse le contexte simulé : "
                + (e instanceof IllegalArgumentException ? e.getMessage() : "contexte ou prévisualisation indisponible") + ". Aucun repli ni application."); return;
        }
        LinearLayout form = column(); form.setPadding(dp(16), dp(8), dp(16), dp(8)); form.setBackgroundColor(AppStyle.panel(activity)); ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        text(form, report); Button activate = button(form, "Appliquer au matériel · verrouillé", () -> { }); activate.setEnabled(false);
        showTracked(new AlertDialog.Builder(activity).setTitle("Graphe → profil → moteur").setView(scroll).setNegativeButton("Fermer", null)
            .setPositiveButton("Simulation transactionnelle / capacités", (d, w) -> TopologyPreview.create(activity,result.topology).show()).create());
    }
    private void showTracked(AlertDialog dialog) {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        dialog.show();
    }
    @Override protected void onDetachedFromWindow() {
        if (activeDialog != null) activeDialog.dismiss();
        super.onDetachedFromWindow();
    }
    void recenter(){canvas.post(canvas::fit);}
    private LinearLayout bar() {
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if(landscape&&compactBar!=null)return compactBar;
        HorizontalScrollView scroll=new HorizontalScrollView(activity);LinearLayout row=new LinearLayout(activity);scroll.addView(row);addView(scroll,new LayoutParams(-1,-2));if(landscape)compactBar=row;return row;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(VERTICAL); return v; }
    private TextView text(LinearLayout parent, String value) { TextView v = new TextView(activity); v.setText(value); v.setTextColor(AppStyle.ink(activity)); v.setTextSize(14); v.setPadding(dp(4), dp(4), dp(4), dp(4)); parent.addView(v); return v; }
    private Button button(LinearLayout parent, String label, Runnable action) { Button v = new Button(activity); v.setText(label); AppStyle.button(v); v.setTextSize(12); v.setMinHeight(dp(48)); v.setOnClickListener(w -> action.run()); LayoutParams params=new LayoutParams(parent.getOrientation()==HORIZONTAL?-2:-1,-2);params.setMargins(dp(3),dp(3),dp(3),dp(3));parent.addView(v,params);return v; }
    private EditText field(LinearLayout parent, String label, String value) { TextView title = text(parent, label); EditText input = new EditText(activity); input.setId(View.generateViewId()); title.setLabelFor(input.getId()); input.setTextColor(AppStyle.ink(activity)); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); input.setText(value); parent.addView(input, new LayoutParams(-1, dp(52))); return input; }
    private Spinner spinner(LinearLayout parent, String label, String[] values, int selected) { TextView title = text(parent, label); Spinner spinner = new Spinner(activity); spinner.setBackgroundColor(AppStyle.panel(activity)); spinner.setId(View.generateViewId()); title.setLabelFor(spinner.getId()); spinner.setContentDescription(label); spinner.setMinimumHeight(dp(48)); spinner.setAdapter(new ArrayAdapter<>(activity, R.layout.route_choice, values)); spinner.setSelection(selected); parent.addView(spinner); return spinner; }
    private String value(EditText field) { return field.getText().toString().trim(); }
    private String id() { return UUID.randomUUID().toString(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String title, String value) { new AlertDialog.Builder(activity).setTitle(title).setMessage(value).setPositiveButton("Fermer", null).show(); }
}
