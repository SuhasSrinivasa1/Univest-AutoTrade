package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DiagnosticsStore {
    private static final String DIR = "univest_diagnostics";
    private static final Object LOCK = new Object();
    private static final String[] STREAMS = {"notifications", "trades", "errors", "broker", "runtime"};

    private DiagnosticsStore() {}

    static void notification(Context c, String packageName, String raw) {
        notification(c, packageName, raw, null);
    }

    static void notification(Context c, String packageName, String raw, UnivestParser.Signal signal) {
        JSONObject d = new JSONObject();
        put(d, "package", packageName); put(d, "raw", raw);
        put(d, "isTradingSignal", signal != null);
        if (signal != null) { put(d, "signalType", signal.type.name()); put(d, "parsedSymbol", signal.symbol); }
        append(c, "notifications", "NOTIFICATION_RECEIVED", signal == null ? "" : signal.symbol, d);
    }

    static void runtime(Context c, String event, String symbol, String message) {
        JSONObject d = new JSONObject(); put(d, "message", message); append(c, "runtime", event, symbol, d);
    }

    static void trade(Context c, String event, String symbol, String message, GrowwClient.ExecutionResult r) {
        JSONObject d = new JSONObject(); put(d, "message", message);
        if (r != null) {
            put(d, "submitted", r.submitted); put(d, "filled", r.filled); put(d, "unknown", r.unknown);
            put(d, "orderId", r.orderId); put(d, "requestedQuantity", r.requestedQuantity);
            put(d, "filledQuantity", r.filledQuantity); put(d, "averagePrice", r.averagePrice);
            put(d, "sizingLtp", r.sizingLtp); put(d, "dispatchAtMillis", r.dispatchAtMillis);
            put(d, "brokerMessage", r.message);
        }
        append(c, "trades", event, symbol, d);
    }

    static void paperTrade(Context c, String event, String symbol, String message) {
        JSONObject d = new JSONObject(); put(d, "message", message); put(d, "paper", true);
        append(c, "trades", event, symbol, d);
    }

    static void broker(Context c, String event, String symbol, boolean ok, String message) {
        JSONObject d = new JSONObject(); put(d, "ok", ok); put(d, "message", message); append(c, "broker", event, symbol, d);
    }

    static void error(Context c, String event, String symbol, String message, Throwable t) {
        JSONObject d = new JSONObject(); put(d, "message", message);
        if (t != null) { put(d, "exception", t.getClass().getName()); put(d, "exceptionMessage", t.getMessage()); }
        append(c, "errors", event, symbol, d);
    }

    private static void append(Context c, String stream, String event, String symbol, JSONObject detail) {
        synchronized (LOCK) {
            try {
                File dir = dir(c); if (!dir.exists()) dir.mkdirs();
                long now = System.currentTimeMillis();
                JSONObject row = new JSONObject(); row.put("timestamp", iso(now)); row.put("timestampMillis", now);
                row.put("stream", stream); row.put("event", event); row.put("symbol", symbol == null ? "" : symbol);
                row.put("detail", detail == null ? new JSONObject() : detail);
                // v2.1 writes one file per IST date so the operational UI automatically starts fresh each day.
                File f = dailyFile(c, stream, now);
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(row.toString()); w.write('\n');
                }
                UnivestHistoryDb.recordDiagnostic(c, now, stream, event, symbol, detail);
                HistoryBackupManager.scheduleAutoBackup(c);
            } catch (Exception ignored) {}
        }
    }

    static String recent(Context c, int maxLines) { return recentStream(c, "runtime", maxLines, false); }
    static String todayTrades(Context c, int maxLines) { return recentStream(c, "trades", maxLines, false); }
    static String todayErrors(Context c, int maxLines) { return recentStream(c, "errors", maxLines, false); }
    static String todayTradingSignals(Context c, int maxLines) { return recentStream(c, "notifications", maxLines, true); }

    static int todayNotificationCount(Context c) {
        File f = dailyFile(c, "notifications", System.currentTimeMillis()); if (!f.exists()) return 0;
        int count = 0; try (BufferedReader br = new BufferedReader(new FileReader(f))) { while (br.readLine() != null) count++; }
        catch (Exception ignored) {} return count;
    }

    private static String recentStream(Context c, String stream, int maxLines, boolean signalsOnly) {
        ArrayDeque<String> q = new ArrayDeque<>(); File f = dailyFile(c, stream, System.currentTimeMillis());
        if (!f.exists()) return emptyLabel(stream, signalsOnly);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (signalsOnly) {
                    try {
                        JSONObject j = new JSONObject(line); JSONObject d = j.optJSONObject("detail");
                        if (d == null || !d.optBoolean("isTradingSignal", false)) continue;
                    } catch (Exception ignored) { continue; }
                }
                q.addLast(line); while (q.size() > maxLines) q.removeFirst();
            }
        } catch (Exception e) { return "Unable to read diagnostics: " + e.getMessage(); }
        StringBuilder out = new StringBuilder();
        for (String line : q) {
            try {
                JSONObject j = new JSONObject(line); JSONObject d = j.optJSONObject("detail");
                if (out.length() > 0) out.append("\n\n");
                out.append(j.optString("timestamp")).append("  ").append(j.optString("event"));
                String sym = j.optString("symbol"); if (!sym.isEmpty()) out.append(" • ").append(sym);
                if (d != null) {
                    String msg = d.optString("message");
                    if (msg.isEmpty()) msg = d.optString("raw");
                    if (!msg.isEmpty()) out.append("\n").append(msg);
                }
            } catch (Exception ignored) {}
        }
        return out.length() == 0 ? emptyLabel(stream, signalsOnly) : out.toString();
    }

    private static String emptyLabel(String stream, boolean signalsOnly) {
        if (signalsOnly) return "No trading-signal notifications recorded today.";
        if ("trades".equals(stream)) return "No trade events recorded today.";
        if ("errors".equals(stream)) return "No errors recorded today.";
        return "No runtime events recorded today.";
    }

    static File createExport(Context c) throws Exception {
        synchronized (LOCK) {
            File out = new File(c.getCacheDir(), "Univest-Debug-" + fileStamp() + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
                File d = dir(c); File[] files = d.listFiles();
                if (files != null) {
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    for (File f : files) if (f.isFile() && f.getName().endsWith(".jsonl")) addFile(zip, f, "logs/" + f.getName());
                }
                File research = new File(c.getFilesDir(), "research_lab");
                addDirectory(zip, research, "research_lab/");
                File durable = new File(c.getFilesDir(), "official-signal-queue.json");
                if (durable.exists()) addFile(zip, durable, "official/official-signal-queue.json");
                File historyDb = UnivestHistoryDb.databaseFile(c);
                if (historyDb.exists()) addFile(zip, historyDb, "history/univest_history.db");
                addText(zip, "history/event-ledger.json", UnivestHistoryDb.exportJson(c).toString());
                addText(zip, "snapshot.json", snapshot(c).toString(2));
                addText(zip, "README.txt",
                        "Univest AutoTrade diagnostic export\n" +
                        "v2.9.0 stores diagnostics in daily IST files. The app UI shows only today's trading signals/trades/errors.\n" +
                        "Historical notification/runtime/broker logs remain in this export for debugging.\n" +
                        "Groww TOTP token, TOTP secret, generated OTP and access token are never exported.\n");
            }
            return out;
        }
    }

    private static JSONObject snapshot(Context c) {
        JSONObject j = new JSONObject();
        try {
            j.put("app", "Univest AutoTrade"); j.put("version", "2.9.0"); j.put("versionCode", 290);
            j.put("sourcePackageLock", "com.univest.capp"); j.put("productLock", "CNC DELIVERY ONLY");
            j.put("executionMode", AppPrefs.getExecutionMode(c)); j.put("entryBudget", AppPrefs.getUnivestBudget(c));
            j.put("reentryBudget", AppPrefs.getUnivestAddBudget(c)); j.put("downwardAverageBudget", AppPrefs.getAveragingBudget(c));
            j.put("downwardAverageStepPct", AppPrefs.getAveragingStepPct(c)); j.put("downwardAverageLevels", AppPrefs.getAveragingLevels(c));
            j.put("downwardAveragingEnabled", AppPrefs.isAveragingEnabled(c)); j.put("enabled", AppPrefs.isUnivestEnabled(c));
            j.put("growwReady", AppPrefs.isReadyForBuy(c)); j.put("staticIpMatch", AppPrefs.isStaticIpMatch(c));
            j.put("lastDetectedIp", AppPrefs.getLastDetectedIp(c)); j.put("lastStatus", AppPrefs.getUnivestStatus(c));
            j.put("todayNotificationCount", todayNotificationCount(c));
            j.put("durableOfficialPending", DurableOfficialSignalQueue.pendingCount(c));
            j.put("durableOfficialStatus", DurableOfficialSignalQueue.statusText(c));
            j.put("notificationListenerState", AppPrefs.getNotificationListenerState(c));
            j.put("notificationListenerHeartbeat", AppPrefs.getNotificationListenerHeartbeat(c));
            j.put("preMarketReady", AppPrefs.isPreMarketReady(c));
            j.put("preMarketStatus", AppPrefs.getPreMarketReadinessStatus(c));
            j.put("historyLedgerEvents", UnivestHistoryDb.count(c));
            j.put("portableHistoryBackupConnected", HistoryBackupManager.isConnected(c));
            j.put("portableHistoryBackupStatus", AppPrefs.getHistoryBackupStatus(c));
            j.put("researchAutoTradeEnabled", AppPrefs.isResearchAutoTradeEnabled(c));
            j.put("researchCapitalLimit", AppPrefs.getResearchCapitalLimit(c));
            j.put("researchCommittedCapital", ResearchTradeEngine.committedCapital(c));
            j.put("researchMaxPositions", AppPrefs.getResearchMaxPositions(c));
            j.put("credentialStorage", "ANDROID_KEYSTORE_AES_GCM");
            j.put("chargeModel", DeliveryNetTarget.CHARGE_MODEL_VERSION);
            j.put("researchOrchestratorStage", AppPrefs.getResearchOrchestratorStage(c));
            j.put("researchOrchestratorStatus", AppPrefs.getResearchOrchestratorStatus(c));
            j.put("researchForecastTargetKey", AppPrefs.getResearchForecastTargetKey(c));
            j.put("researchAccuracy", ResearchTradeEngine.accuracyText(c));
            j.put("researchFailureClusters", ResearchTradeEngine.failureClustersText(c));
            j.put("researchPlaybooks", ResearchStore.playbookRegistry(c));
            j.put("preUnivestAccountability", ResearchPlaybookEngine.accountabilityText(c));
            JSONArray states = new JSONArray(); for (UnivestStateStore.State s : UnivestStateStore.all(c)) states.put(s.toJson());
            j.put("states", states);
        } catch (Exception ignored) {}
        return j;
    }

    static void clear(Context c) {
        synchronized (LOCK) { File d = dir(c); File[] fs = d.listFiles(); if (fs != null) for (File f : fs) if (f.isFile()) f.delete(); }
    }

    private static void addDirectory(ZipOutputStream zip, File dir, String prefix) throws Exception {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (f.isDirectory()) addDirectory(zip, f, prefix + f.getName() + "/");
            else if (f.isFile()) addFile(zip, f, prefix + f.getName());
        }
    }

    private static File dir(Context c) { return new File(c.getFilesDir(), DIR); }
    private static File dailyFile(Context c, String stream, long ms) { return new File(dir(c), stream + "-" + AppPrefs.istDayKey(ms) + ".jsonl"); }
    private static void addFile(ZipOutputStream zip, File f, String name) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) zip.write(b, 0, n);
        } zip.closeEntry();
    }
    private static void addText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static void put(JSONObject j, String k, Object v) { try { j.put(k, v == null ? JSONObject.NULL : v); } catch (Exception ignored) {} }
    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US); f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata")); return f.format(new Date(ms));
    }
    private static String fileStamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US); f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata")); return f.format(new Date());
    }
}
