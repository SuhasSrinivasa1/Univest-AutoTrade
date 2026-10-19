package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedFormulaNetworkBackoffTest {
    @Test public void recognizesGrowwApiRateLimitAndDns() {
        assertEquals("RATE_LIMIT", FixedFormulaNetworkBackoff.category(
                new IllegalStateException("Historical candle HTTP 429: Rate limit has breached")));
        assertEquals("DNS_UNAVAILABLE", FixedFormulaNetworkBackoff.category(
                new java.net.UnknownHostException("Unable to resolve host api.groww.in")));
        assertEquals("", FixedFormulaNetworkBackoff.category(new IllegalArgumentException("Invalid ticker")));
    }
    @Test public void repeatedFailuresBackOffWithoutUnboundedRetryStorm() {
        assertEquals(600_000L, FixedFormulaNetworkBackoff.cooldownMillis("RATE_LIMIT",1));
        assertEquals(1_200_000L, FixedFormulaNetworkBackoff.cooldownMillis("RATE_LIMIT",2));
        assertEquals(2_700_000L, FixedFormulaNetworkBackoff.cooldownMillis("RATE_LIMIT",50));
        assertEquals(300_000L, FixedFormulaNetworkBackoff.cooldownMillis("DNS_UNAVAILABLE",1));
    }
}
