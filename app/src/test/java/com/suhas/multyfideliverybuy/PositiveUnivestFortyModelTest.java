package com.suhas.multyfideliverybuy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Labels are from later official EXITs. All compared features are entry-time only. */
public class PositiveUnivestFortyModelTest {
    private static JSONObject campaign(String symbol, long at, double gross,
                                       String type) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("symbol", symbol);
        entry.put("signalType", type);
        entry.put("source", "OFFICIAL_UNIVEST_ENTRY");
        entry.put("signalAt", at);
        for (int i=0;i<PositiveUnivestFortyModel.FEATURES.length;i++)
            if (i < 16) entry.put(PositiveUnivestFortyModel.FEATURES[i], i + 0.5);
        JSONObject c = new JSONObject();
        c.put("symbol", symbol);
        c.put("upsidePct", gross);
        c.put("exitAt", at + 3600000L);
        c.put("entryProfile", entry);
        return c;
    }

    @Test public void exactFortyDimensionsAreAlwaysPresent() {
        assertEquals(40, PositiveUnivestFortyModel.PARAMETERS);
        assertEquals(40, PositiveUnivestFortyModel.FEATURES.length);
    }

    @Test public void onlyCompletedPositiveOfficialEntriesBecomeTemplates() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(campaign("WIN1", 1000000, .72, "ENTRY"));
        rows.put(campaign("WIN2", 2000000, .64, "ENTRY"));
        rows.put(campaign("LOSER", 3000000, -4.0, "ENTRY"));
        rows.put(campaign("FLAT", 4000000, 0.01, "ENTRY"));
        rows.put(campaign("SELL", 5000000, 9.0, "EXIT"));
        rows.put(campaign("REENTRY", 6000000, 9.0, "REENTRY"));
        rows.put(campaign("WIN1", 1000000, .72, "ENTRY")); // duplicate
        List<JSONObject> winners = PositiveUnivestFortyModel.positiveEntryProfiles(rows);
        assertEquals(2, winners.size());
        assertEquals("WIN1", winners.get(0).optString("symbol"));
        assertEquals("WIN2", winners.get(1).optString("symbol"));
    }

    @Test public void missingInputsDoNotBecomeMatchingEvidence() throws Exception {
        List<JSONObject> winners = new ArrayList<>();
        JSONArray campaigns = new JSONArray();
        campaigns.put(campaign("WIN1", 1000000, 1.4, "ENTRY"));
        winners.addAll(PositiveUnivestFortyModel.positiveEntryProfiles(campaigns));
        JSONObject candidate = new JSONObject();
        for (int i=0;i<11;i++) candidate.put(PositiveUnivestFortyModel.FEATURES[i], i+0.5);
        PositiveUnivestFortyModel.Match tooSparse = PositiveUnivestFortyModel.closest(candidate, winners);
        assertEquals(0, tooSparse.shared);
        assertEquals(0, tooSparse.score, 0.0001);

        candidate.put(PositiveUnivestFortyModel.FEATURES[11], 11.5);
        PositiveUnivestFortyModel.Match observed = PositiveUnivestFortyModel.closest(candidate, winners);
        assertEquals(12, observed.shared);
        assertTrue(observed.score > 0);
        assertFalse("Small sample may not alter ranking", observed.mature);
        PositiveUnivestFortyModel.annotateCandidate(candidate, winners);
        assertEquals(0, candidate.optInt("positive40RankingWeightPct"));
    }

    @Test public void maturityRequiresEightPositiveIndependentCampaignsAcrossFourStocks()
            throws Exception {
        JSONArray campaigns = new JSONArray();
        for (int i=0;i<8;i++) campaigns.put(campaign("WIN"+(i%4), 1000000L + i*100000, 0.75,"ENTRY"));
        List<JSONObject> winners = PositiveUnivestFortyModel.positiveEntryProfiles(campaigns);
        assertEquals(8, winners.size());
        JSONObject candidate = new JSONObject();
        for (int i=0;i<16;i++) candidate.put(PositiveUnivestFortyModel.FEATURES[i], i + 0.5);
        candidate.put("ensembleScore", 80);
        PositiveUnivestFortyModel.Match match = PositiveUnivestFortyModel.closest(candidate, winners);
        assertTrue(match.mature);
        PositiveUnivestFortyModel.annotateCandidate(candidate, winners);
        assertEquals(10,candidate.optInt("positive40RankingWeightPct"));
        assertTrue("Mature measured positive pattern must affect ranking",
                Math.abs(candidate.optDouble("ensembleScore") - 80) >= 0.05);

        JSONArray oneStock = new JSONArray();
        for(int i=0;i<9;i++)oneStock.put(campaign("ONLY", 2000000L+i*100000, 1.0,"ENTRY"));
        assertFalse(PositiveUnivestFortyModel.closest(candidate,
                PositiveUnivestFortyModel.positiveEntryProfiles(oneStock)).mature);
    }
}
