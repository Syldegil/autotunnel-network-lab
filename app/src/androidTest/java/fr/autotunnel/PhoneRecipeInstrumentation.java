package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Signed test APK only. No-restart attachment has a separate classloader: reflect app-private views. */
public final class PhoneRecipeInstrumentation extends Instrumentation {
    private Bundle args; private final Bundle results=new Bundle(); private int failures;
    interface Check {void run() throws Exception;}
    @Override public void onCreate(Bundle args){this.args=args;start();}
    private void check(String name,Check action){try{action.run();results.putString(name,"PASS");}catch(Throwable e){failures++;results.putString(name,"FAIL "+e);}}
    @Override public void onStart(){
        GraphModel baseline=null;
        try{
            if(!"A024".equals(Build.MODEL)||!"Nothing".equalsIgnoreCase(Build.MANUFACTURER)||args==null
                ||!"DS016-code6-authorized".equals(args.getString("consent"))
                ||!Integer.toString(android.os.Process.myPid()).equals(args.getString("pid"))
                ||getTargetContext().getPackageManager().getPackageInfo("io.github.syldegil.autotunnel",0).getLongVersionCode()!=6)
                throw new AssertionError("Expected Phone3/code6/unchanged PID/explicit consent");
            results.putString("no_restart_pid",Integer.toString(android.os.Process.myPid()));
            if("inspect".equals(args.getString("mode"))){
                ClassLoader loader=getTargetContext().getClassLoader();Class<?> collector=Class.forName("io.github.syldegil.autotunnel.InspectorCollector",true,loader);
                java.lang.reflect.Method sampleMethod=collector.getDeclaredMethod("sample",Context.class);sampleMethod.setAccessible(true);Object sample=sampleMethod.invoke(null,getTargetContext());
                results.putString("public_counter_sources",field(sample,"counterSources").toString());results.putString("public_availability",field(sample,"availability").toString());
                Class<?> client=Class.forName("io.github.syldegil.autotunnel.RootClient",true,loader);Class<?> probeType=Class.forName("io.github.syldegil.autotunnel.NetworkDiscovery$Probe",true,loader);
                java.lang.reflect.Method qualify=client.getDeclaredMethod("qualify",probeType);qualify.setAccessible(true);
                for(String probe:new String[]{"LINK_COUNTERS","SOCKETS4","SOCKETS6"}){
                    Object value=Enum.valueOf((Class)probeType,probe);org.json.JSONObject reply=(org.json.JSONObject)qualify.invoke(null,value);
                    require(reply.getBoolean("ok")&&!reply.getBoolean("hardwareEnabled"),"root observation contract");
                    org.json.JSONObject inspection=reply.getJSONObject("inspection");results.putString("root_"+probe,inspection.toString());
                }
                results.putString("stream","DS016 root/public metadata under real Activity UID; no network mutation\n");
                finish(Activity.RESULT_OK,results);return;
            }
            Activity a=open();View canvas=find(a.getWindow().getDecorView(),"GraphCanvas");baseline=model(canvas);
            final GraphModel initial=GraphModel.initial(baseline.profile);reset(a,initial);
            check("render_dark",()->capture(a,"phone3-editor"));
            check("drag_undo_redo",()->{
                GraphModel b=model(canvas);GraphModel.Node n=b.node("n0");drag(canvas,b,n.x+90,n.y+24,n.x+130,n.y+49);
                GraphModel m=model(canvas);require(Math.abs(m.node("n0").x-n.x)>20,"node not moved");
                require(RoutePolicy.plan(b.profile).equals(RoutePolicy.plan(m.profile)),"position affected policy");capture(a,"phone3-moved");
                press(a,"Annuler");require(model(canvas).node("n0").x==n.x,"undo");press(a,"Rétablir");require(model(canvas).node("n0").x==m.node("n0").x,"redo");
            });
            check("palette_27_add_link_delete",()->{
                press(a,"+ Palette");AlertDialog d=dialog(a);require(d.getListView().getCount()==27,"palette count");capture(a,"phone3-palette");
                selectList(d,"DNS");GraphModel added=model(canvas);GraphModel.Node dns=null;for(GraphModel.Node n:added.nodes)if(n.kind==GraphModel.Kind.DNS)dns=n;
                require(dns!=null,"DNS not added");GraphModel.Node source=added.node("n0");
                PointF from=point(canvas,source,GraphModel.Port.OUT_IP),to=point(canvas,dns,GraphModel.Port.IN_IP);drag(canvas,added,from.x,from.y,to.x,to.y);
                GraphModel connected=model(canvas);require(connected.links.size()==added.links.size()+1,"port drag did not connect");
                String id=connected.links.get(connected.links.size()-1).id;invokeMain(canvas,"select",new Class[]{String.class},id);press(a,"Supprimer");positive("Supprimer");
                require(model(canvas).link(id)==null,"disconnect");invokeMain(canvas,"select",new Class[]{String.class},dns.id);press(a,"Supprimer");positive("Supprimer");require(model(canvas).node(dns.id)==null,"delete node");
            });
            reset(a,initial);
            check("properties",()->{
                invokeMain(canvas,"select",new Class[]{String.class},"n0");press(a,"Propriétés");AlertDialog d=dialog(a);EditText name=(EditText)find(d.getWindow().getDecorView(),"EditText");
                runOnMainSync(()->{name.setText("DS016 test");d.getButton(-1).performClick();});waitForIdleSync();require(model(canvas).node("n0").label.equals("DS016 test"),"property not saved");press(a,"Annuler");
            });
            check("zoom_pan_border",()->{
                float z=model(canvas).zoom;press(a,"+");press(a,"−");require(Math.abs(model(canvas).zoom-z)<.02,"zoom");
                GraphModel b=model(canvas);drag(canvas,b,130,300,160,340);require(model(canvas).panX!=b.panX&&model(canvas).node("n0").x==b.node("n0").x,"pan ambiguity");
                reset(a,initial.replace(initial.node("n0").move(2,40)));drag(canvas,model(canvas),20,65,45,85);require(model(canvas).node("n0").x>2,"edge drag");reset(a,initial);
                press(a,"Cadrer");capture(a,"phone3-overview");
            });
            check("simple_expert",()->{press(a,"Mode simplifié");press(a,"Mode expert");});
            check("physical_logical_live",()->{
                String[] labels={"Physique","Logique","Live"};GraphModel.ViewMode[] modes={GraphModel.ViewMode.PHYSICAL,GraphModel.ViewMode.LOGICAL,GraphModel.ViewMode.LIVE};
                for(int i=0;i<3;i++){press(a,"Vue");choose(labels[i]);require(model(canvas).view==modes[i],"view mismatch");}capture(a,"phone3-live");
            });
            GraphModel saved=model(canvas);runOnMainSync(a::finish);waitForIdleSync();Activity reopened=open();
            check("save_close_reopen",()->require(Arrays.equals(GraphCodec.encode(saved),GraphCodec.encode(model(find(reopened.getWindow().getDecorView(),"GraphCanvas")))),"layout not restored"));
            check("rotation",()->{
                Activity rotated=resumeAfter(()->reopened.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                capture(rotated,"phone3-landscape");require(Arrays.equals(GraphCodec.encode(saved),GraphCodec.encode(model(find(rotated.getWindow().getDecorView(),"GraphCanvas")))),"rotation data loss");
                runOnMainSync(()->rotated.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            });
            results.putString("TalkBack","NOT RUN");
        }catch(Throwable e){failures++;results.putString("fatal",e.toString());}
        finally{if(!"inspect".equals(args.getString("mode")))try{Activity a=open();if(baseline!=null)reset(a,baseline);capture(a,"phone3-restored");runOnMainSync(a::finish);waitForIdleSync();results.putString("restore_baseline","PASS");}catch(Throwable e){failures++;results.putString("restore_baseline","FAIL "+e);}}
        results.putString("stream","DS016 Phone3 recipe failures="+failures+"; no network mutation; PID preserved\n");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"ds016-recipe.txt"))){out.write(results.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException ignored){}
        finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,results);
    }
    private static void require(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    private View find(View v,String name){if(v.getClass().getSimpleName().equals(name))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),name);if(found!=null)return found;}return null;}
    private Object field(Object obj,String name)throws Exception{java.lang.reflect.Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private Object invoke(Object obj,String method,Class<?>[] types,Object...args)throws Exception{java.lang.reflect.Method m=obj.getClass().getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(obj,args);}
    private void invokeMain(Object obj,String name,Class<?>[] types,Object...args)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{invoke(obj,name,types,args);}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);waitForIdleSync();}
    private GraphModel model(View canvas)throws Exception{return(GraphModel)field(canvas,"graph");}
    private View editor(Activity a){return find(a.getWindow().getDecorView(),"GraphEditor");}
    private void reset(Activity a,GraphModel m)throws Exception{invokeMain(editor(a),"change",new Class[]{GraphModel.class},m);}
    private AlertDialog dialog(Activity a)throws Exception{return(AlertDialog)field(editor(a),"activeDialog");}
    private boolean click(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v.performClick();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(click(((ViewGroup)v).getChildAt(i),label))return true;return false;}
    private void press(Activity a,String label){boolean[] ok={false};runOnMainSync(()->ok[0]=click(a.getWindow().getDecorView(),label));require(ok[0],"control missing "+label);waitForIdleSync();}
    private PointF point(View canvas,GraphModel.Node n,GraphModel.Port p)throws Exception{return(PointF)invoke(canvas,"point",new Class[]{GraphModel.Node.class,GraphModel.Port.class},n,p);}
    private void drag(View canvas,GraphModel m,float x,float y,float endX,float endY)throws Exception{
        int[] at=new int[2];runOnMainSync(()->canvas.getLocationOnScreen(at));float d=canvas.getResources().getDisplayMetrics().density;long t=SystemClock.uptimeMillis();
        for(int i=0;i<=6;i++){float f=i/6f;MotionEvent e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),i==0?0:i==6?1:2,at[0]+d*(m.panX+(x+(endX-x)*f)*m.zoom),at[1]+d*(m.panY+(y+(endY-y)*f)*m.zoom),0);sendPointerSync(e);e.recycle();Thread.sleep(35);}waitForIdleSync();
    }
    private void selectList(AlertDialog dialog,String label){ListView list=dialog.getListView();int[] pos={-1};runOnMainSync(()->{for(int i=0;i<list.getCount();i++)if(list.getItemAtPosition(i).toString().equals(label))pos[0]=i;require(pos[0]>=0,"list label");list.setSelection(pos[0]);});waitForIdleSync();runOnMainSync(()->list.performItemClick(list.getChildAt(pos[0]-list.getFirstVisiblePosition()),pos[0],list.getItemIdAtPosition(pos[0])));waitForIdleSync();}
    private void choose(String label)throws Exception{getUiAutomation().waitForIdle(200,3000);android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();require(root!=null,"accessibility window");for(android.view.accessibility.AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label)){for(int i=0;i<3&&n!=null;i++,n=n.getParent())if(n.isClickable()&&n.performAction(16)){getUiAutomation().waitForIdle(200,3000);waitForIdleSync();return;}}throw new AssertionError("dialog choice "+label);}
    private void positive(String label)throws Exception{choose(label);}
    private Activity open(){return resumeAfter(()->getTargetContext().startActivity(new Intent().setClassName("io.github.syldegil.autotunnel","io.github.syldegil.autotunnel.RoutesActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)));}
    private Activity resumeAfter(Runnable action){
        Application app=(Application)getTargetContext().getApplicationContext();java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);Activity[] result={null};
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){public void onActivityResumed(Activity a){if(a.getClass().getName().equals("io.github.syldegil.autotunnel.RoutesActivity")){result[0]=a;latch.countDown();}}public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        app.registerActivityLifecycleCallbacks(callback);try{runOnMainSync(action);require(latch.await(15,java.util.concurrent.TimeUnit.SECONDS),"Activity resume timeout");waitForIdleSync();return result[0];}catch(InterruptedException e){throw new AssertionError(e);}finally{app.unregisterActivityLifecycleCallbacks(callback);}
    }
    private void capture(Activity a,String name)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{
        View root=a.getWindow().getDecorView();require(root.getWidth()>0,"layout missing");Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);root.draw(c);
        AlertDialog dialog=dialog(a);if(dialog!=null&&dialog.isShowing()){View decor=dialog.getWindow().getDecorView();int[] at=new int[2],origin=new int[2];decor.getLocationOnScreen(at);root.getLocationOnScreen(origin);c.drawColor(0x88000000);c.save();c.translate(at[0]-origin[0],at[1]-origin[1]);decor.draw(c);c.restore();}
        Paint p=new Paint();p.setColor(Color.rgb(15,23,42));c.drawRect(0,bitmap.getHeight()-64,bitmap.getWidth(),bitmap.getHeight(),p);p.setColor(Color.WHITE);p.setTextSize(24);c.drawText("PHONE3 REEL · CODE6 · DS016 · rendu des vues Android",12,bitmap.getHeight()-22,p);
        File dir=new File(getTargetContext().getCacheDir(),"ds016-phone-evidence");require(dir.isDirectory()||dir.mkdirs(),"capture directory");try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
}
