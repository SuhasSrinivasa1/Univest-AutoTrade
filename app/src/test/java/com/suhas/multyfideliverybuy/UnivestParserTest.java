package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnivestParserTest {
    @Test public void parsesEligibleNewEntry() {
        UnivestParser.Signal s = UnivestParser.parse("New Advisory Pick\nSTOCK: GRAPHITE\nDuration: 1-3 Months");
        assertNotNull(s); assertEquals(UnivestParser.Type.ENTRY, s.type); assertEquals("GRAPHITE", s.symbol);
    }
    @Test public void rejectsOverThreeMonths() {
        assertNull(UnivestParser.parse("New Advisory Pick\nSTOCK: ABC\nDuration: 6 Months"));
        assertNull(UnivestParser.parse("Stock Recommendation\nStock: ABC\nDuration: 3-6 Months"));
    }
    @Test public void parsesBookProfitWithoutDurationGate() {
        UnivestParser.Signal s = UnivestParser.parse("Equity Profit of the Day\nStock: GVPIL\nDuration: 2 Days\nBook Profit for the day");
        assertNotNull(s); assertEquals(UnivestParser.Type.EXIT, s.type); assertEquals("GVPIL", s.symbol);
    }
    @Test public void parsesOfficialDailyProfitAlertsWithAdvisoryPickTicker() {
        UnivestParser.Signal first = UnivestParser.parse(
                "1st Equity Profit of the Day\nAdvisory Pick: URBANCO\n🕓 Duration: 2 Days");
        assertNotNull(first);
        assertEquals(UnivestParser.Type.EXIT, first.type);
        assertEquals("URBANCO", first.symbol);

        UnivestParser.Signal second = UnivestParser.parse(
                "2nd Equity Profit of the Day\nAdvisory Pick: BALRAMCHIN\n🕓 Duration: 6 Days");
        assertNotNull(second);
        assertEquals(UnivestParser.Type.EXIT, second.type);
        assertEquals("BALRAMCHIN", second.symbol);
    }

    @Test public void advisoryPickWithoutExplicitProfitOrEntryNeverSells() {
        assertNull(UnivestParser.parse("Advisory Pick: URBANCO\nDuration: 2 Days"));
        assertNull(UnivestParser.parse("1st Equity Profit of the Day\nDuration: 2 Days"));
        assertNull(UnivestParser.parse("Options\nEquity Profit of the Day\nAdvisory Pick: NIFTY27OCT22000CE"));
    }

    @Test public void parsesBackInRangeVariants() {
        String[] messages = {
                "Stock: ABC\nEquity back in buying range",
                "Stock: ABC\nStock back in ideal range",
                "Stock: ABC\nIdeal buy price back",
                "Stock: ABC\nBack in Entry Range",
                "Stock: ABC\nBudget back"
        };
        for (String m : messages) { UnivestParser.Signal s=UnivestParser.parse(m); assertNotNull(m,s); assertEquals(UnivestParser.Type.REENTRY,s.type); }
    }
    @Test public void companyNameCanBeCaptured() {
        UnivestParser.Signal s = UnivestParser.parse("New equity recommendation\nStock Name: Tata Motors Limited\nDuration: 2 Months");
        assertNotNull(s); assertEquals("Tata Motors Limited", s.symbol);
    }
    @Test public void optionsAndCommoditiesStayBlocked() {
        assertNull(UnivestParser.parse("Options\nNew Trade\nStock: NIFTY25SEP24000CE\nDuration: 1 Month"));
        assertNull(UnivestParser.parse("Commodity\nNew Advisory Pick\nStock: GOLDM\nDuration: 1 Month"));
    }
    @Test public void normalizesBeSuffix() {
        UnivestParser.Signal s=UnivestParser.parse("New Advisory Pick\nSTOCK: HFCL-BE\nDuration: 1-3 Months");
        assertNotNull(s); assertEquals("HFCL",s.symbol);
    }
}
