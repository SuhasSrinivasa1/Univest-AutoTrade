package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallParserTest {
    @Test public void parsesIntradaySample() {
        String s = "Multyfi\n✅ Released: Equity Intraday Trade\n🔶 Stock Name: TBOTEK\n🔶 Target: 1760\n🔶 Entry Range: 1701.1-1703.1\n🔶 Stop Loss: 1675";
        CallParser.Call c = CallParser.parse(s);
        assertNotNull(c);
        assertEquals(CallParser.Category.INTRADAY, c.category);
        assertEquals("TBOTEK", c.symbol);
        assertEquals(1703.1, c.referencePrice, 0.0001);
    }

    @Test public void ignoresExitUpdateEvenIfIntradayWordExists() {
        String s = "Multyfi\nEquity Intraday Trade\nStock Name: TBOTEK\nBook Profits and close early";
        assertNull(CallParser.parse(s));
    }

    @Test public void parsesSwing() {
        String s = "Multyfi\nReleased: Swing Trade\nStock Name: ABC\nBuy Range: 500-505";
        CallParser.Call c = CallParser.parse(s);
        assertNotNull(c);
        assertEquals(CallParser.Category.SWING, c.category);
        assertEquals(505.0, c.referencePrice, 0.0001);
    }

    @Test public void parsesMultibagger() {
        String s = "Multyfi\nReleased: Multibagger Trade\nStock Name: XYZ\nEntry Price: 250.50";
        CallParser.Call c = CallParser.parse(s);
        assertNotNull(c);
        assertEquals(CallParser.Category.MULTIBAGGER, c.category);
        assertEquals(250.50, c.referencePrice, 0.0001);
    }
}
