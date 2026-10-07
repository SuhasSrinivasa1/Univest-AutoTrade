package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnivestStrategyContractTest {
    @Test public void fixedBudgetsAndAveragingAreLocked() {
        assertEquals(20000, UnivestManager.ENTRY_BUDGET);
        assertEquals(5000, UnivestManager.REENTRY_BUDGET);
        assertEquals(5000, UnivestManager.AVERAGE_BUDGET);
        assertEquals(2.0, UnivestManager.AVERAGE_STEP_PCT, 0.0001);
        assertEquals(3, UnivestManager.MAX_AVERAGE_LEVELS);
    }
    @Test public void exitedSymbolCanStartNewRecommendationCycle() {
        assertTrue(UnivestStateStore.canReservePhase(UnivestStateStore.EXITED));
        assertFalse(UnivestStateStore.canReservePhase(UnivestStateStore.ACTIVE));
        assertTrue(UnivestStateStore.canExecuteReservedPhase(UnivestStateStore.ENTRY_PENDING));
    }
    @Test public void freshNewEquityIgnoresExistingHoldingButNotOpenBrokerBuy() {
        assertTrue(UnivestManager.freshEntryMayProceed(0, false));
        assertTrue(UnivestManager.freshEntryMayProceed(25, false));
        assertFalse(UnivestManager.freshEntryMayProceed(0, true));
        assertFalse(UnivestManager.freshEntryMayProceed(25, true));
    }
    @Test public void companyNameNormalizationRemovesLegalSuffixes() {
        assertEquals("TATA MOTORS", InstrumentRepository.normalizedName("Tata Motors Limited"));
        assertEquals("TATA MOTORS", InstrumentRepository.normalizedName("TATA MOTORS LTD."));
    }
    @Test public void sourcePackageIsStrictlyUnivest() {
        assertEquals("com.univest.capp", MultyfiNotificationService.UNIVEST_PACKAGE);
    }
    @Test public void stableReferencesMeetGrowwLengthContractAndRepeatDeterministically() {
        String a = UnivestManager.stableRef("UE", "SENORES", "same alert", 1727668865000L);
        String b = UnivestManager.stableRef("UE", "SENORES", "same alert", 1727668865000L);
        assertEquals(a, b);
        assertTrue(a.length() >= 8 && a.length() <= 20);
        assertTrue(a.matches("[A-Z0-9]+"));
    }
    @Test public void totpBase32ValidationRejectsObviouslyBadSecrets() {
        assertTrue(GrowwClient.isValidBase32("JBSWY3DPEHPK3PXP"));
        assertFalse(GrowwClient.isValidBase32("not-valid-***"));
        assertFalse(GrowwClient.isValidBase32(""));
    }
    @Test public void backInRangeUsesBrokerTruthForBudget() {
        assertEquals(20000, UnivestManager.budgetForBackInRange(0, false));
        assertEquals(5000, UnivestManager.budgetForBackInRange(10, false));
        assertEquals(0, UnivestManager.budgetForBackInRange(0, true));
        assertEquals(0, UnivestManager.budgetForBackInRange(10, true));
    }
    @Test public void parserRejectsNonEquityUnivestContent() {
        assertNull(UnivestParser.parse("Stock: NIFTY\nIndex update\nBook Profit"));
        assertNull(UnivestParser.parse("Stock: GOLD\nCommodity\nNew recommendation\nDuration: 1 month"));
        assertNull(UnivestParser.parse("Stock: ABC\nPromotional offer\nNew recommendation\nDuration: 1 month"));
    }
}
