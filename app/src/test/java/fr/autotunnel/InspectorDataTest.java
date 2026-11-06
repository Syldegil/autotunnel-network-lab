package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class InspectorDataTest {
    @Test public void selectionLabelsShowUidAndUnambiguousIpv6Endpoints() {
        InspectorData.Connection c = new InspectorData.Connection("TCP6", "::1", 1234, "2001:db8::1", 443, "01", 10123);
        assertTrue(c.label().contains("UID=10123")); assertTrue(c.label().contains("[2001:db8::1]:443"));
    }
    @Test public void socketMetadataDecodesWithoutResolvingOrInventingPath() {
        String row="0: 0100007F:1F90 0200007F:01BB 01 00000000:00000000 00:00000000 00000000 10123 0 1234";
        List<InspectorData.Connection> c=InspectorData.connections(row,false,false);assertEquals(1,c.size());
        assertEquals("127.0.0.1",c.get(0).source);assertEquals(8080,c.get(0).sourcePort);assertEquals(443,c.get(0).destinationPort);
        assertTrue(InspectorData.connections("Permission denied",false,false).isEmpty());
    }
    @Test public void interfaceAndFamilyCountersNeverInventMissingZeros() {
        Map<String,long[]> c=InspectorData.interfaces("simusb0: 100 1 0 0 0 0 0 0 200 1 0 0 0 0 0 0\nmalformed");
        assertArrayEquals(new long[]{100,200},c.get("simusb0"));
        assertTrue(InspectorData.familyOctets("denied","").isEmpty());
        Map<String,Long> f=InspectorData.familyOctets("IpExt: InOctets OutOctets\nIpExt: 100 200","Ip6InOctets 42\nIp6OutOctets 84");
        assertEquals(Long.valueOf(100),f.get("IPv4-InOctets"));assertEquals(Long.valueOf(84),f.get("Ip6OutOctets"));
    }
    @Test public void ratesRejectResetsReusedInterfacesAndStaleSamples() {
        assertEquals(500,InspectorData.rate(0,1000,2000,true));assertEquals(-1,InspectorData.rate(100,0,2000,true));
        assertEquals(-1,InspectorData.rate(0,1000,2000,false));assertEquals(-1,InspectorData.rate(0,1000,16000,true));
    }
    @Test public void connectionsAreBounded() {
        StringBuilder text=new StringBuilder();for(int i=0;i<1000;i++)text.append("0: 0100007F:1F90 0200007F:01BB 01 0 0 0 10123 0 1\n");
        assertEquals(128,InspectorData.connections(text.toString(),false,false).size());
    }
}
