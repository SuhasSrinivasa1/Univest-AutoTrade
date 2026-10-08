package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class UIArchitectureContractTest {
    @Test public void primaryNavigationHasFourDistinctUserJobs() {
        assertArrayEquals(new String[]{"Execution", "Research", "Forecast", "Settings"},
                UiArchitecture.primaryTabLabels());
    }

    @Test public void campaignPhasesUseHumanReadableLabels() {
        assertEquals("Buying", UiArchitecture.humanCampaignPhase(UnivestStateStore.ENTRY_PENDING));
        assertEquals("Holding", UiArchitecture.humanCampaignPhase(UnivestStateStore.ACTIVE));
        assertEquals("Waiting", UiArchitecture.humanCampaignPhase(UnivestStateStore.WAIT_REENTRY));
        assertEquals("Selling", UiArchitecture.humanCampaignPhase(UnivestStateStore.EXITING_OFFICIAL));
        assertEquals("Closed", UiArchitecture.humanCampaignPhase(UnivestStateStore.EXITED));
        assertEquals("Attention", UiArchitecture.humanCampaignPhase(UnivestStateStore.ERROR));
    }
}
