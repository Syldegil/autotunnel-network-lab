package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
public class PolicyTest {
    @Test public void wifiAlwaysDisablesTunnel() { assertFalse(Policy.shouldEnable(true, true)); }
    @Test public void noWifiEnablesTunnelIncludingOffline() { assertTrue(Policy.shouldEnable(true, false)); }
    @Test public void manualStopWinsOnEitherNetwork() {
        assertFalse(Policy.shouldEnable(false, false)); assertFalse(Policy.shouldEnable(false, true));
    }
}
