package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Explicitly authorized Phone3/emulator recipe; no root, profile edits or network-policy mutation. */
public final class ActiveInspectorInstrumentation extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    public void onStart(){Bundle result=new Bundle();Activity activity=null;
        try {
            boolean phone="A024".equals(Build.MODEL)&&"Nothing".equalsIgnoreCase(Build.MANUFACTURER);
            boolean emulator=Build.FINGERPRINT.contains("generic")||Build.FINGERPRINT.contains("sdk_");
            if((!phone&&!emulator)||args==null||!"INSPECTOR-ACTIVE-20261026".equals(args.getString("consent"))||getTargetContext().getPackageManager().getPackageInfo("io.github.syldegil.autotunnel",0).getLongVersionCode()!=12)throw new AssertionError("Expected authorized device and code12");
            activity=startActivitySync(new Intent(getTargetContext(),InspectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync(); final Activity a=activity;
            press(a,"Démarrer / reprendre les lectures publiques");Thread.sleep(3500);waitForIdleSync();
            InspectorDashboard dashboard=(InspectorDashboard)find(a.getWindow().getDecorView(),InspectorDashboard.class);
            if(dashboard==null)throw new AssertionError("Dashboard missing");
            java.lang.reflect.Field current=InspectorActivity.class.getDeclaredField("current");current.setAccessible(true);
            if(current.get(a)==null)throw new AssertionError("Public sample absent");
            result.putString("public_collector","PASS");
            press(a,"Réseaux et routes"); EditText search=(EditText)find(dashboard,EditText.class);runOnMainSync(()->search.setText("no-such-interface-autotunnel"));waitForIdleSync();
            if(!contains(dashboard,"Aucun réseau correspondant"))throw new AssertionError("Search empty state");runOnMainSync(()->search.setText(""));
            press(a,"Diagnostic");DiagnosticPanel panel=(DiagnosticPanel)find(dashboard,DiagnosticPanel.class);EditText host=(EditText)find(panel,EditText.class);
            runOnMainSync(()->host.setText("example.com"));press(a,"Tester DNS → TCP → TLS");waitDone(panel);
            DiagnosticSequence.Result success=panel.latest();if(success==null)throw new AssertionError("No diagnostic result");
            result.putString("https_example_com",statuses(success));
            for(DiagnosticSequence.Step step:success.steps)if(step.status!=DiagnosticSequence.Status.PASS)throw new AssertionError("Positive diagnostic: "+statuses(success));
            ScrollView scroll=(ScrollView)find(a.getWindow().getDecorView(),ScrollView.class);
            runOnMainSync(()->{android.graphics.Rect rect=new android.graphics.Rect();panel.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(panel,rect);scroll.scrollTo(0,rect.top);});waitForIdleSync();capture(a,"active-success");
            for(String kind:new String[]{"tcpClosed","tlsUntrusted"}) {
                String supplied=args.getString(kind);if(supplied==null)continue;
                java.net.Socket reserved=new java.net.Socket();
                final String target;
                if("tcpClosed".equals(kind)){reserved.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}),0));target="127.0.0.1:"+reserved.getLocalPort();}else target=supplied;
                runOnMainSync(()->host.setText(target));press(a,"Tester DNS → TCP → TLS");waitDone(panel);
                reserved.close();DiagnosticSequence.Result failed=panel.latest();result.putString(kind,statuses(failed));
                int expected="tcpClosed".equals(kind)?1:2;
                if(failed.steps.get(expected).status!=DiagnosticSequence.Status.FAIL)throw new AssertionError("Expected failure: "+kind+" "+statuses(failed));
                for(int i=0;i<expected;i++)if(failed.steps.get(i).status!=DiagnosticSequence.Status.PASS)throw new AssertionError("Fixture unreachable: "+kind);
                if(expected==1 && failed.steps.get(2).status!=DiagnosticSequence.Status.NOT_RUN)throw new AssertionError("TLS ran after failed TCP");
                if(expected==2 && !failed.steps.get(2).detail.contains("Certificat"))throw new AssertionError("Untrusted certificate not identified");
                capture(a,"active-"+kind);
            }
            runOnMainSync(()->host.setText("autotunnel-check.invalid"));press(a,"Tester DNS → TCP → TLS");waitDone(panel);
            DiagnosticSequence.Result negative=panel.latest();result.putString("dns_invalid",statuses(negative));
            if(negative.steps.get(0).status!=DiagnosticSequence.Status.FAIL||negative.steps.get(1).status!=DiagnosticSequence.Status.NOT_RUN||negative.steps.get(2).status!=DiagnosticSequence.Status.NOT_RUN)throw new AssertionError("DNS failure did not skip TCP/TLS");capture(a,"active-dns-failure");
            java.net.ServerSocket silent=new java.net.ServerSocket(0,2,java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}));
            List<java.net.Socket> peers=Collections.synchronizedList(new ArrayList<>());
            Thread acceptor=new Thread(()->{try{for(int i=0;i<2;i++)peers.add(silent.accept());}catch(IOException ignored){}});acceptor.setDaemon(true);acceptor.start();
            try {
                runOnMainSync(()->host.setText("127.0.0.1:"+silent.getLocalPort()));press(a,"Tester DNS → TCP → TLS");waitTlsRunning(panel);
                press(a,"Annuler le diagnostic");Thread.sleep(300);waitForIdleSync();if(panel.latest()!=null)throw new AssertionError("Cancelled result published");result.putString("cancel","PASS blocked TLS socket closed");
                press(a,"Tester DNS → TCP → TLS");waitTlsRunning(panel);runOnMainSync(a::finish);waitForIdleSync();Thread.sleep(300);
                if(current.get(a)!=null||panel.latest()!=null)throw new AssertionError("Data survived onPause");result.putString("onPause","PASS blocked TLS cancelled and observations cleared");
            } finally {silent.close();acceptor.join(1000);synchronized(peers){for(java.net.Socket peer:peers)peer.close();}}
            result.putString("stream","PASS code12 native UI; real public collector; actual DNS/TCP/verified TLS; DNS negative; cancel; onPause; no profile or network-policy changes\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage());if(activity!=null){Activity a=activity;runOnMainSync(a::finish);}finish(Activity.RESULT_CANCELED,result);}
    }
    private void waitTlsRunning(DiagnosticPanel p)throws Exception{long end=SystemClock.elapsedRealtime()+3000;while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};runOnMainSync(()->ready[0]=p.latest()!=null&&p.latest().steps.get(2).status==DiagnosticSequence.Status.RUNNING);if(ready[0])return;Thread.sleep(30);}throw new AssertionError("TLS blocking fixture did not start");}
    private void waitDone(DiagnosticPanel p)throws Exception{long end=SystemClock.elapsedRealtime()+18000;while(SystemClock.elapsedRealtime()<end){boolean[] done={false};runOnMainSync(()->done[0]=((Button)findText(p,"Tester DNS → TCP → TLS")).isEnabled());if(done[0])return;Thread.sleep(100);}throw new AssertionError("Diagnostic exceeded bounded wait");}
    private String statuses(DiagnosticSequence.Result r){if(r==null)return "NONE";StringBuilder out=new StringBuilder();for(DiagnosticSequence.Step s:r.steps)out.append(s.name).append('=').append(s.status).append('(').append(s.millis).append("ms) ");return out.toString();}
    private void press(Activity a,String label){runOnMainSync(()->{View v=findText(a.getWindow().getDecorView(),label);if(v==null||!v.performClick())throw new AssertionError("Missing control "+label);});waitForIdleSync();}
    private View findText(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findText(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    private View find(View v,Class<?> type){if(type.isInstance(v))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),type);if(found!=null)return found;}return null;}
    private boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),text))return true;return false;}
    private void capture(Activity a,String name){runOnMainSync(()->{try{View v=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File d=new File(getTargetContext().getCacheDir(),"inspector-active-evidence");d.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(d,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}catch(IOException e){throw new RuntimeException(e);}});}
}
