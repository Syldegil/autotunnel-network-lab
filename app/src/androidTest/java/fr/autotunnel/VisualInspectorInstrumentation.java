package io.github.syldegil.autotunnel;
import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Visual and interaction recipe for emulator or explicitly authorized Phone3, no profile mutation. */
public final class VisualInspectorInstrumentation extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    public void onStart(){Bundle out=new Bundle();Activity a=null;
        try{
            boolean allowed=Build.FINGERPRINT.contains("generic")||Build.FINGERPRINT.contains("sdk_")||("A024".equals(Build.MODEL)&&"INSPECTOR-VISUAL-20261026".equals(args.getString("consent")));
            if(!allowed||getTargetContext().getPackageManager().getPackageInfo("io.github.syldegil.autotunnel",0).getLongVersionCode()!=13)throw new AssertionError("Wrong device or version");
            a=startActivitySync(new Intent(getTargetContext(),InspectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();Thread.sleep(3600);final Activity activity=a;
            InspectorDashboard dashboard=(InspectorDashboard)find(a.getWindow().getDecorView(),InspectorDashboard.class);if(dashboard==null)throw new AssertionError("Dashboard missing");
            java.lang.reflect.Field current=InspectorActivity.class.getDeclaredField("current");current.setAccessible(true);
            if(current.get(a)==null)throw new AssertionError("Public observation did not start automatically");
            if(!visibleText(dashboard,"Connecté")&&!visibleText(dashboard,"Réseau")&&!visibleText(dashboard,"Aucun réseau actif"))throw new AssertionError("Connection summary missing");
            if(visibleText(a.getWindow().getDecorView(),"Qualification matérielle"))throw new AssertionError("Advanced tools visible by default");
            capture(a,"overview");
            press(a,"Pause");if(current.get(a)!=null)throw new AssertionError("Pause kept sample");capture(a,"paused");
            press(a,"Reprendre");Thread.sleep(500);if(current.get(a)==null)throw new AssertionError("Resume missing");
            press(a,"Réseaux");EditText search=(EditText)find(dashboard,EditText.class);runOnMainSync(()->search.setText("missing-interface-visual-test"));waitForIdleSync();if(!visibleText(dashboard,"Aucun réseau correspondant"))throw new AssertionError("Search empty state");runOnMainSync(()->search.setText(""));capture(a,"networks");
            press(a,"Tester");capture(a,"test-ready");
            DiagnosticPanel panel=(DiagnosticPanel)find(dashboard,DiagnosticPanel.class);EditText host=(EditText)find(panel,EditText.class);
            if(args!=null&&"true".equals(args.getString("networkTest"))){
                runOnMainSync(()->host.setText("example.com"));press(a,"Tester la connexion");long end=SystemClock.elapsedRealtime()+18000;
                while(SystemClock.elapsedRealtime()<end){boolean[] done={false};runOnMainSync(()->done[0]=((Button)text(panel,"Tester la connexion")).isEnabled());if(done[0])break;Thread.sleep(100);}
                DiagnosticSequence.Result result=panel.latest();if(result==null||result.steps.get(2).status!=DiagnosticSequence.Status.PASS)throw new AssertionError("Expected real successful TLS result");
                if(!visibleText(panel,"Connexion chiffrée vérifiée")||!visibleText(panel,"Certificat valide"))throw new AssertionError("Human summary missing");
                if(visibleText(panel,"Origine : API"))throw new AssertionError("Raw report exposed by default");
                capture(a,"test-result");press(a,"Détails du test");if(!visibleText(panel,"Origine : API"))throw new AssertionError("Technical detail inaccessible");press(a,"Détails du test");
                press(a,"Essentiel");if(!visibleText(dashboard,"Connexion chiffrée vérifiée"))throw new AssertionError("Last test absent from overview");capture(a,"overview-tested");out.putString("real_dns_tcp_tls","PASS and summarised on overview");
            }
            press(a,"Options avancées");if(!visibleText(a.getWindow().getDecorView(),"Qualification matérielle"))throw new AssertionError("Advanced tools inaccessible");press(a,"Options avancées");
            java.util.concurrent.CountDownLatch paused=new java.util.concurrent.CountDownLatch(1);
            Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
                public void onActivityPaused(Activity value){if(value==activity)paused.countDown();}
                public void onActivityCreated(Activity a,Bundle b){} public void onActivityStarted(Activity a){} public void onActivityResumed(Activity a){} public void onActivityStopped(Activity a){} public void onActivitySaveInstanceState(Activity a,Bundle b){} public void onActivityDestroyed(Activity a){}
            };
            runOnMainSync(()->{activity.getApplication().registerActivityLifecycleCallbacks(observer);activity.finish();});
            if(!paused.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Activity pause not delivered");
            runOnMainSync(()->activity.getApplication().unregisterActivityLifecycleCallbacks(observer));waitForIdleSync();
            if(current.get(a)!=null||panel.latest()!=null)throw new AssertionError("Observations survived observed onPause");
            out.putString("stream","PASS code13 auto-observe, graphical essential view, pause/resume, filters, progressive disclosure, diagnostic summary, onPause\n");finish(Activity.RESULT_OK,out);
        }catch(Throwable e){if(a!=null){Activity old=a;runOnMainSync(old::finish);}out.putString("stream","FAIL "+e);finish(Activity.RESULT_CANCELED,out);}
    }
    private View find(View v,Class<?> type){if(type.isInstance(v))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=find(((ViewGroup)v).getChildAt(i),type);if(f!=null)return f;}return null;}
    private View text(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=text(((ViewGroup)v).getChildAt(i),label);if(f!=null)return f;}return null;}
    private boolean visibleText(View v,String value){if(v.getVisibility()!=View.VISIBLE)return false;if(v instanceof TextView&&((TextView)v).getText().toString().contains(value))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(visibleText(((ViewGroup)v).getChildAt(i),value))return true;return false;}
    private void press(Activity a,String label){runOnMainSync(()->{View v=text(a.getWindow().getDecorView(),label);if(v==null||!v.performClick())throw new AssertionError("Missing control: "+label);});waitForIdleSync();}
    private void capture(Activity a,String name){runOnMainSync(()->{try{View v=a.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));File d=new File(getTargetContext().getCacheDir(),"inspector-visual-evidence");d.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(d,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}bitmap.recycle();}catch(IOException e){throw new RuntimeException(e);}});}
}
