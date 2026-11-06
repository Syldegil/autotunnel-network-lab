package io.github.syldegil.autotunnel;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Preview only. Not in the daemon command allowlist and cannot authorize any protected profile. */
public final class LoopbackFilterTrial {
    public final List<String> install, remove, check;
    public final long startSeconds, stopSeconds;
    public LoopbackFilterTrial(String id, long startSeconds, int durationSeconds) {
        if (!id.matches("[0-9a-f]{16}") || startSeconds < 1700000000L || startSeconds > 2147483000L
            || durationSeconds < 1 || durationSeconds > 30) throw new IllegalArgumentException("Bounded trial required");
        this.startSeconds = startSeconds; this.stopSeconds = startSeconds + durationSeconds;
        DateTimeFormatter format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
        List<String> match = Arrays.asList("OUTPUT", "-o", "lo", "-s", "127.0.0.1/32", "-d", "127.0.0.1/32",
            "-p", "udp", "--sport", "49152", "--dport", "49153", "-m", "owner", "--uid-owner", "2000",
            "-m", "time", "--datestart", format.format(Instant.ofEpochSecond(startSeconds)),
            "--datestop", format.format(Instant.ofEpochSecond(stopSeconds)),
            "-m", "comment", "--comment", "AT15_" + id, "-j", "DROP");
        install = command("-I", match); remove = command("-D", match); check = command("-C", match);
    }
    private static List<String> command(String operation, List<String> match) {
        List<String> argv = new ArrayList<>(Arrays.asList("/system/bin/iptables", "-w", "2", "-t", "filter", operation));
        argv.addAll(match); return Collections.unmodifiableList(argv);
    }
    /** xt_time uses UTC wall time (inclusive end), not a monotonic lease; clock reversal invalidates safety. */
    public boolean withinKernelWindow(long realtimeSeconds) { return realtimeSeconds >= startSeconds && realtimeSeconds <= stopSeconds; }
}
