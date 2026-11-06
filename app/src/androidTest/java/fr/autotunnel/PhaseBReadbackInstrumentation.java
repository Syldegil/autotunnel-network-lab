package io.github.syldegil.autotunnel;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;

/** Code16 device receipt: read existing encrypted data and views; never apply a network plan. */
public final class PhaseBReadbackInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Activity activity = null;
        try {
            if (args == null || !"AUTOTUNNEL-20261105".equals(args.getString("recipe"))
                || getTargetContext().getPackageManager().getPackageInfo("io.github.syldegil.autotunnel", 0).getLongVersionCode() != 16)
                throw new AssertionError("Unexpected recipe or installed version");
            if (!Vault.file(getTargetContext()).isFile()) throw new AssertionError("Profile absent");
            if (Vault.load(getTargetContext()).getPeers().isEmpty()) throw new AssertionError("Profile unreadable");
            if (new GraphStore(getTargetContext()).existing(RoutingProfile.Scope.PHONE) == null)
                throw new AssertionError("Saved graph absent");
            result.putString("existing_data", "PASS Keystore decrypts profile; saved graph decodes");
            if (RootDaemon.HARDWARE_MUTATIONS_QUALIFIED) throw new AssertionError("Hardware gate changed");
            for (Class<?> screen : new Class<?>[]{MainActivity.class, RoutesActivity.class, InspectorActivity.class}) {
                activity = startActivitySync(new Intent(getTargetContext(), screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync(); Thread.sleep(screen == InspectorActivity.class ? 6500 : 700);
                Activity current = activity;
                runOnMainSync(() -> {
                    View view = current.getWindow().getDecorView();
                    if (view.getWidth() == 0 || view.getHeight() == 0) throw new AssertionError("Empty view");
                    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    File directory = new File(getTargetContext().getCacheDir(), "phase-b-receipt");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Capture directory");
                    try (FileOutputStream out = new FileOutputStream(new File(directory, screen.getSimpleName() + ".png"))) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception e) { throw new AssertionError("Capture failed", e); }
                    finally { bitmap.recycle(); }
                    current.finish();
                });
                waitForIdleSync(); activity = null;
            }
            result.putString("stream", "PASS code16: encrypted profile, graph, three rendered screens, hardware gate retained. No network qualification.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            if (activity != null) { Activity current = activity; runOnMainSync(current::finish); }
            result.putString("stream", "FAIL " + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
