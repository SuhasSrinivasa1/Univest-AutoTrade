package com.suhas.multyfideliverybuy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForecastAverageIsolationTest {
    @Test public void officialReturnsOpenSignalsAndDuplicatesDoNotChangeForecastAverage() throws Exception {
        long now = 1_800_000_000_000L;
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("recommendationKey", "f1").put("state", "CLOSED")
                .put("forecastCallAt", now - 10000).put("exitAt", now - 1000).put("netPct", 1.2));
        rows.put(new JSONObject().put("recommendationKey", "f2").put("state", "CLOSED")
                .put("forecastCallAt", now - 9000).put("exitAt", now - 800).put("netPct", -0.8));
        rows.put(new JSONObject().put("recommendationKey", "official")
                .put("state", "CLOSED").put("forecastCallAt", 0)
                .put("exitAt", now - 500).put("netPct", 30.0));
        rows.put(new JSONObject().put("recommendationKey", "open").put("state", "SHADOW_OPEN")
                .put("forecastCallAt", now - 100).put("netPct", 50.0));
        rows.put(new JSONObject().put("recommendationKey", "f1").put("state", "SHADOW_OPEN")
                .put("forecastCallAt", now - 15000).put("netPct", 1.2));
        assertEquals(2, ResearchTradeEngine.rollingClosedCount(rows, now, 30));
        assertEquals(0.2, ResearchTradeEngine.rollingAverageClosedUpsidePct(rows, now, 30), 0.00001);
    }
}
