package io.github.syldegil.autotunnel;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Atomic private drafts; read failure never replaces an existing file. No export or backup. */
final class GraphStore {
    private static final Object LOCK = new Object();
    private final File directory;
    GraphStore(Context context) { directory = context.getNoBackupFilesDir(); }
    private AtomicFile file(RoutingProfile.Scope scope) { return new AtomicFile(new File(directory, "topology-" + scope.name() + ".bin")); }
    GraphHistory load(RoutingProfile fallback) throws IOException {
        GraphHistory existing = existing(fallback.scope);
        return existing == null ? new GraphHistory(GraphModel.initial(fallback)) : existing;
    }
    GraphHistory existing(RoutingProfile.Scope scope) throws IOException {
        synchronized (LOCK) {
            AtomicFile file = file(scope);
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
            GraphHistory history = read(file);
            if (history.current.profile.scope != scope) throw new IOException("Parcours incohérent");
            return history;
        }
    }
    private GraphHistory read(AtomicFile file) throws IOException {
        try (InputStream in = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > GraphHistory.MAX_BYTES) throw new IOException("Historique trop volumineux"); bytes.write(buffer, 0, n); }
            return GraphHistory.decode(bytes.toByteArray());
        }
    }
    void write(GraphHistory history) throws IOException {
        synchronized (LOCK) {
            AtomicFile file = file(history.current.profile.scope);
            long revision = file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists() ? read(file).revision : 0;
            if (revision != history.revision - 1) throw new IOException("Révision concurrente ; recharger le graphe");
            byte[] bytes = history.encode(); FileOutputStream out = file.startWrite();
            try { out.write(bytes); file.finishWrite(out); } catch (IOException e) { file.failWrite(out); throw e; }
        }
    }
}
