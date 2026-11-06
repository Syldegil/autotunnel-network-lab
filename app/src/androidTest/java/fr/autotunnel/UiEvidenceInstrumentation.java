package io.github.syldegil.autotunnel;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Test APK only. Captures actual Android View rendering without weakening FLAG_SECURE. */
public class UiEvidenceInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.FINGERPRINT.contains("sdk_")
                && !android.os.Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("sdk"))
                throw new AssertionError("This test is restricted to an emulator, never the user's phone");
            Activity activity = startActivitySync(new Intent(getTargetContext(), RoutesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> ((GraphEditor)find(activity.getWindow().getDecorView(),GraphEditor.class)).change(GraphModel.initial(RoutingProfile.defaults(RoutingProfile.Scope.PHONE))));
            waitForIdleSync();
            capture(activity, "graph-editor");
            exerciseGraph(activity);
            exerciseTemplates(activity);
            result.putString("multibranch_templates", "PASS combined/mixed load; private duplicate; preview; transaction; per-branch tunnel failure; restore");
            verifyLandscape(activity);
            result.putString("graph_gestures_and_persistence", "PASS drag node; palette; drag typed link; branch rejection; undo/redo; store reload; zoom; properties; preview");
            String action = arguments == null ? null : arguments.getString("click");
            if (action != null) {
                final boolean[] clicked = {false};
                runOnMainSync(() -> clicked[0] = click(activity.getWindow().getDecorView(), action));
                if (!clicked[0]) throw new AssertionError("Requested visible control not found: " + action);
                waitForIdleSync();
                // Capture the current focused dialog as well as the Activity when possible.
                capture(activity, "graph-after-action");
            }
            final InspectorCollector.Sample[] sample = {null};
            sample[0] = InspectorCollector.sample(getTargetContext());
            result.putString("public_counter_sources", sample[0].counterSources.toString());
            result.putString("public_counter_count", Integer.toString(sample[0].counters.size()));
            verifyRecoveryJournal();
            result.putString("recovery_journal", "PASS seven topologies AtomicFile write/read/reconstruct; legacy schema; altered digest refusal; no network mutation");
            verifyStructuredInspection();
            verifyCancellation();
            if (arguments != null && "true".equals(arguments.getString("root"))) {
                org.json.JSONObject reply = RootClient.qualify(NetworkDiscovery.Probe.LINK_COUNTERS);
                if (!reply.getBoolean("ok") || reply.getBoolean("hardwareEnabled")) throw new AssertionError("Helper read contract");
                RootInspection.Snapshot root = RootInspection.decode(reply.getJSONObject("inspection"), NetworkDiscovery.Probe.LINK_COUNTERS);
                if (root.counters.isEmpty() || !root.status.equals("READ_OK")) throw new AssertionError("Root counters unavailable");
                for (String operation : new String[]{"APPLY", "RESTORE", "CLOSE", "PREVIEW"}) verifyLockedOperation(operation);
                org.json.JSONObject tether = RootClient.qualify(NetworkDiscovery.Probe.TETHERING);
                if (!tether.getBoolean("ok") || tether.getBoolean("hardwareEnabled")
                    || !"TETHERING".equals(tether.getString("probe"))
                    || !tether.getString("local").contains("NON QUALIFIÉ")) throw new AssertionError("Tethering observation contract");
                result.putString("root_tethering_observation", "PASS authenticated one-shot probe, no hardware qualification");
                result.putString("isolated_root_ipc", "PASS uid=" + android.os.Process.myUid() + " interfaces=" + root.counters.size());
            }
            verifyInspectorLifecycle();
            result.putString("inspector_foreground_lifecycle", "PASS sample present; timer cancelled and observations cleared onPause");
            result.putString("structured_payload_and_socket_cancellation", "PASS on emulator Android runtime");
            result.putString("stream", "Actual emulator view captures complete; no network policy applied\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void verifyRecoveryJournal() throws Exception {
        File path = new File(getTargetContext().getCacheDir(), "ds017-test-journal.json");
        try {
            EngineJournal journal = new EngineJournal(path);
            for (int i = 0; i < 7; i++) {
                NetworkEngine engine = new NetworkEngine(new SimulatedNetworkBackend(1000), new NetworkEngine.MemoryJournal());
                NetworkPlan plan = engine.preview(GraphCompiler.compile(GraphTemplates.create(i)).topology);
                journal.write("BEFORE:" + plan.policy.get(0).id, plan, java.util.Collections.emptyList());
                org.json.JSONObject record = journal.read();
                NetworkPlan rebuilt = journal.reconstruct(record);
                if (!plan.digest.equals(rebuilt.digest) || rebuilt.profiles.size() != plan.profiles.size())
                    throw new AssertionError("Journal topology lost");
                if (!RecoveryPlan.pending(rebuilt, NetworkCodec.strings(record.getJSONArray("applied")), record.getString("phase"))
                    .contains(plan.policy.get(0).id)) throw new AssertionError("Interrupted operation lost");
                if (plan.profiles.size() == 1) {
                    record.put("schema", 1); record.remove("profiles");
                    if (!journal.reconstruct(record).digest.equals(plan.digest)) throw new AssertionError("Legacy journal");
                }
                record.put("digest", NetworkPlan.hash("tampered"));
                boolean rejected = false;
                try { journal.reconstruct(record); } catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Altered journal accepted");
            }
        } finally {
            new android.util.AtomicFile(path).delete();
        }
    }
    protected GraphCanvas graphCanvas(Activity activity) {
        return (GraphCanvas) find(activity.getWindow().getDecorView(), GraphCanvas.class);
    }
    protected View find(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    protected GraphModel graph(GraphCanvas canvas) throws Exception {
        java.lang.reflect.Field field = GraphCanvas.class.getDeclaredField("graph"); field.setAccessible(true); return (GraphModel) field.get(canvas);
    }
    protected void press(Activity activity, String label) {
        final boolean[] clicked = {false};
        runOnMainSync(() -> clicked[0] = click(activity.getWindow().getDecorView(), label));
        if (!clicked[0]) throw new AssertionError("Control missing: " + label);
        waitForIdleSync();
    }
    protected void clickPaletteItem(Activity activity, String label) throws Exception {
        GraphEditor editor = (GraphEditor) find(activity.getWindow().getDecorView(), GraphEditor.class);
        java.lang.reflect.Field field = GraphEditor.class.getDeclaredField("activeDialog"); field.setAccessible(true);
        AlertDialog dialog = (AlertDialog) field.get(editor);
        ListView list = dialog.getListView(); final int[] position = {-1}; final int[] target = new int[2];
        runOnMainSync(() -> {
            for (int i = 0; i < list.getAdapter().getCount(); i++) if (label.equals(list.getAdapter().getItem(i).toString())) position[0] = i;
            if (position[0] < 0) throw new AssertionError("Palette item missing");
            list.setSelection(position[0]);
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            View row = list.getChildAt(position[0]-list.getFirstVisiblePosition());
            if (row == null) throw new AssertionError("Palette row not visible");
            row.getLocationOnScreen(target); target[0] += row.getWidth()/2; target[1] += row.getHeight()/2;
        });
        long at = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, target[0], target[1], 0);
        sendPointerSync(down); down.recycle();
        MotionEvent up = MotionEvent.obtain(at, at+50, MotionEvent.ACTION_UP, target[0], target[1], 0);
        sendPointerSync(up); up.recycle(); getUiAutomation().waitForIdle(250,3000);waitForIdleSync();
    }
    protected void drag(GraphCanvas canvas, GraphModel model, float x1, float y1, float x2, float y2) throws Exception {
        int[] location = new int[2]; runOnMainSync(() -> canvas.getLocationOnScreen(location));
        float density = canvas.getResources().getDisplayMetrics().density;
        long down = android.os.SystemClock.uptimeMillis();
        for (int step = 0; step <= 6; step++) {
            float fraction = step / 6f;
            float x = location[0] + density * (model.panX + (x1 + (x2-x1)*fraction)*model.zoom);
            float y = location[1] + density * (model.panY + (y1 + (y2-y1)*fraction)*model.zoom);
            MotionEvent event = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), step == 0 ? MotionEvent.ACTION_DOWN : step == 6 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, y, 0);
            sendPointerSync(event); event.recycle(); Thread.sleep(35);
        }
        waitForIdleSync();
    }
    protected void dismissDialog(Activity activity) throws Exception {
        GraphEditor editor = (GraphEditor) find(activity.getWindow().getDecorView(), GraphEditor.class);
        java.lang.reflect.Field field = GraphEditor.class.getDeclaredField("activeDialog"); field.setAccessible(true);
        AlertDialog dialog = (AlertDialog) field.get(editor);
        if (dialog == null || !dialog.isShowing()) throw new AssertionError("Expected graph dialog");
        runOnMainSync(dialog::dismiss); waitForIdleSync();
    }
    protected void exerciseGraph(Activity activity) throws Exception {
        GraphCanvas canvas = graphCanvas(activity);
        if (canvas == null) throw new AssertionError("Interactive canvas missing");
        GraphModel before = graph(canvas); GraphModel.Node source = before.node("n0");
        if (source == null) throw new AssertionError("Fresh test graph expected");
        drag(canvas, before, source.x+90, source.y+24, source.x+130, source.y+49);
        GraphModel moved = graph(canvas);
        if (Math.abs(moved.node("n0").x-source.x) < 20 || !RoutePolicy.plan(before.profile).equals(RoutePolicy.plan(moved.profile)))
            throw new AssertionError("Drag changed policy or did not move the node");
        capture(activity, "graph-node-moved");
        press(activity, "Annuler");
        if (Math.abs(graph(canvas).node("n0").x-source.x) > .1) throw new AssertionError("Undo did not restore position");
        press(activity, "Rétablir");
        if (Math.abs(graph(canvas).node("n0").x-moved.node("n0").x) > .1) throw new AssertionError("Redo did not restore move");
        press(activity, "+ Palette"); capture(activity, "graph-palette"); clickPaletteItem(activity, "DNS");
        GraphModel added = graph(canvas); GraphModel.Node dns = null;
        for (GraphModel.Node node : added.nodes) if (node.kind == GraphModel.Kind.DNS) dns = node;
        if (dns == null) throw new AssertionError("Palette did not create DNS node");
        GraphModel.Node from = added.node("n0");
        PointF output = GraphCanvas.point(from, GraphModel.Port.OUT_IP), input = GraphCanvas.point(dns, GraphModel.Port.IN_IP);
        drag(canvas, added, output.x, output.y, input.x, input.y);
        GraphModel connected = graph(canvas);
        if (connected.links.size() != added.links.size()+1 || GraphCompiler.compile(connected).valid())
            throw new AssertionError("Typed drag did not create link or unsupported branch accepted");
        capture(activity, "graph-linked-draft");
        press(activity, "Annuler"); press(activity, "Annuler");
        GraphHistory disk = new GraphStore(getTargetContext()).existing(RoutingProfile.Scope.PHONE);
        if (disk == null || !java.util.Arrays.equals(GraphCodec.encode(graph(canvas)), GraphCodec.encode(disk.current)))
            throw new AssertionError("Saved graph differs from rendered model");
        press(activity, "Cadrer"); capture(activity, "graph-overview");
        float zoom = graph(canvas).zoom; press(activity, "+");
        if (graph(canvas).zoom <= zoom) throw new AssertionError("Zoom control ineffective");
        runOnMainSync(() -> canvas.select("n2")); press(activity, "Propriétés");
        capture(activity, "graph-wireguard-properties"); dismissDialog(activity);
        press(activity, "Compiler / simuler"); capture(activity, "graph-preview"); dismissDialog(activity);
        if (GraphCompiler.HARDWARE_ACTIVATION_ENABLED || RootDaemon.HARDWARE_MUTATIONS_QUALIFIED) throw new AssertionError("Hardware lock lifted");
    }
    private void verifyLockedOperation(String operation) throws Exception {
        try (android.net.LocalSocket socket = new android.net.LocalSocket()) {
            socket.connect(new android.net.LocalSocketAddress(RootDaemon.SOCKET, android.net.LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(5000);
            if (socket.getPeerCredentials().getUid() != 0) throw new AssertionError("Unexpected helper UID");
            org.json.JSONObject hello = new org.json.JSONObject(PrivilegedProtocol.read(socket.getInputStream()));
            org.json.JSONObject request = new org.json.JSONObject().put("version", 1).put("sequence", 1)
                .put("challenge", hello.getString("challenge")).put("operation", operation).put("probe", "ID");
            PrivilegedProtocol.write(socket.getOutputStream(), request.toString());
            org.json.JSONObject reply = new org.json.JSONObject(PrivilegedProtocol.read(socket.getInputStream()));
            if (reply.getBoolean("ok") || !reply.getString("state").equals("UNSUPPORTED")) throw new AssertionError("Observation server accepted " + operation);
        }
    }
    private void verifyInspectorLifecycle() throws Exception {
        Activity activity = startActivitySync(new Intent(getTargetContext(), InspectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(() -> {
            if (!click(activity.getWindow().getDecorView(), "Démarrer / reprendre les lectures publiques"))
                throw new AssertionError("Public collector control unavailable");
        });
        // Fixed two-sample observation window on the emulator; no device polling service.
        Thread.sleep(3500); waitForIdleSync();
        java.lang.reflect.Field current = InspectorActivity.class.getDeclaredField("current"); current.setAccessible(true);
        java.lang.reflect.Field timer = InspectorActivity.class.getDeclaredField("timer"); timer.setAccessible(true);
        final java.util.concurrent.ScheduledFuture<?>[] observed = {null};
        final Throwable[] failure = {null};
        runOnMainSync(() -> {
            try {
                if (current.get(activity) == null) throw new AssertionError("No foreground sample");
                observed[0] = (java.util.concurrent.ScheduledFuture<?>) timer.get(activity);
                if (observed[0] == null || observed[0].isDone()) throw new AssertionError("Collector not running");
            } catch (Throwable e) { failure[0] = e; }
        });
        if (failure[0] != null) throw new AssertionError("Collector start", failure[0]);
        capture(activity, "inspector-public");
        startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(() -> {
            try {
                if (!observed[0].isCancelled() || current.get(activity) != null || timer.get(activity) != null || RootInspection.latest() != null)
                    throw new AssertionError("Collector survived onPause");
            } catch (Throwable e) { failure[0] = e; }
        });
        if (failure[0] != null) throw new AssertionError("Collector stop", failure[0]);
    }
    private void verifyStructuredInspection() throws Exception {
        RootInspection.Snapshot original = RootInspection.parse(NetworkDiscovery.Probe.LINK_COUNTERS, 123,
            "test0: 100 1 0 0 0 0 0 0 200 1 0 0 0 0 0 0\n");
        org.json.JSONObject encoded = RootInspection.encode(original);
        if (RootInspection.decode(encoded, NetworkDiscovery.Probe.LINK_COUNTERS).counters.get("test0")[1] != 200)
            throw new AssertionError("Counter roundtrip");
        encoded.put("command", "NOT_PERMITTED");
        try { RootInspection.decode(encoded, NetworkDiscovery.Probe.LINK_COUNTERS); throw new AssertionError("Unknown field accepted"); }
        catch (IOException expected) { /* strict schema */ }
    }
    private void verifyCancellation() throws Exception {
        String name = "autotunnel.test.cancel." + android.os.Process.myPid();
        try (android.net.LocalServerSocket server = new android.net.LocalServerSocket(name);
             android.net.LocalSocket client = new android.net.LocalSocket(); RootClient.Call call = new RootClient.Call()) {
            client.connect(new android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT));
            try (android.net.LocalSocket accepted = server.accept()) {
                call.attach(client);
                java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
                Thread reader = new Thread(() -> {
                    try { reading.countDown(); client.getInputStream().read(); } catch (IOException expected) { }
                }, "ipc-cancellation-test");
                reader.start();
                if (!reading.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Reader did not start");
                reader.join(100);
                if (!reader.isAlive()) throw new AssertionError("Test read was not blocked before cancellation");
                call.close(); reader.join(2000);
                if (reader.isAlive()) throw new AssertionError("Socket cancellation left a blocked read");
            }
        }
    }
    protected boolean click(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label) && view.isShown()) return view.performClick();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (click(((ViewGroup) view).getChildAt(i), label)) return true;
        return false;
    }
    protected String evidenceLabel() { return "EMULATEUR API " + android.os.Build.VERSION.SDK_INT + " · CODE7 · DS016 · rendu réel"; }
    private void clickWindow(String label){
        try{getUiAutomation().waitForIdle(250,3000);}catch(java.util.concurrent.TimeoutException e){throw new AssertionError("UI did not settle",e);}
        final boolean[] done={false};runOnMainSync(()->{for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())if(window.isShown()&&click(window,label)){done[0]=true;break;}});
        if(!done[0])throw new AssertionError("Dialog control missing "+label);waitForIdleSync();
    }
    private boolean windowContains(String expected){
        for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root.isShown()&&contains(root,expected))return true;return false;
    }
    private boolean contains(View v,String value){if(v instanceof TextView&&((TextView)v).getText().toString().contains(value))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),value))return true;return false;}
    private void exerciseTemplates(Activity activity)throws Exception{
        press(activity,"Modèles / copies");clickPaletteItem(activity,"Routage combiné");clickWindow("Charger");
        GraphCompiler.Result result=GraphCompiler.compile(graph(graphCanvas(activity)));
        if(!result.valid()||result.topology.branches.size()!=3)throw new AssertionError("Combined template not loaded into actual editor");
        press(activity,"Cadrer");capture(activity,"ds016-combined");
        int copies=new GraphLibrary(getTargetContext()).names().size();press(activity,"Modèles / copies");clickPaletteItem(activity,"Dupliquer ce graphe dans la bibliothèque");
        if(new GraphLibrary(getTargetContext()).names().size()!=copies+1)throw new AssertionError("Library duplication failed");
        press(activity,"Modèles / copies");clickPaletteItem(activity,"Routage mixte · téléphone direct explicite");clickWindow("Charger");press(activity,"Cadrer");capture(activity,"ds016-mixed");
        press(activity,"Compiler / simuler");capture(activity,"ds016-preview");clickWindow("Simulation transactionnelle / capacités");
        clickWindow("Simuler la transaction");clickWindow("Simuler");
        if(!windowContains("VPN_PROTECTED"))throw new AssertionError("Transaction simulation failed");
        clickWindow("Simuler panne du tunnel");
        if(!windowContains("PHONE=DIRECT")||!windowContains("USB=VPN_BLOCKED"))throw new AssertionError("Branch fail-closed UI mismatch");
        clickWindow("Restaurer le simulateur");clickWindow("Fermer");
    }
    private void verifyLandscape(Activity activity)throws Exception{
        GraphModel before=graph(graphCanvas(activity));ActivityMonitor monitor=addMonitor(RoutesActivity.class.getName(),null,false);
        runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        Activity rotated=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);if(rotated==null)throw new AssertionError("Landscape Activity absent");waitForIdleSync();
        GraphModel after=graph(graphCanvas(rotated));if(!GraphCompiler.compile(before).report().equals(GraphCompiler.compile(after).report()))throw new AssertionError("Rotation changed policy");
        for(GraphModel.Node n:before.nodes)if(n.x!=after.node(n.id).x||n.y!=after.node(n.id).y)throw new AssertionError("Rotation changed node positions");
        if(graphCanvas(rotated).getHeight()/getTargetContext().getResources().getDisplayMetrics().density<100)throw new AssertionError("Landscape canvas too small");
        capture(rotated,"ds016-landscape");runOnMainSync(()->rotated.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));waitForIdleSync();
    }
    protected void capture(Activity activity, String name) throws Exception {
        final Throwable[] error = {null};
        runOnMainSync(() -> {
            try {
                View root = activity.getWindow().getDecorView();
                if (root.getWidth() == 0 || root.getHeight() == 0) throw new AssertionError("View not laid out");
                Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap); root.draw(canvas);
                GraphEditor editor = (GraphEditor) find(root, GraphEditor.class);
                if (editor != null) {
                    java.lang.reflect.Field field = GraphEditor.class.getDeclaredField("activeDialog"); field.setAccessible(true);
                    AlertDialog dialog = (AlertDialog) field.get(editor);
                    if (dialog != null && dialog.isShowing()) {
                        View decor = dialog.getWindow().getDecorView(); int[] at = new int[2], origin = new int[2];
                        decor.getLocationOnScreen(at); root.getLocationOnScreen(origin);
                        canvas.drawColor(0x88000000); canvas.save(); canvas.translate(at[0]-origin[0], at[1]-origin[1]);
                        decor.draw(canvas); canvas.restore();
                    }
                }
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.rgb(15,23,42));
                canvas.drawRect(0, bitmap.getHeight()-70, bitmap.getWidth(), bitmap.getHeight(), paint);
                paint.setColor(Color.WHITE); paint.setTextSize(24);
                canvas.drawText(evidenceLabel(), 16, bitmap.getHeight()-25, paint);
                File directory = new File(getTargetContext().getCacheDir(), "ui-evidence");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Evidence directory");
                try (OutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encoding");
                } finally { bitmap.recycle(); }
            } catch (Throwable failure) { error[0] = failure; }
        });
        if (error[0] != null) throw new IOException("Capture failed", error[0]);
    }
}
