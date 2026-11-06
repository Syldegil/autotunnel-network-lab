package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import com.wireguard.android.backend.*;
import com.wireguard.config.Config;
import java.util.concurrent.*;

public final class AutoService extends Service {
    static volatile String status = "Automatisation arrêtée";
    static volatile boolean running = false;
    // All tunnel transitions, including cleanup, share one serial queue across service instances.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    @android.annotation.SuppressLint("StaticFieldLeak") // Only holds the process Application context.
    private static GoBackend backend;
    private static final Tunnel tunnel = new Tunnel() {
        public String getName() { return "AutoTunnel"; }
        public void onStateChange(State state) {
            TunnelObservation.latest = new TunnelObservation(state == State.UP ? "Interface locale active" : "Interface locale arrêtée",
                -1, -1, System.currentTimeMillis());
        }
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private volatile boolean closed;
    private boolean registered;
    private Config config;
    private final Runnable evaluate = () -> worker.execute(this::reconcile);
    private final Runnable heartbeat = new Runnable() {
        public void run() { schedule(); main.postDelayed(this, 15000); }
    };
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { schedule(); }
        @Override public void onLost(Network n) { schedule(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { schedule(); }
    };
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings", MODE_PRIVATE); }
    static void start(Context c) { c.startForegroundService(new Intent(c, AutoService.class)); }
    @Override public void onCreate() {
        super.onCreate(); running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("auto", "Automatisation VPN", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification("Détection du réseau…"));
        cm = getSystemService(ConnectivityManager.class);
        try {
            // Observe physical networks, not VPN capabilities (which can inherit Wi-Fi).
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
            registered = true;
            main.post(heartbeat);
        } catch (Exception e) { publish("Surveillance indisponible : relancez l’application"); stopSelf(); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            prefs(this).edit().putBoolean("enabled", false).apply(); stopSelf(); return START_NOT_STICKY;
        }
        if (!prefs(this).getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY; }
        schedule(); return START_STICKY;
    }
    private void schedule() {
        main.post(() -> { if (!closed) { main.removeCallbacks(evaluate); main.postDelayed(evaluate, 400); } });
    }
    @SuppressWarnings("deprecation")
    private boolean hasWifi() {
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }
    private void reconcile() {
        if (closed) return;
        try {
            boolean enabled = prefs(this).getBoolean("enabled", false);
            boolean wifi = hasWifi();
            boolean up = Policy.shouldEnable(enabled, wifi);
            if (backend == null) backend = new GoBackend(getApplicationContext());
            if (up && android.net.VpnService.prepare(this) != null) {
                publish("Autorisation VPN requise : ouvrez l’application"); return;
            }
            if (up) {
                if (config == null) config = Vault.load(this);
                backend.setState(tunnel, Tunnel.State.UP, config);
                // A Wi-Fi event may have arrived while the backend resolved the endpoint.
                if (closed || hasWifi() || !prefs(this).getBoolean("enabled", false)) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null); schedule(); return;
                }
                observe();
                publish("Hors Wi-Fi · interface VPN active, trajet non vérifié");
            } else {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
                publish(wifi ? "Wi-Fi connecté · VPN désactivé" : "Automatisation arrêtée");
            }
        } catch (Exception e) {
            // Never expose exception messages: parser/backend messages can contain profile data.
            TunnelObservation.latest = new TunnelObservation("Erreur du backend · état inconnu", -1, -1, System.currentTimeMillis());
            publish("Connexion impossible · nouvelle tentative sous 15 s");
        }
    }
    private void observe() {
        try {
            Statistics stats = backend.getStatistics(tunnel);
            boolean up = backend.getState(tunnel) == Tunnel.State.UP;
            boolean measured = up && !stats.isStale();
            long handshake = measured ? 0 : -1;
            if (measured) for (com.wireguard.crypto.Key peer : stats.peers())
                handshake = Math.max(handshake, stats.peer(peer).latestHandshakeEpochMillis());
            TunnelObservation.latest = new TunnelObservation(up ? "Interface locale active" : "Interface locale arrêtée",
                measured ? stats.totalRx() : -1, measured ? stats.totalTx() : -1, System.currentTimeMillis(),
                handshake, measured ? stats.peers().length : -1);
        } catch (Exception ignored) {
            TunnelObservation.latest = new TunnelObservation("Mesure indisponible · état inconnu", -1, -1, System.currentTimeMillis());
        }
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AutoService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "auto").setSmallIcon(io.github.syldegil.autotunnel.R.drawable.ic_shield)
            .setContentTitle("AutoTunnel").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build()).build();
    }
    private void publish(String text) {
        main.post(() -> { if (!closed) { status = text; getSystemService(NotificationManager.class).notify(1, notification(text)); } });
    }
    @Override public void onDestroy() {
        closed = true; running = false; main.removeCallbacksAndMessages(null);
        if (registered) cm.unregisterNetworkCallback(callback);
        status = "Automatisation arrêtée";
        TunnelObservation.latest = new TunnelObservation("Arrêt demandé · état à confirmer", -1, -1, System.currentTimeMillis());
        worker.execute(() -> {
            try { if (backend != null) backend.setState(tunnel, Tunnel.State.DOWN, null); }
            catch (Exception ignored) {
                status = "Vérifiez l’arrêt du VPN dans les paramètres Android";
                TunnelObservation.latest = new TunnelObservation("Erreur à l’arrêt · état inconnu", -1, -1, System.currentTimeMillis());
            }
        });
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
