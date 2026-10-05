package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class ResearchOrchestrator {
    static final String STAGE_HOLIDAY = "MARKET_HOLIDAY";
    static final String STAGE_OVERNIGHT = "OVERNIGHT_ENRICHMENT";
    static final String STAGE_PREOPEN = "PREOPEN_FROZEN";
    static final String STAGE_LIVE = "LIVE_MONITOR";
    static final String STAGE_REPLAY = "POST_CLOSE_REPLAY";
    static final String STAGE_EOD = "EOD_FULL_NSE_SCAN";

    private ResearchOrchestrator() {}

    static void tick(Context context) {
        Context c = context.getApplicationContext();
        long now = System.currentTimeMillis();
        String today = NseTradingCalendar.dayKey(now);

        try {
            if (!NseTradingCalendar.isTradingDay(now)) {
                String next = formatDay(NseTradingCalendar.nextTradingDay(now));
                AppPrefs.setResearchOrchestrator(c, STAGE_HOLIDAY,
                        "NSE closed • " + NseTradingCalendar.describe(now)
                                + " • latest frozen forecast retained • next regular session " + next + ".");
                return;
            }

            int minute = NseTradingCalendar.minuteOfDay(now);

            if (minute >= 17 * 60 + 30) {
                if (!today.equals(AppPrefs.getResearchEodScanKey(c))) {
                    AppPrefs.setResearchOrchestrator(c, STAGE_EOD,
                            "Running full eligible NSE CASH EOD scan for the next trading session…");
                    ResearchEngine.runNightly(c);
                    UnivestStrategyStudy.runNightly(c);
                    HistoryBackupManager.forceAutoBackup(c);
                    AppPrefs.setResearchEodScanKey(c, today);
                    String target = NseTradingCalendar.nextTradingDayKey(now);
                    AppPrefs.setResearchForecastTargetKey(c, target);
                    AppPrefs.setResearchOrchestrator(c, STAGE_EOD,
                            "Full NSE EOD scan complete • target session " + target
                                    + " • frozen forecast ready for overnight/pre-open enrichment.");
                } else {
                    AppPrefs.setResearchOrchestrator(c, STAGE_EOD,
                            "Today's full NSE EOD scan is complete • target session "
                                    + AppPrefs.getResearchForecastTargetKey(c) + ".");
                }
                return;
            }

            if (minute >= 15 * 60 + 35) {
                if (!today.equals(AppPrefs.getResearchReplayKey(c))) {
                    AppPrefs.setResearchOrchestrator(c, STAGE_REPLAY,
                            "Running deterministic post-market replay and accuracy/failure attribution…");
                    ResearchTradeEngine.replayAndScore(c);
                    UnivestStrategyStudy.runNightly(c);
                    HistoryBackupManager.forceAutoBackup(c);
                    AppPrefs.setResearchFailureSummary(c, ResearchTradeEngine.failureClustersText(c));
                    AppPrefs.setResearchReplayKey(c, today);
                }
                AppPrefs.setResearchOrchestrator(c, STAGE_REPLAY,
                        "Post-market replay complete • daily accuracy and repeated failure clusters updated.");
                return;
            }

            if (NseTradingCalendar.isRegularMarketOpen(now)) {
                if (today.equals(AppPrefs.getResearchForecastTargetKey(c))
                        && !today.equals(AppPrefs.getResearchPreopenFreezeKey(c))) {
                    preopenFreeze(c, now, today);
                    AppPrefs.setResearchPreopenFreezeKey(c, today);
                    DiagnosticsStore.runtime(c, "RESEARCH_LATE_PREOPEN_FREEZE", "",
                            "Pre-open freeze window was missed; ranking frozen at first live orchestrator tick without re-ranking.");
                }
                AppPrefs.setResearchOrchestrator(c, STAGE_LIVE,
                        "Live Research monitor active • frozen forecast + one-minute path capture + entry/exit evaluation.");
                JSONArray predictions = ResearchStore.predictions(c);
                ResearchEventStore.captureTopCandidates(c, predictions, now, 10);
                ResearchTradeEngine.evaluateLive(c);
                return;
            }

            if (minute >= 8 * 60 + 45 && minute < 9 * 60 + 15) {
                if (today.equals(AppPrefs.getResearchForecastTargetKey(c))
                        && !today.equals(AppPrefs.getResearchPreopenFreezeKey(c))) {
                    preopenFreeze(c, now, today);
                    AppPrefs.setResearchPreopenFreezeKey(c, today);
                }
                AppPrefs.setResearchOrchestrator(c, STAGE_PREOPEN,
                        today.equals(AppPrefs.getResearchPreopenFreezeKey(c))
                                ? "08:45+ pre-open forecast frozen for today's session. Live layer will not rewrite the frozen ranking."
                                : "No forecast targeted to today's session; waiting for the next EOD full-NSE scan.");
                return;
            }

            AppPrefs.setResearchOrchestrator(c, STAGE_OVERNIGHT,
                    "Overnight stage • frozen EOD forecast retained. Pre-open enrichment/freeze begins from 08:45 IST.");
        } catch (Throwable t) {
            AppPrefs.setResearchOrchestrator(c, "ERROR",
                    "Research orchestration error: " + safe(t));
            DiagnosticsStore.error(c, "RESEARCH_ORCHESTRATOR_FAILED", "",
                    "Research orchestration tick failed.", t);
        }
    }

    private static void preopenFreeze(Context c, long now, String sessionKey) {
        JSONArray a = ResearchStore.predictions(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            try {
                String symbol = p.optString("symbol", "");
                if (!symbol.isEmpty()) {
                    try {
                        double ltp = GrowwClient.getLtpForAutomation(c, symbol);
                        if (ltp > 0) p.put("preopenLtp", ltp);
                    } catch (Throwable ignored) {}
                    try {
                        ResearchNewsClient.Summary n = ResearchNewsClient.fetch(
                                p.optString("companyName"), symbol);
                        p.put("nationalNews", n.nationalCount);
                        p.put("internationalNews", n.internationalCount);
                        p.put("positiveCatalysts", n.positiveCatalysts);
                        p.put("negativeCatalysts", n.negativeCatalysts);
                        p.put("newsSignal", n.catalystLabel);
                    } catch (Throwable ignored) {}
                }
                p.put("preopenFreezeAt", now);
                p.put("forecastSessionKey", sessionKey);
                p.put("freezeType", "PREOPEN_08_45_PLUS");
                ResearchDataQuality.annotate(p);
            } catch (Exception ignored) {}
        }
        ResearchStore.savePredictions(c, a);
        ResearchStore.appendForecastSnapshot(c, a, now, "PREOPEN_FROZEN", sessionKey);
        JSONObject snap = new JSONObject();
        try {
            snap.put("sessionKey", sessionKey);
            snap.put("frozenAt", now);
            snap.put("candidateCount", a.length());
            snap.put("rankingImmutableDuringSession", true);
        } catch (Exception ignored) {}
        ResearchEventStore.appendDecisionSnapshot(c, "PREOPEN_FORECAST_FREEZE", snap);
        DiagnosticsStore.runtime(c, "RESEARCH_PREOPEN_FROZEN", "",
                "Pre-open Research forecast frozen for session " + sessionKey + ".");
    }

    static String statusText(Context c) {
        String stage = AppPrefs.getResearchOrchestratorStage(c);
        String status = AppPrefs.getResearchOrchestratorStatus(c);
        return stage + " • " + status;
    }

    private static String formatDay(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("dd MMM yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date(ms));
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
