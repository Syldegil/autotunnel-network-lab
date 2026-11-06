package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetworkLiveTest {
 @Test public void publicAddressValidationRejectsHostnamesAndMalformedInput(){assertTrue(NetworkWatchService.validIp("203.0.113.4"));assertTrue(NetworkWatchService.validIp("2001:db8::1"));assertFalse(NetworkWatchService.validIp("example.com"));assertFalse(NetworkWatchService.validIp("999.1.1.1"));assertFalse(NetworkWatchService.validIp("127.1"));assertFalse(NetworkWatchService.validIp("<html>"));assertFalse(NetworkWatchService.validIp(""));}
 @Test public void tunnelNeverCountsAsPhysical(){assertEquals("",PhysicalRates.group("tun0","VPN"));assertEquals("",PhysicalRates.group("wlan0","VPN"));assertEquals("Wi-Fi",PhysicalRates.group("wlan0",""));assertEquals("USB",PhysicalRates.group("rndis0",""));assertEquals("USB",PhysicalRates.group("ncm0",""));assertEquals("4G / 5G",PhysicalRates.group("rmnet_data2",""));assertEquals("",PhysicalRates.group("lo",""));assertEquals("",PhysicalRates.group("v4-rmnet_data2",""));}
}
