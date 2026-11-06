package io.github.syldegil.autotunnel;

public final class Policy {
    private Policy() {}
    public static boolean shouldEnable(boolean automatic, boolean wifiConnected) {
        return automatic && !wifiConnected;
    }
}
