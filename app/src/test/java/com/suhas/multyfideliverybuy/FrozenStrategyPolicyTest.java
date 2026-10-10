package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class FrozenStrategyPolicyTest {
    @Test public void genericStrategyFreezesOnlyAfterIndependentEvidence() {
        assertTrue(ResearchPlaybookEngine.freezeEligible(30, 12, 6, 8, 72.0));
        assertFalse(ResearchPlaybookEngine.freezeEligible(29, 12, 6, 8, 90.0));
        assertFalse(ResearchPlaybookEngine.freezeEligible(30, 11, 6, 8, 90.0));
        assertFalse(ResearchPlaybookEngine.freezeEligible(30, 12, 5, 8, 90.0));
        assertFalse(ResearchPlaybookEngine.freezeEligible(30, 12, 6, 7, 90.0));
        assertFalse(ResearchPlaybookEngine.freezeEligible(30, 12, 6, 8, 71.9));
    }

    @Test public void freezeAlsoRequiresFastPositiveOfficialOutcomes() {
        assertTrue(ResearchPlaybookEngine.freezeOutcomeEligible(8, 1.6, 70.0, 1.8));
        assertFalse(ResearchPlaybookEngine.freezeOutcomeEligible(7, 2.0, 80.0, 1.8));
        assertFalse(ResearchPlaybookEngine.freezeOutcomeEligible(8, 1.0, 80.0, 1.8));
        assertFalse(ResearchPlaybookEngine.freezeOutcomeEligible(8, 2.0, 59.9, 1.8));
    }

    @Test public void frozenGenericTargetIsBoundedAndResearchDailyCallsAreCapped() {
        assertEquals(10, ResearchPlaybookEngine.FROZEN_TARGET);
        assertEquals(5, ResearchPlaybookEngine.MIN_FROZEN_FOR_STABLE_DECISIONS);
        assertEquals(5, ResearchTradeEngine.DAILY_RECOMMENDATION_CAP);
        assertEquals(2, UnivestBenchmark.PRIMARY_MAX_SESSIONS);
    }
}
