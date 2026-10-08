package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class FinalStabilityContractTest {
    @Test public void portableSettingsAllowListContainsNoCredentialLikeKeys() {
        assertTrue(PortableSettings.EXPORTED_KEYS.length > 0);
        for (String key : PortableSettings.EXPORTED_KEYS) {
            assertFalse("credential-like key must never be portable: " + key, PortableSettings.keyIsCredentialLike(key));
        }
    }

    @Test public void latencyIdentityUsesExactAndroidPostTime() {
        assertTrue(OfficialExecutionLatency.sameConcreteEvent("ENTRY", "ABC", 1000L, "ENTRY", "abc", 1000L));
        assertFalse(OfficialExecutionLatency.sameConcreteEvent("ENTRY", "ABC", 1000L, "ENTRY", "ABC", 6000L));
        assertFalse(OfficialExecutionLatency.sameConcreteEvent("ENTRY", "ABC", 1000L, "EXIT", "ABC", 1000L));
    }
}
