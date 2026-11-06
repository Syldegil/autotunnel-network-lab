package io.github.syldegil.autotunnel;
import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import java.io.*;
/** Interaction/rendering recipe; never activates automation or modifies drafts. */
public final class VisualDashboardInstrumentation extends Instrumentation {
 private Bundle args;public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 public void onStart(){Bundle out=new Bundle();Activity a=null;try{
 if(!"VISUAL-DASHBOARD-20261028".equals(args.getString("consent"))||getTargetContext().getPackageManager().getPackageInfo("io.github.syldegil.autotunnel",0).getLongVersionCode()!=15)throw new AssertionError("Wrong consent/version");
 a=open(MainActivity.class);Thread.sleep(1500);capture(a,"home");
 if(!visible(a.getWindow().getDecorView(),"PILOTAGE AUTO")||!visible(a.getWindow().getDecorView(),"Parcours"))throw new AssertionError("Missing home actions");
 if(!AutoService.running&&Vault.file(getTargetContext()).exists()){press(a,"Activer");Thread.sleep(300);getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitForIdleSync();if(AutoService.running)throw new AssertionError("Cancelled confirmation started VPN");}
 Activity home=a;runOnMainSync(()->((ScrollView)find(home.getWindow().getDecorView(),ScrollView.class)).fullScroll(View.FOCUS_DOWN));waitForIdleSync();capture(a,"home-addresses");close(a);
 a=open(InspectorActivity.class);Thread.sleep(6500);capture(a,"traffic");
 InspectorDashboard dash=(InspectorDashboard)find(a.getWindow().getDecorView(),InspectorDashboard.class);java.lang.reflect.Field current=InspectorActivity.class.getDeclaredField("current");current.setAccessible(true);if(current.get(a)==null)throw new AssertionError("No collector sample");
 press(a,"Pause");if(current.get(a)!=null)throw new AssertionError("Pause failed");press(a,"Reprendre");Thread.sleep(3500);if(current.get(a)==null)throw new AssertionError("Resume failed");
 press(a,"Adresses");capture(a,"addresses");if(find(dash,NetworkSummaryView.class)==null)throw new AssertionError("Address view missing");
 press(a,"Tester");capture(a,"test");if(!visible(dash,"Site ou serveur"))throw new AssertionError("Test page missing");
 press(a,"Débits");close(a);if(current.get(a)!=null)throw new AssertionError("Collector survived pause");
 a=open(RoutesActivity.class);Thread.sleep(500);capture(a,"graph");press(a,"⋯ Outils");Thread.sleep(300);getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitForIdleSync();close(a);
 a=open(MainActivity.class);
 if("true".equals(args.getString("restoreAutomation"))){if(!AutoService.prefs(getTargetContext()).getBoolean("enabled",false))throw new AssertionError("Previous preference missing");Activity active=a;runOnMainSync(()->AutoService.start(active));Thread.sleep(4500);if(!AutoService.running)throw new AssertionError("Automation not restored");}
 capture(a,"home-final");close(a);
 out.putString("stream","PASS code15: dashboard, cancel activation, full address page, traffic pause/resume, tabs, test page, graph/tools, lifecycle. No draft mutation; automation restoration only when explicitly requested.\n");finish(Activity.RESULT_OK,out);
 }catch(Throwable e){if(a!=null)close(a);out.putString("stream","FAIL "+e);finish(Activity.RESULT_CANCELED,out);}}
 Activity open(Class<?> type){Activity a=startActivitySync(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();return a;}
 void close(Activity a){runOnMainSync(a::finish);waitForIdleSync();}
 View find(View v,Class<?> type){if(type.isInstance(v))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=find(((ViewGroup)v).getChildAt(i),type);if(f!=null)return f;}return null;}
 View text(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=text(((ViewGroup)v).getChildAt(i),label);if(f!=null)return f;}return null;}
 boolean visible(View v,String label){if(v.getVisibility()!=View.VISIBLE)return false;if(v instanceof TextView&&((TextView)v).getText().toString().contains(label))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(visible(((ViewGroup)v).getChildAt(i),label))return true;return false;}
 void press(Activity a,String label){runOnMainSync(()->{View v=text(a.getWindow().getDecorView(),label);if(v==null||!v.performClick())throw new AssertionError("Missing "+label);});waitForIdleSync();}
 void capture(Activity a,String name){runOnMainSync(()->{try{View v=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File d=new File(getTargetContext().getCacheDir(),"visual-dashboard");d.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(d,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}catch(Exception e){throw new RuntimeException(e);}});}
}
