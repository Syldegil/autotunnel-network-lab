package io.github.syldegil.autotunnel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.wireguard.config.Config;
import java.io.*;

public final class MainActivity extends Activity {
    private static final int IMPORT = 10, VPN = 11;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, profile, tunnel;
    private Button activate, importButton;
    private final Runnable refresh = new Runnable() {
        public void run() {
            boolean active = AutoService.prefs(MainActivity.this).getBoolean("enabled", false);
            state.setText(AutoService.running ? "En marche" : "En pause");
            state.setContentDescription(AutoService.running ? AutoService.status : "Automatisation arrêtée");
            activate.setText(active && AutoService.running ? "Mettre en pause" : "Activer");
            tunnel.setText(NetworkWatchService.wireguardState().replace("Tunnel AutoTunnel non observé actif","Tunnel non observé"));
            importButton.setEnabled(!active && !AutoService.running);
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        AppStyle.theme(this); super.onCreate(saved);
        try { NetworkWatchService.autoStart(this); } catch (RuntimeException ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(AppStyle.bg(this));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(16),dp(16),dp(24));
        scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout heading=DashboardUi.row(root);
        TextView title=DashboardUi.text(heading,"AutoTunnel",32,true);if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button info=DashboardUi.action(heading,"ⓘ",()->DashboardUi.info(this,"À propos du fonctionnement","En Wi-Fi, Internet reste direct. Hors Wi-Fi, l’automatisation utilise votre profil WireGuard. Aucun coupe-circuit n’est garanti. Les parcours sont des brouillons non appliqués.\n\nAutorisez les notifications et une batterie sans restriction si Android arrête l’app. Un seul VPN Android peut fonctionner à la fois.\n\nLe profil reste chiffré sur ce téléphone. Le suivi IP interroge ipify ; le journal reste privé."));info.setContentDescription("Informations et fonctionnement");info.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout control=DashboardUi.card(root);
        LinearLayout controlTitle=DashboardUi.row(control);controlTitle.addView(new InspectorVisuals.Icon(this,"shield",AppStyle.green(this)),new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout controlCopy=DashboardUi.column(this);controlCopy.setPadding(dp(12),0,0,0);controlTitle.addView(controlCopy,new LinearLayout.LayoutParams(0,-2,1));DashboardUi.text(controlCopy,"PILOTAGE AUTO",12,false);
        state=DashboardUi.text(controlCopy,"En pause",23,true);
        LinearLayout rule=DashboardUi.row(control);rule.setPadding(0,dp(16),0,dp(16));
        TextView wifi=DashboardUi.text(rule,"Wi-Fi\nAccès direct",14,false);wifi.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        DashboardUi.text(rule,"→",22,false).setPadding(dp(8),0,dp(8),0);
        TextView mobile=DashboardUi.text(rule,"Hors Wi-Fi\nWireGuard",14,false);mobile.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        activate=DashboardUi.action(control,"Activer",()->toggleAutomation());DashboardUi.primary(activate);
        tunnel=DashboardUi.text(control,"Tunnel non observé",13,false);
        DashboardUi.text(control,"Sans coupe-circuit",12,false).setPadding(0,dp(8),0,0);
        LinearLayout shortcuts=DashboardUi.row(root);
        DashboardUi.tile(shortcuts,"pulse","Débits","Observer le réseau",()->startActivity(new Intent(this,InspectorActivity.class)));
        DashboardUi.tile(shortcuts,"graph","Parcours","Brouillons",()->startActivity(new Intent(this,RoutesActivity.class)));
        root.addView(new NetworkSummaryView(this));
        LinearLayout setup=DashboardUi.card(root);
        profile=DashboardUi.text(setup,Vault.file(this).exists()?"Profil enregistré":"Aucun profil",18,true);
        importButton=DashboardUi.action(setup,"Importer .conf",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,IMPORT);});
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
    }
    private void toggleAutomation(){
        if(AutoService.running){AutoService.prefs(this).edit().putBoolean("enabled",false).apply();stopService(new Intent(this,AutoService.class));return;}
        if(!Vault.file(this).exists()){message("Importez d’abord votre fichier WireGuard .conf.");return;}
        new AlertDialog.Builder(this).setTitle("Activer l’automatisation ?").setMessage("Wi-Fi : accès direct. Hors Wi-Fi : WireGuard.\n\nSans coupe-circuit garanti. Les brouillons ne sont pas appliqués. Le VPN Android actuel peut être remplacé et l’accès au réseau domestique interrompu.").setNegativeButton("Annuler",null).setPositiveButton("Activer",(d,w)->{Intent consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,VPN);else enable();}).show();
    }
    private void enable() {
        AutoService.prefs(this).edit().putBoolean("enabled",true).apply();
        try { AutoService.start(this); }
        catch (RuntimeException e) { AutoService.prefs(this).edit().putBoolean("enabled",false).apply(); message("Android a refusé le démarrage. Réessayez depuis cette page."); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == VPN) { if (result == RESULT_OK) enable(); else message("Autorisation VPN refusée. L’automatisation reste arrêtée."); }
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream input = getContentResolver().openInputStream(data.getData()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException();
            byte[] buffer = new byte[4096]; int n;
            while ((n=input.read(buffer))!=-1) { if (output.size()+n > 65536) throw new IOException(); output.write(buffer,0,n); }
            Config config = Config.parse(new ByteArrayInputStream(output.toByteArray()));
            if (config.getPeers().isEmpty()) { message("Le profil doit contenir au moins un pair WireGuard."); return; }
            Vault.save(this,config); profile.setText("Profil WireGuard enregistré");
            message("Profil importé. Vous pouvez activer l’automatisation.");
        } catch (Exception e) { message("Import impossible : choisissez un fichier WireGuard .conf valide (64 Ko maximum). Le profil précédent est conservé."); }
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent,String content,int size,String color,boolean bold) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size); view.setTextColor(bold ? AppStyle.green(this) : AppStyle.ink(this));
        if (bold) view.setTypeface(null,Typeface.BOLD); view.setPadding(0,dp(7),0,dp(7)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent,String label,View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); AppStyle.button(button); button.setOnClickListener(listener);
        parent.addView(button,new LinearLayout.LayoutParams(-1,-2)); return button;
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK",null).show(); }
}
