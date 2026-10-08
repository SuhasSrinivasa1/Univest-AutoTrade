package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONObject;

/** Explicit allow-list for portable NON-SECRET user configuration. */
final class PortableSettings {
    static final String[] EXPORTED_KEYS = {
            "executionMode", "swingEnabled", "multibaggerEnabled",
            "univestBudget", "univestAddBudget", "downwardAveragingEnabled", "downwardAveragingLevels",
            "expectedStaticIp", "intradayBudget", "manualBudget",
            "researchBudget", "researchMaxPositions", "researchCapitalLimit"
    };

    private PortableSettings() {}

    static JSONObject exportJson(Context c) {
        JSONObject j = new JSONObject();
        put(j, "executionMode", AppPrefs.getExecutionMode(c));
        put(j, "swingEnabled", AppPrefs.isSwingEnabled(c));
        put(j, "multibaggerEnabled", AppPrefs.isMultibaggerEnabled(c));
        put(j, "univestBudget", AppPrefs.getUnivestBudget(c));
        put(j, "univestAddBudget", AppPrefs.getUnivestAddBudget(c));
        put(j, "downwardAveragingEnabled", AppPrefs.isAveragingEnabled(c));
        put(j, "downwardAveragingLevels", AppPrefs.getAveragingLevels(c));
        put(j, "expectedStaticIp", AppPrefs.getExpectedStaticIp(c));
        put(j, "intradayBudget", AppPrefs.getIntradayBudget(c));
        put(j, "manualBudget", AppPrefs.getManualBudget(c));
        put(j, "researchBudget", AppPrefs.getResearchBudget(c));
        put(j, "researchMaxPositions", AppPrefs.getResearchMaxPositions(c));
        put(j, "researchCapitalLimit", AppPrefs.getResearchCapitalLimit(c));
        return j;
    }

    static void importJson(Context c, JSONObject j) {
        if (j == null) return;
        if (j.has("executionMode")) AppPrefs.setExecutionMode(c, j.optString("executionMode", AppPrefs.MODE_PAPER));
        if (j.has("swingEnabled")) AppPrefs.setSwingEnabled(c, j.optBoolean("swingEnabled", false));
        if (j.has("multibaggerEnabled")) AppPrefs.setMultibaggerEnabled(c, j.optBoolean("multibaggerEnabled", false));
        if (j.has("univestBudget")) AppPrefs.setUnivestBudget(c, j.optInt("univestBudget", 20000));
        if (j.has("univestAddBudget")) AppPrefs.setUnivestAddBudget(c, j.optInt("univestAddBudget", 5000));
        if (j.has("downwardAveragingEnabled")) AppPrefs.setAveragingEnabled(c, j.optBoolean("downwardAveragingEnabled", true));
        if (j.has("downwardAveragingLevels")) AppPrefs.setAveragingLevels(c, j.optInt("downwardAveragingLevels", 3));
        if (j.has("expectedStaticIp")) AppPrefs.setExpectedStaticIp(c, j.optString("expectedStaticIp", ""));
        if (j.has("intradayBudget")) AppPrefs.setIntradayBudget(c, j.optInt("intradayBudget", 100000));
        if (j.has("manualBudget")) AppPrefs.setManualBudget(c, j.optInt("manualBudget", 50000));
        if (j.has("researchBudget")) AppPrefs.setResearchBudget(c, j.optInt("researchBudget", 5000));
        if (j.has("researchMaxPositions")) AppPrefs.setResearchMaxPositions(c, j.optInt("researchMaxPositions", 2));
        if (j.has("researchCapitalLimit")) AppPrefs.setResearchCapitalLimit(c, j.optInt("researchCapitalLimit", 100000));

        // Restoring configuration must never silently resume live trading. Credentials/readiness are intentionally
        // excluded, and all execution switches remain disarmed until the user explicitly re-authenticates/re-arms.
        AppPrefs.setArmed(c, false);
        AppPrefs.setUnivestEnabled(c, false);
        AppPrefs.setResearchAutoTradeEnabled(c, false);
        AppPrefs.clearAccessToken(c);
    }

    static boolean keyIsCredentialLike(String key) {
        String k = key == null ? "" : key.toLowerCase(java.util.Locale.US);
        return k.contains("apikey") || k.contains("api_key") || k.contains("totp")
                || k.contains("token") || k.contains("password") || k.contains("secret");
    }

    private static void put(JSONObject j, String k, Object v) { try { j.put(k, v); } catch (Exception ignored) {} }
}
