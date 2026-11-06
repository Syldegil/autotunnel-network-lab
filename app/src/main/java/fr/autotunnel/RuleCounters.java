package io.github.syldegil.autotunnel;

import java.util.*;
import java.util.regex.*;

/** Parses counters only for our filter chains. No rule text/comment is returned. */
public final class RuleCounters {
    private RuleCounters() { }
    public static Map<String, long[]> parse(String saveOutput) {
        Map<String, long[]> counters = new LinkedHashMap<>(); Map<String, Integer> positions = new HashMap<>();
        Pattern rule = Pattern.compile("^\\[(\\d{1,19}):(\\d{1,19})] -A (AT13_[GRU][46][IOF]) .*$");
        for (String line : saveOutput.split("\n")) {
            Matcher m = rule.matcher(line.trim()); if (!m.matches()) continue;
            try {
                long packets = Long.parseLong(m.group(1)), bytes = Long.parseLong(m.group(2));
                int index = positions.getOrDefault(m.group(3), 0) + 1; positions.put(m.group(3), index);
                counters.put(m.group(3) + ":" + index, new long[]{packets, bytes});
            } catch (NumberFormatException ignored) { /* Counter overflow is unknown, not zero. */ }
        }
        return counters;
    }
}
