package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkCheckTest {
    @Test
    public void normalizesIpv4() {
        assertEquals("49.37.10.25", NetworkCheck.normalizeIp(" 49.37.10.25 "));
    }

    @Test
    public void normalizesBracketedIpv6() {
        assertEquals("2001:db8::1", NetworkCheck.normalizeIp("[2001:DB8::1]"));
    }

    @Test
    public void validatesIpShapes() {
        assertTrue(NetworkCheck.looksLikeIp("49.37.10.25"));
        assertTrue(NetworkCheck.looksLikeIp("2001:db8::1"));
        assertFalse(NetworkCheck.looksLikeIp("192.168.1.999"));
        assertFalse(NetworkCheck.looksLikeIp("vpn.example.com"));
    }
}
