package com.suhas.multyfideliverybuy;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class SameDayForecastPolicyTest {
    private static long ist(int h,int m) {
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.clear();c.set(2026,Calendar.OCTOBER,21,h,m);return c.getTimeInMillis();
    }
    @Test public void fixedStepHasTenLevelsEndingAtMinusFive() {
        assertEquals("UNIVEST_DAY_V1_20261021",SameDayForecastPolicy.VERSION);
        assertEquals(.5,SameDayForecastPolicy.AVERAGE_STEP_PCT,0.00001);
        assertEquals(10,SameDayForecastPolicy.AVERAGE_LEVELS);
        assertEquals(99.5,SameDayForecastPolicy.averagingTrigger(100,1),1e-9);
        assertEquals(95,SameDayForecastPolicy.averagingTrigger(100,10),1e-9);
        assertTrue(Double.isNaN(SameDayForecastPolicy.averagingTrigger(100,11)));
    }
    @Test public void lateEntriesAreForbiddenAndClosingIsBeforeMarketShut() {
        assertTrue(SameDayForecastPolicy.entryAllowed(ist(13,59)));
        assertFalse(SameDayForecastPolicy.entryAllowed(ist(14,0)));
        assertTrue(SameDayForecastPolicy.closingWindow(ist(15,10)));
        assertTrue(SameDayForecastPolicy.closingWindow(ist(15,29)));
        assertFalse(SameDayForecastPolicy.closingWindow(ist(15,30)));
    }
    @Test public void weakParticipationDoesNotGetAnEntryInEveryRegime() {
        assertFalse(SameDayForecastPolicy.evaluate(.3,63,.4,101,100,.12,1.5,1.5,
                "RANGE_MIXED",Double.NaN).admissible);
        assertTrue(SameDayForecastPolicy.evaluate(.75,65,1.7,101,100,.12,1.5,1.7,
                "RISK_OFF",.15).admissible);
        assertFalse(SameDayForecastPolicy.evaluate(.08,65,1.0,101,100,.12,1.5,1.7,
                "RISK_OFF",.50).admissible);
    }
}
