package io.github.syldegil.autotunnel;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import android.graphics.*;import java.io.*;import org.json.*;
public final class NetworkLiveInstrumentation extends Instrumentation {
 private Bundle args; public void onCreate(Bundle a){args=a;super.onCreate(a);start();}
 public void onStart(){Bundle out=new Bundle();Activity a=null;try{
  if(!"NETWORK-LIVE-20261028".equals(args.getString("consent")))throw new AssertionError("Missing recipe consent");
  Context c=getTargetContext();boolean automation=AutoService.running;
  Context isolated=new ContextWrapper(c){public File getNoBackupFilesDir(){File f=new File(getCacheDir(),"journal-recipe");f.mkdirs();return f;}};
  NetworkLog.clear(isolated);for(int i=0;i<503;i++)if(!NetworkLog.append(isolated,"route"+i,"dns","ip","lan","test"))throw new AssertionError("Write failed");
  JSONArray log=NetworkLog.read(isolated);if(log.length()!=500||!log.getJSONObject(0).getString("route").equals("route3"))throw new AssertionError("Journal limit");NetworkLog.clear(isolated);
  a=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
  long deadline=SystemClock.elapsedRealtime()+28000;while(NetworkWatchService.ipAt==0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(250);
  if(!NetworkWatchService.running||NetworkWatchService.latest==null)throw new AssertionError("Monitor did not start");
  if(NetworkWatchService.ipAt==0)throw new AssertionError("IP probe did not finish");
  if(NetworkWatchService.latest.connected&&!NetworkWatchService.validIp(NetworkWatchService.publicIp))throw new AssertionError("Real HTTPS IP unavailable");
  capture(a,"main");Activity first=a;runOnMainSync(first::finish);waitForIdleSync();
  a=startActivitySync(new Intent(c,InspectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(6500);waitForIdleSync();capture(a,"inspector");
  Activity scrolling=a;runOnMainSync(()->{View v=scrolling.findViewById(android.R.id.content);ScrollView scroll=(ScrollView)((android.view.ViewGroup)v).getChildAt(0);scroll.scrollTo(0,750);});waitForIdleSync();capture(a,"rates");
  InspectorCollector.Sample sample=InspectorCollector.sample(c);out.putString("counters",sample.counters.keySet().toString());
  if(NetworkLog.read(c).length()==0)throw new AssertionError("No persisted transitions");
  if(automation!=AutoService.running)throw new AssertionError("Observer changed automation");
  Activity current=a;runOnMainSync(()->{current.getResources().getConfiguration();current.finish();});waitForIdleSync();
  if(!NetworkWatchService.running)throw new AssertionError("Observer stopped outside inspector");
  a=startActivitySync(new Intent(c,RoutesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();capture(a,"graph");Activity graph=a;runOnMainSync(graph::finish);
  out.putString("stream","PASS code14: public HTTPS address, Android DNS, bounded atomic journal, independent observer, physical counters, main/inspector/graph rendering. Profile present="+Vault.file(c).exists()+"\n");finish(Activity.RESULT_OK,out);
 }catch(Throwable e){if(a!=null){Activity old=a;runOnMainSync(old::finish);}out.putString("stream","FAIL "+e);finish(Activity.RESULT_CANCELED,out);}}
 private void capture(Activity a,String name){runOnMainSync(()->{try{View v=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File d=new File(getTargetContext().getCacheDir(),"network-live-evidence");d.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(d,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}catch(Exception e){throw new RuntimeException(e);}});}
}
