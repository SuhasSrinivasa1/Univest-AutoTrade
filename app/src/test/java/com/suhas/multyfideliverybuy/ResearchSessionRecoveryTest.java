package com.suhas.multyfideliverybuy;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ResearchSessionRecoveryTest {
    private static long ist(int year,int month,int day,int h,int m){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.clear();c.set(year,month-1,day,h,m);
        return c.getTimeInMillis();
    }

    @Test public void cleanInstallCanRecoverBeforeAndDuringMarketButNotTooLate(){
        assertFalse(ResearchSessionRecovery.eligibleTime(ist(2026,10,22,7,14)));
        assertTrue(ResearchSessionRecovery.eligibleTime(ist(2026,10,22,7,15)));
        assertTrue(ResearchSessionRecovery.eligibleTime(ist(2026,10,22,8,55)));
        assertTrue(ResearchSessionRecovery.eligibleTime(ist(2026,10,22,10,31)));
        assertFalse(ResearchSessionRecovery.eligibleTime(ist(2026,10,22,14,0)));
        assertFalse(ResearchSessionRecovery.eligibleTime(ist(2026,10,24,8,55)));
        assertFalse(ResearchSessionRecovery.eligibleTime(ist(2026,10,20,8,55))); // NSE holiday
    }

    @Test public void recoveryCandleCutoffIsBeginningOfTargetSession(){
        long actual=ResearchSessionRecovery.startOfSession(ist(2026,10,22,12,37));
        assertEquals(ist(2026,10,22,0,0),actual);
        assertTrue(ist(2026,10,21,15,30)<actual);
        assertFalse(ist(2026,10,22,0,0)<actual); // exclude boundary candle
    }
}
