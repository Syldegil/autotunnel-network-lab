package io.github.syldegil.autotunnel;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && AutoService.prefs(context).getBoolean("enabled", false)) {
            try { AutoService.start(context); }
            catch (RuntimeException ignored) { /* Some OEMs require a manual launch after boot. */ }
        }
    }
}
