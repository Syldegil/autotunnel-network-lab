package io.github.syldegil.autotunnel;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;

/** Connects only on explicit UI action. Never invokes su or launches/installs a daemon. */
final class RootClient {
    /** Owned by the foreground caller; cancellation closes even a blocked handshake/read. */
    static final class Call implements java.io.Closeable {
        private LocalSocket socket;
        private boolean cancelled;
        synchronized void attach(LocalSocket next) throws java.io.IOException {
            if (cancelled) { next.close(); throw new java.io.IOException("Root observation cancelled"); }
            socket = next;
        }
        @Override public synchronized void close() {
            cancelled = true;
            if (socket != null) {
                // shutdown wakes a concurrent native recv; close alone need not wake it on Linux.
                try { socket.shutdownInput(); } catch (java.io.IOException ignored) { }
                try { socket.shutdownOutput(); } catch (java.io.IOException ignored) { }
                try { socket.close(); } catch (java.io.IOException ignored) { }
            }
            socket = null;
        }
    }
    static JSONObject qualify(NetworkDiscovery.Probe probe) throws Exception {
        try (Call call = new Call()) { return qualify(probe, call); }
    }
    static JSONObject qualify(NetworkDiscovery.Probe probe, Call call) throws Exception {
        if (probe == null) throw new IllegalArgumentException("One probe required");
        try (LocalSocket socket = new LocalSocket()) {
            // Materialize the descriptor before publishing it: close must not wait on connect's monitor.
            java.io.InputStream input = socket.getInputStream();
            call.attach(socket);
            socket.setSoTimeout(15000);
            socket.connect(new LocalSocketAddress(RootDaemon.SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException("Untrusted server");
            JSONObject hello = new JSONObject(PrivilegedProtocol.read(input));
            if (hello.getInt("version") != PrivilegedProtocol.VERSION) throw new SecurityException();
            JSONObject request = new JSONObject().put("version", 1).put("sequence", 1).put("challenge", hello.getString("challenge"))
                .put("operation", PrivilegedProtocol.Operation.QUALIFY.name());
            request.put("probe", probe.name());
            PrivilegedProtocol.write(socket.getOutputStream(), request.toString());
            return new JSONObject(PrivilegedProtocol.read(input));
        }
    }
}
