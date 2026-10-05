package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BudgetConfigTest {
    @Test public void normalizesBudgetIntoSupportedRangeAndStep() {
        assertEquals(0, AppPrefs.normalizeUnivestBudget(-1));
        assertEquals(0, AppPrefs.normalizeUnivestBudget(0));
        assertEquals(5000, AppPrefs.normalizeUnivestBudget(5000));
        assertEquals(10000, AppPrefs.normalizeUnivestBudget(10499));
        assertEquals(11000, AppPrefs.normalizeUnivestBudget(10500));
        assertEquals(100000, AppPrefs.normalizeUnivestBudget(99999));
        assertEquals(100000, AppPrefs.normalizeUnivestBudget(250000));
    }

    @Test public void backInRangeUsesInitialWhenFlatAndSharedAddWhenHeld() {
        assertEquals(75000, UnivestManager.chooseBackInRangeBudget(0, false, 75000, 12000));
        assertEquals(12000, UnivestManager.chooseBackInRangeBudget(3, false, 75000, 12000));
        assertEquals(0, UnivestManager.chooseBackInRangeBudget(0, true, 75000, 12000));
        assertEquals(0, UnivestManager.chooseBackInRangeBudget(3, false, 75000, 0));
    }
}
