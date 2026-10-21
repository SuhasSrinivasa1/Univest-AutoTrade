package com.suhas.multyfideliverybuy;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalGttTraderTest {
    @Test public void eachLiveOrUncertainGttStatePreventsSecondOrder()throws Exception{
        for(String status:new String[]{"BUY_RESERVED","BUY_GTT_OPEN","BUY_GTT_UNKNOWN",
                "BUY_TRIGGERED_UNCONFIRMED","HOLDING","TRAIL_CREATE_RESERVED",
                "TRAIL_CREATE_UNKNOWN","TRAIL_MODIFY_UNKNOWN","EOD_SELL_RESERVED",
                "EOD_SELL_PENDING","EOD_SELL_UNKNOWN","STOP_TRIGGERED_UNCONFIRMED",
                "UNRESOLVED"}) {
            JSONObject t=new JSONObject();t.put("state",status);
            assertTrue(status,SignalGttTrader.pendingOrHeld(t));
        }
    }
    @Test public void closedAndInactiveCampaignsAreNotOpenHoldings()throws Exception{
        for(String status:new String[]{"CLOSED","REJECTED","ENTRY_INACTIVE",
                "ENTRY_CANCELLED_EOD"}) {
            JSONObject t=new JSONObject();t.put("state",status);
            assertFalse(status,SignalGttTrader.pendingOrHeld(t));
        }
    }
    @Test public void sourceCannotBeMixedWithOfficial(){
        assertEquals("FORECAST",SignalGttTrader.FORECAST);
        assertEquals("FORMULA",SignalGttTrader.FORMULA);
        assertFalse(GrowwClient.STOP_LOSS_ORDERS_ENABLED);
    }
}
