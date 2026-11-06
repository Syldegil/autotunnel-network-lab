package io.github.syldegil.autotunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Fixed system executables, bounded output/time, argv only. Never requests su or downloads tools. */
final class DeviceCommandRunner implements RootNetworkBackend.Runner {
    public RootNetworkBackend.Result run(NetworkPlan.Tool tool, List<String> args) throws Exception {
        String binary = tool == NetworkPlan.Tool.IP ? "/system/bin/ip" : tool == NetworkPlan.Tool.IPTABLES ? "/system/bin/iptables" : "/system/bin/ip6tables";
        return command(binary, args);
    }
    static RootNetworkBackend.Result command(String binary, List<String> args) throws Exception {
        if (!Arrays.asList("/system/bin/ip", "/system/bin/iptables", "/system/bin/ip6tables", "/system/bin/nft",
            "/system/bin/getenforce", "/system/bin/id", "/system/bin/dumpsys", "/system/bin/tc", "/system/bin/settings", "/data/adb/ksu/bin/ksud",
            "/system/bin/iptables-save", "/system/bin/ip6tables-save", "/system/bin/cmd", "/system/bin/cat", "/system/bin/ls",
            "/system/bin/getprop", "/system/bin/bpftool", "/system/bin/conntrack").contains(binary))
            throw new IllegalArgumentException("Executable not approved");
        List<String> argv = new ArrayList<>(); argv.add(binary); argv.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().put("PATH", "/system/bin:/system/xbin"); builder.environment().put("LANG", "C");
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<byte[]> bytes = reader.submit(() -> {
            try (InputStream in = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 524288) throw new IOException("Output limit"); out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        });
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("Command timeout");
            return new RootNetworkBackend.Result(process.exitValue(), new String(bytes.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } finally { process.destroyForcibly(); reader.shutdownNow(); }
    }
}
