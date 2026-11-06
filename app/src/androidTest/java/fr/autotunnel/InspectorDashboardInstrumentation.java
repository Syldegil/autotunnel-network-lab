package io.github.syldegil.autotunnel;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** UI recipe uses an isolated emulator and actual public observations only. */
public final class InspectorDashboardInstrumentation extends UiEvidenceInstrumentation {
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(!android.os.Build.FINGERPRINT.contains("generic")&&!android.os.Build.FINGERPRINT.contains("sdk_")) throw new AssertionError("Emulator only");
            Activity a=startActivitySync(new Intent(getTargetContext(),InspectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            press(a,"Démarrer / reprendre les lectures publiques"); Thread.sleep(6500); waitForIdleSync();
            InspectorDashboard dashboard=(InspectorDashboard)find(a.getWindow().getDecorView(),InspectorDashboard.class);
            if(dashboard==null) throw new AssertionError("Dashboard absent");
            java.lang.reflect.Field current=InspectorActivity.class.getDeclaredField("current"); current.setAccessible(true);
            if(current.get(a)==null) throw new AssertionError("No actual collector observation");
            ScrollView scroll=(ScrollView)find(a.getWindow().getDecorView(),ScrollView.class);
            runOnMainSync(()->scroll.scrollTo(0,dashboard.getTop())); waitForIdleSync(); capture(a,"inspector-overview");
            runOnMainSync(()->scroll.scrollBy(0,400)); waitForIdleSync(); capture(a,"inspector-map");
            runOnMainSync(()->scroll.scrollBy(0,500)); waitForIdleSync(); capture(a,"inspector-traffic");
            press(a,"Réseaux et routes"); runOnMainSync(()->scroll.scrollTo(0,dashboard.getTop())); waitForIdleSync();
            EditText search=(EditText)find(dashboard,EditText.class);
            runOnMainSync(()->search.setText("no-such-network-test")); waitForIdleSync();
            if(!hasText(dashboard,"Aucun réseau correspondant")) throw new AssertionError("Empty search not explained");
            capture(a,"inspector-empty-search"); runOnMainSync(()->search.setText("")); waitForIdleSync(); capture(a,"inspector-networks");
            press(a,"Diagnostic"); waitForIdleSync(); capture(a,"inspector-diagnostic");
            if(!hasText(dashboard,"Certificat HTTPS : NON EXÉCUTÉ")) throw new AssertionError("False active diagnostic");
            press(a,"Arrêter les collecteurs");
            if(current.get(a)!=null || !hasText(dashboard,"Aucune observation courante")) throw new AssertionError("Stop retained data");
            press(a,"Démarrer / reprendre les lectures publiques"); Thread.sleep(500); waitForIdleSync();
            startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            if(current.get(a)!=null) throw new AssertionError("Observation survived onPause");
            java.lang.reflect.Field history=InspectorDashboard.class.getDeclaredField("history"); history.setAccessible(true);
            java.lang.reflect.Field sample=InspectorDashboard.class.getDeclaredField("sample"); sample.setAccessible(true);
            if(sample.get(dashboard)!=null) throw new AssertionError("Dashboard survived onPause");
            result.putString("stream","PASS actual public samples, native map/chart, tabs, search empty/reset, passive diagnostic, explicit stop/restart, onPause clear\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable e) { result.putString("stream","FAIL "+e); finish(Activity.RESULT_CANCELED,result); }
    }
    private boolean hasText(View view,String needle) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(needle)) return true;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) if(hasText(((ViewGroup)view).getChildAt(i),needle)) return true;
        return false;
    }
    @Override protected String evidenceLabel() { return "Inspector native · EMULATOR · real observations"; }
}
