package io.github.syldegil.autotunnel;

import android.app.Activity;
import android.widget.*;
import java.util.concurrent.*;

/** Explicit foreground-only active diagnostics, separate from passive counters. */
final class DiagnosticPanel extends LinearLayout {
    private final Activity activity;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final EditText target;
    private final TextView output, verdict, guidance, testedTarget;
    private final InspectorVisuals.StepRow[] rows=new InspectorVisuals.StepRow[3];
    Runnable onChange;
    private final Button start;
    private DiagnosticSequence.Call call;
    private Future<?> task; private ScheduledFuture<?> deadline;
    private int generation; private DiagnosticSequence.Result latest;
    DiagnosticPanel(Activity activity,int ink,int muted) {
        super(activity);this.activity=activity;setOrientation(VERTICAL);
        TextView title=new TextView(activity);title.setText("Site ou serveur");title.setTextSize(19);title.setTextColor(ink);addView(title);
        TextView hint=new TextView(activity);hint.setText("Test DNS · connexion · certificat TLS");hint.setTextColor(muted);addView(hint);
        target=new EditText(activity);target.setSingleLine(true);target.setHint("example.org ou adresse:port");target.setContentDescription("Destination du diagnostic actif");target.setTextColor(ink);target.setHintTextColor(muted);target.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);addView(target);
        start=new Button(activity);start.setText("Tester la connexion");start.setAllCaps(false);start.setOnClickListener(v->begin());addView(start);
        Button cancel=new Button(activity);cancel.setText("Annuler le test");cancel.setAllCaps(false);cancel.setOnClickListener(v->stop(false));addView(cancel);
        verdict=InspectorVisuals.text(this,"Aucun serveur testé",23,ink);
        testedTarget=InspectorVisuals.text(this,"",13,muted);
        guidance=InspectorVisuals.text(this,"Lancez un test pour vérifier une destination précise.",14,muted);
        boolean dark=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int good=android.graphics.Color.parseColor(dark?"#77e0bd":"#086a50"),bad=android.graphics.Color.parseColor(dark?"#ffcf80":"#885500");
        start.setBackground(InspectorVisuals.shape(good,12,activity));start.setTextColor(dark?android.graphics.Color.parseColor("#0c121b"):android.graphics.Color.WHITE);
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,InspectorVisuals.dp(activity,48));actionParams.setMargins(0,InspectorVisuals.dp(activity,8),0,InspectorVisuals.dp(activity,8));start.setLayoutParams(actionParams);
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dark?android.graphics.Color.parseColor("#1a2736"):android.graphics.Color.parseColor("#edf2f7")));
        String[] titles={"1  TROUVER L’ADRESSE","2  JOINDRE LE SERVEUR","3  VÉRIFIER LE CHIFFREMENT"},icons={"globe","server","shield"};
        for(int i=0;i<3;i++){rows[i]=new InspectorVisuals.StepRow(activity,titles[i],icons[i],ink,muted,good,bad);addView(rows[i]);}
        Button details=new Button(activity);details.setText("Détails du test");details.setAllCaps(false);addView(details);
        output=new TextView(activity);output.setTextColor(ink);output.setTextSize(15);output.setText("Aucun diagnostic actif exécuté.");output.setVisibility(GONE);addView(output);details.setOnClickListener(v->output.setVisibility(output.getVisibility()==VISIBLE?GONE:VISIBLE));
        verdict.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }
    private void begin() {
        final DiagnosticTarget parsed;
        try{parsed=DiagnosticTarget.parse(target.getText().toString());}catch(IllegalArgumentException e){target.setError(e.getMessage());return;}
        stop(true);final int token=generation;
        final AndroidDiagnosticTransport transport=new AndroidDiagnosticTransport(activity.getApplicationContext());
        final DiagnosticSequence.Call active=new DiagnosticSequence.Call();call=active;start.setEnabled(false);target.setEnabled(false);
        output.setText("Diagnostic en cours vers "+parsed.label()+"\n"+transport.origin());
        deadline=deadlines.schedule(active::timeout,15,TimeUnit.SECONDS);
        task=worker.submit(()->{
            DiagnosticSequence.run(parsed,transport,active,result->activity.runOnUiThread(()->{if(generation==token){latest=result;output.setText(result.describe());present();}}));
            activity.runOnUiThread(()->{if(generation==token){if(deadline!=null)deadline.cancel(false);deadline=null;call=null;task=null;start.setEnabled(true);target.setEnabled(true);}});
        });
    }
    DiagnosticSequence.Result latest(){return latest;}
    void stop(boolean clear) {
        generation++;boolean running=call!=null;
        if(call!=null)call.close();if(task!=null)task.cancel(true);if(deadline!=null)deadline.cancel(false);
        call=null;task=null;deadline=null;start.setEnabled(true);target.setEnabled(true);
        if(clear||running){latest=null;output.setText(clear?"Aucun diagnostic actif courant.":"Diagnostic annulé. Aucun résultat final conservé.");output.setVisibility(GONE);present();if(!clear) {verdict.setText("Test interrompu");guidance.setText("Vous pouvez lancer un nouveau test.");}}
    }
    private void present(){verdict.setText(InspectorMeaning.verdict(latest));guidance.setText(InspectorMeaning.guidance(latest));testedTarget.setText(latest==null?"":latest.target+" · "+new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.FRANCE).format(new java.util.Date(latest.at)));for(int i=0;i<3;i++)rows[i].update(i,latest==null?null:latest.steps.get(i));if(onChange!=null)onChange.run();}
    void destroy(){stop(true);worker.shutdownNow();deadlines.shutdownNow();}
}
