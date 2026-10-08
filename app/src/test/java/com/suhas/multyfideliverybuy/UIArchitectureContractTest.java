package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class UIArchitectureContractTest {
    @Test public void primaryNavigationHasFourDistinctUserJobs() {
        assertArrayEquals(new String[]{"Execution", "Research", "Forecast", "Settings"},
                DashboardActivity.primaryTabLabels());
    }

    @Test public void campaignPhasesUseHumanReadableLabels() {
        assertEquals("Buying", DashboardActivity.humanCampaignPhase(UnivestStateStore.ENTRY_PENDING));
        assertEquals("Holding", DashboardActivity.humanCampaignPhase(UnivestStateStore.ACTIVE));
        assertEquals("Waiting", DashboardActivity.humanCampaignPhase(UnivestStateStore.WAIT_REENTRY));
        assertEquals("Selling", DashboardActivity.humanCampaignPhase(UnivestStateStore.EXITING_OFFICIAL));
        assertEquals("Closed", DashboardActivity.humanCampaignPhase(UnivestStateStore.EXITED));
        assertEquals("Attention", DashboardActivity.humanCampaignPhase(UnivestStateStore.ERROR));
    }
}
