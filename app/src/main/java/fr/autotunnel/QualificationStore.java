package io.github.syldegil.autotunnel;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Display-only evidence in no-backup storage. Hardware authority never reads this app-owned file. */
final class QualificationStore {
    private final AtomicFile file;
    QualificationStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "ds014-observations.json")); }
    void save(CapabilityMatrix matrix) throws Exception {
        JSONArray array = new JSONArray();
        for (CapabilityMatrix.Evidence e : matrix.records()) array.put(new JSONObject().put("capability", e.capability.name())
            .put("path", e.path.name()).put("status", e.status.name()).put("context", e.contextHash).put("artifact", e.artifactHash)
            .put("test", e.testId).put("at", e.at).put("expires", e.expires));
        byte[] data = new JSONObject().put("schema", 1).put("evidence", array).toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 131072) throw new IOException();
        FileOutputStream out = file.startWrite();
        try { out.write(data); out.getFD().sync(); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    CapabilityMatrix load() {
        CapabilityMatrix matrix = new CapabilityMatrix();
        try {
            byte[] bytes = file.readFully(); if (bytes.length > 131072) return matrix;
            JSONObject object = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); if (object.getInt("schema") != 1) return matrix;
            JSONArray array = object.getJSONArray("evidence"); if (array.length() > 256) return matrix;
            for (int i = 0; i < array.length(); i++) {
                JSONObject e = array.getJSONObject(i);
                matrix.recordObservation(new CapabilityMatrix.Evidence(CapabilityMatrix.Capability.valueOf(e.getString("capability")),
                    CapabilityMatrix.Path.valueOf(e.getString("path")), CapabilityMatrix.Status.valueOf(e.getString("status")),
                    e.getString("context"), e.getString("artifact"), e.getString("test"), e.getLong("at"), e.getLong("expires")));
            }
        } catch (Exception ignored) { return new CapabilityMatrix(); }
        return matrix;
    }
}
