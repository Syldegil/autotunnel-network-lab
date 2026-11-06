package io.github.syldegil.autotunnel;

import android.app.*;
import android.widget.*;
import java.util.*;

/** Executable transaction simulator backed by the same NetworkEngine; never a privileged client. */
final class TopologyPreview {
    static AlertDialog create(Activity activity,RoutingTopology topology){
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);
        ScrollView scroll=new ScrollView(activity);scroll.addView(root);TextView report=new TextView(activity);root.addView(report);
        SimulatedNetworkBackend backend=new SimulatedNetworkBackend(System.currentTimeMillis());NetworkEngine engine=new NetworkEngine(backend,new NetworkEngine.MemoryJournal());
        try{
            NetworkPlan plan=engine.preview(topology);
            StringBuilder qualification=new StringBuilder("\nQualification réelle : INCONNUE pour chaque branche. IPv6 simulé n’est pas IPv6 transporté.\n");
            qualification.append(new CapabilityMatrix().requirements(topology)).append('\n');
            report.setText(topology.report()+qualification+"\n"+plan.preview());
            TextView state=new TextView(activity);root.addView(state);
            add(root,activity,"Simuler la transaction",()->new AlertDialog.Builder(activity).setMessage("Appliquer ce plan uniquement au simulateur mémoire ? Aucune commande Android.").setNegativeButton("Annuler",null).setPositiveButton("Simuler",(d,w)->state.setText(engine.apply(plan,plan.digest,System.currentTimeMillis())+"\n"+engine.error())).show());
            add(root,activity,"Simuler panne du tunnel",()->{backend.proof=false;state.setText(engine.event(NetworkEngine.Event.TUNNEL_LOST,System.currentTimeMillis())+"\n"+engine.branchStates()+"\n"+engine.error());});
            add(root,activity,"Restaurer le simulateur",()->state.setText(engine.restore(true)+"\n"+engine.error()));
            Button locked=new Button(activity);locked.setText("Activation Android bloquée · capacités non qualifiées");locked.setEnabled(false);root.addView(locked);
        }catch(Exception e){report.setText("Prévisualisation refusée avant mutation\n"+e.getMessage());}
        return new AlertDialog.Builder(activity).setTitle("Graphe → branches → moteur").setView(scroll).setPositiveButton("Fermer",null).create();
    }
    private static void add(LinearLayout root,Activity a,String label,Runnable action){Button b=new Button(a);b.setText(label);b.setOnClickListener(v->action.run());root.addView(b);}
}
