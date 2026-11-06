package io.github.syldegil.autotunnel;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded atomic local journal. No WireGuard config is serialized here. No export. */
final class DraftStore {
    private final Context context;
    DraftStore(Context context) { this.context = context; }
    private AtomicFile file(RoutingProfile.Scope scope) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "route-" + scope.name() + ".json"));
    }
    private JSONObject read(RoutingProfile.Scope scope) throws Exception {
        AtomicFile f = file(scope);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) return null;
        byte[] data = f.readFully();
        if (data.length > 262144) throw new IllegalStateException();
        JSONObject object = new JSONObject(new String(data, StandardCharsets.UTF_8));
        if (object.getInt("schema") != 1) throw new IllegalStateException();
        return object;
    }
    DraftHistory load(RoutingProfile.Scope scope) throws Exception {
        JSONObject object = read(scope);
        if (object == null) return new DraftHistory(RoutingProfile.defaults(scope), null, 0);
        RoutingProfile current = decode(object.getJSONObject("current"));
        RoutingProfile previous = object.isNull("previous") ? null : decode(object.getJSONObject("previous"));
        if (current.scope != scope) throw new IllegalStateException();
        return new DraftHistory(current, previous, object.getLong("revision"));
    }
    void write(DraftHistory history, String operation) throws Exception {
        if (!operation.equals("SAVE") && !operation.equals("UNDO")) throw new IllegalArgumentException();
        JSONObject old = read(history.current.scope);
        long storedRevision = old == null ? 0 : old.getLong("revision");
        if (storedRevision != history.revision - 1) throw new IllegalStateException("Concurrent revision");
        JSONArray events = old == null ? new JSONArray() : old.getJSONArray("events");
        JSONArray recent = new JSONArray();
        for (int i = Math.max(0, events.length() - 19); i < events.length(); i++) recent.put(events.getJSONObject(i));
        recent.put(new JSONObject().put("revision", history.revision).put("operation", operation)
            .put("time", System.currentTimeMillis()));
        JSONObject object = new JSONObject().put("schema", 1).put("revision", history.revision)
            .put("current", encode(history.current)).put("previous", history.previous == null ? JSONObject.NULL : encode(history.previous))
            .put("events", recent);
        AtomicFile f = file(history.current.scope);
        FileOutputStream out = f.startWrite();
        try {
            out.write(object.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out);
        } catch (Exception e) { f.failWrite(out); throw e; }
    }
    String journal(RoutingProfile.Scope scope) throws Exception {
        JSONObject object = read(scope);
        if (object == null) return "Aucun changement enregistré.";
        StringBuilder result = new StringBuilder("Journal des brouillons (20 dernières opérations ; aucune commande réseau)\n");
        JSONArray events = object.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            result.append(new java.util.Date(event.getLong("time"))).append(" · r")
                .append(event.getLong("revision")).append(" · ")
                .append("UNDO".equals(event.getString("operation")) ? "restauration" : "enregistrement").append('\n');
        }
        return result.toString();
    }
    static JSONObject encode(RoutingProfile p) throws Exception {
        JSONArray rules = new JSONArray();
        for (RoutingProfile.Rule r : p.rules) rules.put(new JSONObject()
            .put("direction", r.direction.name()).put("protocol", r.protocol.name()).put("family", r.family.name())
            .put("path", r.path.name()).put("action", r.action.name()).put("first", r.firstPort).put("last", r.lastPort)
            .put("source", r.source).put("destination", r.destination).put("priority", r.priority).put("enabled", r.enabled)
            .put("inputInterface", r.inputInterface).put("outputInterface", r.outputInterface));
        return new JSONObject().put("scope", p.scope.name()).put("mode", p.mode.name()).put("ipv6", p.ipv6.name())
            .put("routes4", new JSONArray(p.routes4)).put("routes6", new JSONArray(p.routes6))
            .put("dns", new JSONArray(p.dns)).put("rules", rules);
    }
    static RoutingProfile decode(JSONObject object) throws Exception {
        List<RoutingProfile.Rule> rules = new ArrayList<>();
        JSONArray values = object.getJSONArray("rules");
        if (values.length() > 64) throw new IllegalArgumentException();
        for (int i = 0; i < values.length(); i++) {
            JSONObject r = values.getJSONObject(i);
            rules.add(new RoutingProfile.Rule(RoutingProfile.Direction.valueOf(r.getString("direction")),
                RoutingProfile.Protocol.valueOf(r.getString("protocol")), RoutingProfile.Family.valueOf(r.getString("family")),
                RoutingProfile.Path.valueOf(r.getString("path")), RoutingProfile.Action.valueOf(r.getString("action")),
                r.getInt("first"), r.getInt("last"), r.getString("source"), r.getString("destination"),
                r.getInt("priority"), r.getBoolean("enabled"), r.optString("inputInterface", ""), r.optString("outputInterface", "")));
        }
        return new RoutingProfile(RoutingProfile.Scope.valueOf(object.getString("scope")),
            RoutingProfile.Mode.valueOf(object.getString("mode")), RoutingProfile.V6.valueOf(object.getString("ipv6")),
            strings(object.getJSONArray("routes4")), strings(object.getJSONArray("routes6")), strings(object.getJSONArray("dns")), rules);
    }
    private static List<String> strings(JSONArray array) throws Exception {
        if (array.length() > 128) throw new IllegalArgumentException();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }
}
