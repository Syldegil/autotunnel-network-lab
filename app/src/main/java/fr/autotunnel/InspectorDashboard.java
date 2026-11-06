package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native presentation of dated collector observations; no probes, WebView or synthetic traffic. */
final class InspectorDashboard extends LinearLayout {
    private final Activity activity;
    private final InspectorHistory history = new InspectorHistory();
    private final LinearLayout overview, networks, diagnostics, networkRows;
    private final TextView stamp, rates, transport, wireguard, route, destination, steps, internetHint, vpnHint, rxValue, txValue;
    private final Button pauseButton;
    private final TrafficTiles physicalRates;
    private Runnable resumeAction, pauseAction;
    private boolean collecting;
    private final InspectorVisuals.Icon accessIcon,internetIcon;
    private final Spinner interfaces, periods, filters;
    private final EditText search;
    private final Chart chart;
    private final DiagnosticPanel diagnosticPanel;
    private final int bg, panel, ink, muted, green, blue;
    private InspectorCollector.Sample sample;
    private String selected = "";
    private long window = 60000;
    private final List<Button> tabs = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    InspectorDashboard(Activity activity) {
        super(activity); this.activity = activity;
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bg = Color.parseColor(dark ? "#0c121b" : "#f2f5f8"); panel = Color.parseColor(dark ? "#141e2b" : "#ffffff");
        ink = Color.parseColor(dark ? "#eff5fc" : "#15283a"); muted = Color.parseColor(dark ? "#9caec2" : "#53677b");
        green = Color.parseColor(dark ? "#77e0bd" : "#086a50"); blue = Color.parseColor(dark ? "#9bbdff" : "#315fb7");
        setOrientation(VERTICAL); setBackgroundColor(bg); setPadding(dp(16),dp(12),dp(16),dp(16));
        DashboardUi.header(activity,this,"Réseau");
        LinearLayout live=new LinearLayout(activity);live.setGravity(Gravity.CENTER_VERTICAL);addView(live);
        stamp=label(live,"Observation en pause",12,muted);stamp.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        pauseButton=button(live,"Reprendre",()->{if(collecting){if(pauseAction!=null)pauseAction.run();}else if(resumeAction!=null)resumeAction.run();});pauseButton.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));pauseButton.setTextSize(12);

        LinearLayout nav = new LinearLayout(activity); nav.setOrientation(HORIZONTAL); addView(nav);
        overview = section(); networks = section(); diagnostics = section();
        String[] titles = {"Débits", "Adresses", "Tester"};
        for (int i=0; i<titles.length; i++) {
            final int page=i; Button b=button(nav,titles[i], () -> show(page)); b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));tabs.add(b);
        }
        LinearLayout map=card(overview,"VOTRE CONNEXION");
        transport=label(map,"Lecture du réseau…",25,ink);internetHint=label(map,"Les informations apparaîtront ici.",14,muted);
        LinearLayout diagram=new LinearLayout(activity);diagram.setGravity(Gravity.CENTER_VERTICAL);diagram.setPadding(0,dp(12),0,0);map.addView(diagram);
        diagramNode(diagram,"phone","Téléphone",blue);connector(diagram);accessIcon=diagramNode(diagram,"globe","Réseau",blue);connector(diagram);internetIcon=diagramNode(diagram,"globe","Internet",muted);
        map.setOnClickListener(v->detail("Votre connexion",sample==null?"Aucune observation courante.":sample.network));map.setFocusable(true);map.setContentDescription("Votre connexion. Toucher pour voir les détails.");
        LinearLayout pair=new LinearLayout(activity);pair.setBaselineAligned(false);overview.addView(pair);
        LinearLayout vpn=card(pair,"TUNNEL VPN");LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-1,1);left.setMargins(0,dp(12),dp(5),0);vpn.setLayoutParams(left);vpn.setMinimumHeight(dp(160));
        vpn.addView(new InspectorVisuals.Icon(activity,"shield",blue),new LinearLayout.LayoutParams(dp(32),dp(32)));wireguard=label(vpn,"État inconnu",19,ink);vpnHint=label(vpn,"Pas d’observation récente",12,muted);vpn.setOnClickListener(v->detail("Tunnel AutoTunnel",TunnelObservation.latest.describe()));vpn.setFocusable(true);
        LinearLayout last=card(pair,"DIAGNOSTIC");LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-1,1);right.setMargins(dp(5),dp(12),0,0);last.setLayoutParams(right);last.setMinimumHeight(dp(160));
        last.addView(new InspectorVisuals.Icon(activity,"server",blue),new LinearLayout.LayoutParams(dp(32),dp(32)));destination=label(last,"Aucun serveur testé",19,ink);route=label(last,"Toucher pour tester",12,muted);route.setMaxLines(2);route.setEllipsize(android.text.TextUtils.TruncateAt.END);last.setOnClickListener(v->show(2));last.setFocusable(true);last.setContentDescription("Dernier test. Toucher pour tester une destination.");
        LinearLayout physical=card(overview,"DÉBITS · ↓ REÇU  /  ↑ ENVOYÉ");
        overview.removeView(physical);overview.addView(physical,0);
        physicalRates=new TrafficTiles(activity);physical.addView(physicalRates);
        button(physical,"ⓘ Comprendre les mesures",()->detail("Débits observés","Réception et envoi par interface, sur une minute. Échelles propres à chaque panneau. Les interruptions restent vides ; — signifie indisponible. Compteurs partiels et non additionnables. USB : partage réseau, hors MTP/ADB. Un nom d’interface seul indique un type présumé."));
        LinearLayout traffic=card(overview,"DÉBIT EN CE MOMENT");
        LinearLayout numbers=new LinearLayout(activity);traffic.addView(numbers);
        LinearLayout down=new LinearLayout(activity);down.setOrientation(VERTICAL);numbers.addView(down,new LinearLayout.LayoutParams(0,-2,1));label(down,"↓ Réception",13,green);rxValue=label(down,"—",25,green);
        LinearLayout up=new LinearLayout(activity);up.setOrientation(VERTICAL);numbers.addView(up,new LinearLayout.LayoutParams(0,-2,1));label(up,"↑ Envoi",13,blue);txValue=label(up,"—",25,blue);
        rates=label(traffic,"Deux relevés nécessaires",12,muted);
        chart=new Chart(activity);traffic.addView(chart,new LinearLayout.LayoutParams(-1,dp(110)));
        LinearLayout settings=new LinearLayout(activity);settings.setOrientation(VERTICAL);traffic.addView(settings);settings.setVisibility(GONE);
        interfaces=new Spinner(activity);settings.addView(interfaces);interfaces.setOnItemSelectedListener(listener(()->{Object value=interfaces.getSelectedItem();selected=value==null?"":value.toString();drawTraffic();}));
        periods=new Spinner(activity);periods.setAdapter(adapter(Arrays.asList("1 min","5 min","15 min")));settings.addView(periods);periods.setOnItemSelectedListener(listener(()->{window=new long[]{60000,300000,900000}[periods.getSelectedItemPosition()];drawTraffic();}));
        label(settings,"Une seule interface ; le trafic du téléphone et celui du tunnel ne s’additionnent pas. Les interruptions restent vides.",12,muted);
        button(traffic,"Interface et durée",()->settings.setVisibility(settings.getVisibility()==VISIBLE?GONE:VISIBLE));
        networks.addView(new NetworkSummaryView(activity));
        search=new EditText(activity); search.setSingleLine(true); search.setTextColor(ink); search.setHintTextColor(muted);
        search.setHint("Rechercher une interface, IP ou route"); search.setContentDescription("Rechercher dans les réseaux et routes"); networks.addView(search);
        filters=new Spinner(activity); filters.setAdapter(adapter(Arrays.asList("Tous", "VPN", "Wi-Fi", "Mobile"))); networks.addView(filters);
        filters.setOnItemSelectedListener(listener(this::renderNetworks));
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int before,int c) { renderNetworks(); } public void afterTextChanged(Editable e) {} });
        networkRows=new LinearLayout(activity); networkRows.setOrientation(VERTICAL); networks.addView(networkRows);
        LinearLayout active=card(diagnostics,"Tester une destination");
        diagnosticPanel=new DiagnosticPanel(activity,ink,muted); active.addView(diagnosticPanel); diagnosticPanel.onChange=this::updateLastTest;
        LinearLayout diagnostic=card(diagnostics,"Pour aller plus loin");
        steps=label(diagnostic,"Aucune observation courante.",14,muted); steps.setVisibility(GONE);
        button(diagnostic,"Observations techniques",()->steps.setVisibility(steps.getVisibility()==VISIBLE?GONE:VISIBLE));
        button(diagnostic,"Exporter le bilan anonymisé",this::export);

        show(0); clear();
    }
    private AdapterView.OnItemSelectedListener listener(Runnable action) { return new AdapterView.OnItemSelectedListener() {
        public void onNothingSelected(AdapterView<?> p) {} public void onItemSelected(AdapterView<?> p,View v,int position,long id) { action.run(); }
    }; }
    private ArrayAdapter<String> adapter(List<String> values) { return new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,values) {
        @Override public View getView(int p,View v,android.view.ViewGroup parent) { TextView t=(TextView)super.getView(p,v,parent); t.setTextColor(ink); t.setMaxLines(2); return t; }
    }; }
    private LinearLayout section() { LinearLayout l=new LinearLayout(activity); l.setOrientation(VERTICAL); addView(l); return l; }
    private LinearLayout card(LinearLayout parent,String title) {
        LinearLayout l=new LinearLayout(activity); l.setOrientation(VERTICAL); l.setPadding(dp(16),dp(16),dp(16),dp(16));
        GradientDrawable shape=new GradientDrawable(); shape.setColor(panel); shape.setCornerRadius(dp(20)); l.setBackground(shape);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,dp(12),0,0); parent.addView(l,params);
        label(l,title,12,muted); return l;
    }
    private TextView label(LinearLayout parent,String value,int size,int color) {
        TextView t=new TextView(activity); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(6),0,dp(6)); parent.addView(t); return t;
    }
    private Button button(LinearLayout p,String text,Runnable action) { Button b=new Button(activity); b.setText(text); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setTextColor(ink); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg)); b.setOnClickListener(v->action.run()); p.addView(b,new LinearLayout.LayoutParams(-1,-2)); return b; }
    private TextView node(LinearLayout p,String title,Runnable action) { return button(p,title,action); }
    private void arrow(LinearLayout p) { TextView t=label(p,"↓  chemin non vérifié",12,muted); t.setGravity(Gravity.CENTER); }
    void show(int page) { if(page!=2 && diagnosticPanel!=null) diagnosticPanel.stop(false); overview.setVisibility(page==0?VISIBLE:GONE); networks.setVisibility(page==1?VISIBLE:GONE); diagnostics.setVisibility(page==2?VISIBLE:GONE); for(int i=0;i<tabs.size();i++) { tabs.get(i).setSelected(i==page); tabs.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(i==page?blend(green,panel):panel)); tabs.get(i).setTextColor(i==page?green:muted); tabs.get(i).setContentDescription(tabs.get(i).getText()+(i==page?", onglet sélectionné":", onglet")); } }
    void update(InspectorCollector.Sample next) {
        boolean followActive=sample==null||selected.equals(sample.activeInterface);
        sample=next; Map<String,String> ids=new TreeMap<>();
        for(String name:next.counters.keySet()) if(next.indices.containsKey(name)) ids.put(name,next.contextHash+"|"+next.indices.get(name)+"|"+next.source+"|"+next.counterSources.get(name));
        history.add(next.elapsed,next.counters,ids);
        stamp.setText((collecting?"● En direct · ":"Relevé · ")+new java.text.SimpleDateFormat("HH:mm:ss",Locale.FRANCE).format(new Date(next.at)));
        transport.setText(InspectorMeaning.connection(next.networkKnown,next.activeNetworkPresent,next.activeRole));
        internetHint.setText(InspectorMeaning.internet(next.networkKnown,next.activeNetworkPresent,next.activeValidated));
        accessIcon.kind(next.activeRole.equals("Wi-Fi")?"wifi":next.activeRole.startsWith("Mobile")?"mobile":next.activeRole.startsWith("VPN")?"shield":"globe");accessIcon.color(next.networkKnown&&next.activeNetworkPresent?green:muted);internetIcon.color(next.networkKnown&&next.activeValidated?green:muted);
        TunnelObservation tunnel=TunnelObservation.latest;wireguard.setText(InspectorMeaning.tunnel(tunnel.fresh(System.currentTimeMillis()),tunnel.state));
        vpnHint.setText((AutoService.running?"Automatisation active":"Automatisation arrêtée"));
        updateLastTest();
        List<String> names=new ArrayList<>(next.counters.keySet()); names.sort(Comparator.comparingInt(name -> name.equals(next.activeInterface)?0:next.types.containsKey(name)?1:2)); if(names.isEmpty()) names.add("Aucun compteur disponible");
        List<String> existing=new ArrayList<>(); for(int i=0;i<interfaces.getCount();i++) existing.add(interfaces.getItemAtPosition(i).toString());
        if(!names.equals(existing)) { interfaces.setAdapter(adapter(names)); int index=names.indexOf(selected); interfaces.setSelection(Math.max(0,index)); selected=names.get(Math.max(0,index)); }
        if(followActive&&names.contains(next.activeInterface)){selected=next.activeInterface;interfaces.setSelection(names.indexOf(selected));}
        drawTraffic(); renderNetworks();
        physicalRates.update(next, history);
        steps.setText("Observation : "+new Date(next.at)+"\nOrigine : "+next.source
            +"\n\n1 · Transport : "+(next.types.isEmpty()?"INDISPONIBLE":"PARTIELLEMENT OBSERVÉ (API Android)")
            +"\n2 · Pair WireGuard : "+(TunnelObservation.latest.fresh(System.currentTimeMillis())?"observation disponible, accès au pair NON TESTÉ":"INDISPONIBLE / relevé absent ou périmé")
            +"\n3 · Passerelle et destination : NON TESTÉES"
            +"\n4 · DNS et connexion TCP : NON EXÉCUTÉS"
            +"\n5 · Certificat HTTPS : NON EXÉCUTÉ ; nécessite DNS et TCP réussis"
            +"\n\nUne route ou un handshake ne prouve pas l’accès LAN. Le VPN personnel ne prouve pas la couverture du profil professionnel. Aucun paquet de test envoyé."
            +"\n\nDurée de collecte : "+next.durationMillis+" ms · sockets visibles : "+next.connections.size()+" (inventaire partiel)");
    }
    private void drawTraffic() {
        if(chart==null || rates==null) return;
        List<InspectorHistory.Point> points=history.points(selected,window); chart.points=points; chart.window=window; chart.invalidate();
        InspectorHistory.Point latest=points.isEmpty()?null:points.get(points.size()-1);
        long rx=latest==null?-1:latest.rx, tx=latest==null?-1:latest.tx;
        rxValue.setText(rx<0?"—":InspectorHistory.rate(rx));txValue.setText(tx<0?"—":InspectorHistory.rate(tx));
        String role=sample==null?"":sample.types.getOrDefault(selected,selected);if(role.startsWith("Mobile"))role="Réseau mobile";if(role.startsWith("VPN"))role="Interface VPN";
        rates.setText(sample==null?"Observation en pause":rx<0&&tx<0?"Mesure en cours ou indisponible · "+role:"Sur "+role+" · débit global, pas vitesse maximale");
        chart.setContentDescription("Réception "+InspectorHistory.rate(rx)+", envoi "+InspectorHistory.rate(tx)+". Les interruptions ne sont pas des zéros.");
    }
    private void renderNetworks() {
        if(networkRows==null) return; networkRows.removeAllViews();
        if(sample==null) { label(networkRows,"Aucune observation courante",15,muted); return; }
        String query=search.getText().toString().toLowerCase(Locale.ROOT); int filter=filters.getSelectedItemPosition(); int count=0;
        for(Map.Entry<String,String> e:sample.interfaceDetails.entrySet()) {
            String role=sample.types.getOrDefault(e.getKey(),"Rôle inconnu");
            if(filter==1&&!role.startsWith("VPN") || filter==2&&!role.startsWith("Wi-Fi") || filter==3&&!role.startsWith("Mobile")) continue;
            if(!(e.getKey()+" "+e.getValue()).toLowerCase(Locale.ROOT).contains(query)) continue;
            count++; LinearLayout row=card(networkRows,e.getKey()+" · "+role);
            TextView details=label(row,e.getValue()+"\nSource : "+sample.source+"\nDate : "+new Date(sample.at)+"\nRoute déclarée ≠ trajet démontré",14,muted); details.setVisibility(expanded.contains(e.getKey()) ? VISIBLE : GONE);
            Button expand=button(row,expanded.contains(e.getKey()) ? "Masquer les détails" : "Afficher les détails",()->details.setVisibility(details.getVisibility()==VISIBLE?GONE:VISIBLE));
            expand.setOnClickListener(v->{ boolean open=details.getVisibility()!=VISIBLE; if(open) expanded.add(e.getKey()); else expanded.remove(e.getKey()); details.setVisibility(open?VISIBLE:GONE); expand.setText(open?"Masquer les détails":"Afficher les détails"); });
        }
        if(count==0) label(networkRows,"Aucun réseau correspondant · aucune conclusion sur sa joignabilité",15,muted);
    }
    private void detail(String title,String body) { new AlertDialog.Builder(activity).setTitle(title).setMessage(body).setPositiveButton("Fermer",null).show(); }
    private void export() {
        DiagnosticSequence.Result active=diagnosticPanel.latest();
        if(sample==null && active==null) { detail("Export indisponible","Démarrer une collecte ou un diagnostic pour créer un bilan daté."); return; }
        try {
            org.json.JSONObject json=new org.json.JSONObject(); json.put("schema",1); json.put("kind","OBSERVATION_PARTIELLE_ANONYMISEE"); json.put("at",sample==null?active.at:sample.at);
            json.put("interfaces",sample==null?org.json.JSONObject.NULL:sample.interfaceDetails.size()); json.put("visibleSockets",sample==null?org.json.JSONObject.NULL:sample.connections.size()); json.put("declaredRoutes",sample==null?org.json.JSONObject.NULL:sample.routeClaims.size());
            json.put("collectionMillis",sample==null?org.json.JSONObject.NULL:sample.durationMillis); json.put("pathVerified",false);
            org.json.JSONArray tests=new org.json.JSONArray(); if(active!=null) for(DiagnosticSequence.Step step:active.steps) { org.json.JSONObject item=new org.json.JSONObject(); item.put("stage",step.name);item.put("status",step.status.name());item.put("millis",step.millis);tests.put(item); } json.put("activeTests",tests); if(active!=null)json.put("diagnosticAt",active.at);
            Intent intent=new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT,json.toString(2));
            activity.startActivity(Intent.createChooser(intent,"Partager le bilan anonymisé"));
        } catch(org.json.JSONException | ActivityNotFoundException e) { detail("Export indisponible","Aucune application de partage disponible ou bilan non sérialisable."); }
    }
    void clear() {
        physicalRates.clear();
        sample=null; history.clear(); expanded.clear(); selected=""; diagnosticPanel.stop(true);
        collecting=false;pauseButton.setText("Reprendre");stamp.setText("Observation en pause");transport.setText("Observation en pause");internetHint.setText("Reprenez pour voir votre connexion.");wireguard.setText("État inconnu");vpnHint.setText("Pas d’observation courante");accessIcon.color(muted);internetIcon.color(muted);updateLastTest();
        interfaces.setAdapter(adapter(Collections.singletonList("Aucun compteur disponible"))); drawTraffic(); renderNetworks(); steps.setText("Aucune observation courante. Tests actifs : NON EXÉCUTÉS.");
    }
    void collectorActions(Runnable resume,Runnable pause){resumeAction=resume;pauseAction=pause;}
    void collecting(){collecting=true;pauseButton.setText("Pause");stamp.setText("● Lecture en cours…");}
    private void updateLastTest(){if(destination==null||route==null||diagnosticPanel==null)return;DiagnosticSequence.Result r=diagnosticPanel.latest();destination.setText(InspectorMeaning.verdict(r));route.setText(r==null?"Toucher pour tester":r.target+" · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.FRANCE).format(new Date(r.at)));}
    private InspectorVisuals.Icon diagramNode(LinearLayout parent,String kind,String title,int color){LinearLayout item=new LinearLayout(activity);item.setGravity(Gravity.CENTER);item.setOrientation(VERTICAL);parent.addView(item,new LinearLayout.LayoutParams(0,-2,1));InspectorVisuals.Icon icon=new InspectorVisuals.Icon(activity,kind,color);item.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));TextView label=label(item,title,11,muted);label.setGravity(Gravity.CENTER);return icon;}
    private void connector(LinearLayout parent){TextView t=new TextView(activity);t.setText("···");t.setTextColor(muted);t.setTextSize(20);parent.addView(t,new LinearLayout.LayoutParams(dp(24),-2));}
    void destroy(){diagnosticPanel.destroy();}
    private static int blend(int a,int b){return Color.rgb((Color.red(a)+Color.red(b)*5)/6,(Color.green(a)+Color.green(b)*5)/6,(Color.blue(a)+Color.blue(b)*5)/6);}
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private final class Chart extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        List<InspectorHistory.Point> points=Collections.emptyList(); long window=60000;
        Chart(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float left=dp(4), top=dp(28), width=getWidth()-dp(8), height=getHeight()-dp(48);
            long maximum=1; for(InspectorHistory.Point p:points) maximum=Math.max(maximum,Math.max(p.rx,p.tx));
            paint.setStyle(Paint.Style.FILL); paint.setColor(muted); paint.setTextSize(dp(10)); canvas.drawText("",left,dp(16),paint);
            paint.setStrokeWidth(dp(1)); for(int i=0;i<3;i++) canvas.drawLine(left,top+height*i/2,left+width,top+height*i/2,paint);
            if(!points.isEmpty()) for(int series=0;series<2;series++) {
                paint.setColor(series==0?green:blue); paint.setStrokeWidth(dp(2)); InspectorHistory.Point previous=null;
                long end=points.get(points.size()-1).elapsed;
                for(InspectorHistory.Point p:points) {
                    long value=series==0?p.rx:p.tx;
                    if(value<0) { previous=null; continue; }
                    float x=left+width*(1-(end-p.elapsed)/(float)window), y=top+height*(1-value/(float)maximum);
                    canvas.drawCircle(x,y,dp(2),paint);
                    if(previous!=null && p.elapsed-previous.elapsed<=15000) {
                        long old=series==0?previous.rx:previous.tx;
                        canvas.drawLine(left+width*(1-(end-previous.elapsed)/(float)window),top+height*(1-old/(float)maximum),x,y,paint);
                    } previous=p;
                }
            }
            paint.setColor(muted); canvas.drawText("−"+window/60000+" min",left,getHeight()-dp(5),paint); String end="maintenant"; canvas.drawText(end,getWidth()-paint.measureText(end)-dp(4),getHeight()-dp(5),paint);
        }
    }
}
