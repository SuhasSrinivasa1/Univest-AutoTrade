package com.suhas.multyfideliverybuy;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ManualTradeTest {
    @Test public void longTargetRoundsUpToTick() {
        assertEquals(101.05, GrowwClient.roundTarget(101.001, 0.05, true), 0.0001);
    }

    @Test public void shortTargetRoundsDownToTick() {
        assertEquals(98.95, GrowwClient.roundTarget(98.999, 0.05, false), 0.0001);
    }

    @Test public void resolvesSymbolFromAutocompleteDisplay() {
        InstrumentRepository.Instrument r = new InstrumentRepository.Instrument("RELIANCE", "Reliance Industries", 0.05, true, true);
        InstrumentRepository.Instrument i = InstrumentRepository.resolve(Arrays.asList(r), "RELIANCE — Reliance Industries");
        assertNotNull(i);
        assertEquals("RELIANCE", i.symbol);
    }
}
