package io.github.syldegil.autotunnel;

import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable root-owned write-ahead checkpoint. No WG config/key, no executable command strings. */
final class EngineJournal implements NetworkEngine.Journal {
    private final AtomicFile file;
    EngineJournal(File path) { file = new AtomicFile(path); }
    public synchronized void write(String phase, NetworkPlan plan, List<String> applied) throws Exception {
        JSONObject object = new JSONObject().put("schema", 2).put("phase", phase).put("digest", plan.digest)
            .put("profile", DraftStore.encode(plan.profile)).put("snapshot", NetworkCodec.snapshot(plan.snapshot))
            .put("applied", new JSONArray(applied)).put("time", System.currentTimeMillis());
        JSONArray profiles = new JSONArray();
        for (RoutingProfile profile : plan.profiles) profiles.put(DraftStore.encode(profile));
        object.put("profiles", profiles);
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 262144) throw new IOException("Journal size");
        FileOutputStream out = file.startWrite();
        try { out.write(bytes); out.getFD().sync(); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
        // AtomicFile may log a failed rename instead of throwing: readback is mandatory.
        if (!Arrays.equals(bytes, file.readFully())) throw new IOException("Journal readback");
    }
    JSONObject read() throws Exception {
        byte[] bytes = file.readFully(); if (bytes.length > 262144) throw new IOException("Journal size");
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (value.getInt("schema") != 1 && value.getInt("schema") != 2) throw new IOException("Journal version");
        return value;
    }
    boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists(); }
    NetworkPlan reconstruct(JSONObject record) throws Exception {
        List<RoutingProfile> profiles = new ArrayList<>();
        if (record.getInt("schema") == 1) profiles.add(DraftStore.decode(record.getJSONObject("profile")));
        else if (record.getInt("schema") == 2) {
            JSONArray values = record.getJSONArray("profiles");
            if (values.length() < 1 || values.length() > 3) throw new IOException("Journal profile count");
            for (int i = 0; i < values.length(); i++) profiles.add(DraftStore.decode(values.getJSONObject(i)));
        } else throw new IOException("Journal version");
        return RecoveryPlan.reconstruct(profiles, NetworkCodec.snapshot(record.getJSONObject("snapshot")), record.getString("digest"));
    }
}
