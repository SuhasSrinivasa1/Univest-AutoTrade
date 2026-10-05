package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class MultyfiExitParserTest {
    @Test public void parsesClosingEarly() {
        MultyfiExitParser.Signal s=MultyfiExitParser.parse("Multyfi Equity Intraday\nStock Name: ABC\nBook Profits and close early");
        assertNotNull(s); assertEquals("ABC",s.symbol);
    }
    @Test public void parsesSellNow() {
        MultyfiExitParser.Signal s=MultyfiExitParser.parse("Multyfi\nStock: XYZ\nSell immediately");
        assertNotNull(s); assertEquals("XYZ",s.symbol);
    }
    @Test public void doesNotTreatReleaseAsExit() {
        assertNull(MultyfiExitParser.parse("Multyfi Released: Equity Intraday Trade\nStock Name: ABC\nEntry Range: 100-101"));
    }
}
