package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Crash/restart-durable handoff for already-validated official Univest signals.
 *
 * Important: this is NOT a similarity/notification-history execution gate. Each distinct Android
 * notification postTime remains an independent event. The queue only guarantees that one concrete
 * notification survives OEM process/service recycling until UnivestManager.handle() actually runs.
 *
 * If Android kills the process after a broker request but before COMPLETE is persisted, replay is
 * intentionally safe because UnivestManager reconciles broker holdings/open orders and uses stable
 * Groww reference IDs before any new official order.
 */
final class DurableOfficialSignalQueue {
    static final String RECEIVED = "RECEIVED";
    static final String RUNNING = "RUNNING";
    static final String COMPLETE = "COMPLETE";
    static final String RETRYABLE = "RETRYABLE";
    static final String CANCELLED = "CANCELLED";

    private static final String FILE_NAME = "official-signal-queue.json";
    private static final Object FILE_LOCK = new Object();
    private static final Object IDLE_LOCK = new Object();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final PerSymbolSerialExecutor EXECUTOR = new PerSymbolSerialExecutor(4);
    private static final long COMPLETE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_RECORDS = 300;

    private DurableOfficialSignalQueue() {}

    static String enqueueAndDispatch(Context context, UnivestParser.Signal signal, long postTime) {
        if (signal == null) return "";
        Context c = context.getApplicationContext();
        long effectivePost = postTime > 0 ? postTime : System.currentTimeMillis();
        String id = eventIdFor(signal.type.name(), signal.symbol, signal.rawText, effectivePost);
        synchronized (FILE_LOCK) {
            List<JSONObject> all = loadLocked(c);
            JSONObject existing = findById(all, id);
            if (existing == null) {
                JSONObject row = new JSONObject();
                try {
                    row.put("id", id);
                    row.put("type", signal.type.name());
                    row.put("symbol", cleanSymbol(signal.symbol));
                    row.put("rawText", signal.rawText == null ? "" : signal.rawText);
                    row.put("postTime", effectivePost);
                    row.put("receivedAt", System.currentTimeMillis());
                    row.put("updatedAt", System.currentTimeMillis());
                    row.put("state", RECEIVED);
                    row.put("attempts", 0);
                    row.put("lastError", "");
                    all.add(row);
                    saveLocked(c, compact(all));
                    DiagnosticsStore.runtime(c, "OFFICIAL_SIGNAL_DURABLY_QUEUED", signal.symbol,
                            signal.type + " persisted before broker execution • queue id " + id + ".");
                } catch (Exception e) {
                    DiagnosticsStore.error(c, "OFFICIAL_SIGNAL_QUEUE_WRITE_FAILED", signal.symbol,
                            "Could not durably persist official signal before dispatch.", e);
                    return "";
                }
            }
        }
        OfficialSignalRecoveryScheduler.scheduleNow(c);
        dispatchId(c, id);
        return id;
    }

    static void recoverPending(Context context) {
        Context c = context.getApplicationContext();
        List<JSONObject> pending;
        synchronized (FILE_LOCK) {
            pending = pendingLocked(c);
        }
        // Submission order is chronological. PerSymbolSerialExecutor preserves that order per stock
        // while still allowing unrelated symbols to proceed concurrently.
        Collections.sort(pending, Comparator.comparingLong(o -> o.optLong("postTime", o.optLong("receivedAt", 0L))));
        for (JSONObject row : pending) dispatchId(c, row.optString("id"));
    }

    static int pendingCount(Context context) {
        synchronized (FILE_LOCK) { return pendingLocked(context.getApplicationContext()).size(); }
    }

    static String statusText(Context context) {
        Context c = context.getApplicationContext();
        synchronized (FILE_LOCK) {
            List<JSONObject> all = loadLocked(c);
            int pending = 0;
            JSONObject latest = null;
            for (JSONObject row : all) {
                String state = row.optString("state");
                if (!COMPLETE.equals(state) && !CANCELLED.equals(state)) pending++;
                if (latest == null || row.optLong("updatedAt", 0L) > latest.optLong("updatedAt", 0L)) latest = row;
            }
            if (latest == null) return "No durable official events recorded yet.";
            return "Pending " + pending + " • last " + latest.optString("type") + " "
                    + latest.optString("symbol") + " • " + latest.optString("state")
                    + " • attempts " + latest.optInt("attempts", 0);
        }
    }

    static boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (IDLE_LOCK) {
            while (!IN_FLIGHT.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) return false;
                try { IDLE_LOCK.wait(Math.min(left, 1000L)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            return true;
        }
    }

    static String eventIdFor(String type, String symbol, String rawText, long postTime) {
        String raw = clean(type) + "|" + cleanSymbol(symbol) + "|" + postTime + "|" + (rawText == null ? "" : rawText);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("UVQ");
            for (int i = 0; i < 10; i++) b.append(String.format(Locale.US, "%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return "UVQ" + Integer.toHexString(raw.hashCode()) + Long.toHexString(postTime);
        }
    }

    private static void dispatchId(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        final String symbol;
        synchronized (FILE_LOCK) {
            JSONObject row = findById(loadLocked(c), id);
            if (row == null || isTerminal(row.optString("state"))) return;
            synchronized (IDLE_LOCK) {
                if (!IN_FLIGHT.add(id)) return;
            }
            symbol = row.optString("symbol", "");
        }

        EXECUTOR.execute(symbol, () -> processOne(c, id));
    }

    private static void processOne(Context c, String id) {
        UnivestParser.Signal signal = null;
        long postTime = 0L;
        try {
            synchronized (FILE_LOCK) {
                List<JSONObject> all = loadLocked(c);
                JSONObject row = findById(all, id);
                if (row == null || isTerminal(row.optString("state"))) return;
                String type = row.optString("type", "");
                UnivestParser.Type parsedType = UnivestParser.Type.valueOf(type);
                signal = new UnivestParser.Signal(parsedType, row.optString("symbol", ""), row.optString("rawText", ""));
                postTime = row.optLong("postTime", 0L);
                row.put("state", RUNNING);
                row.put("attempts", row.optInt("attempts", 0) + 1);
                row.put("updatedAt", System.currentTimeMillis());
                row.put("lastError", "");
                saveLocked(c, compact(all));
            }

            if (!AppPrefs.isUnivestEnabled(c)) {
                markCancelled(c, id, signal.symbol,
                        "Automation was disarmed before durable event recovery; event will not execute later.");
                return;
            }
            if ((signal.type == UnivestParser.Type.ENTRY || signal.type == UnivestParser.Type.REENTRY)
                    && AppPrefs.isLiveMode(c) && !AppPrefs.isReadyForBuy(c)) {
                markCancelled(c, id, signal.symbol,
                        "LIVE buy readiness was no longer current at recovery time; stale official buy was not replayed.");
                return;
            }

            DiagnosticsStore.runtime(c, "OFFICIAL_SIGNAL_DURABLE_DISPATCH", signal.symbol,
                    signal.type + " durable event entered broker execution handler • queue id " + id + ".");
            UnivestManager.handle(c, signal, postTime);

            synchronized (FILE_LOCK) {
                List<JSONObject> all = loadLocked(c);
                JSONObject row = findById(all, id);
                if (row != null) {
                    row.put("state", COMPLETE);
                    row.put("updatedAt", System.currentTimeMillis());
                    row.put("completedAt", System.currentTimeMillis());
                    saveLocked(c, compact(all));
                }
            }
            DiagnosticsStore.runtime(c, "OFFICIAL_SIGNAL_DURABLE_COMPLETE", signal.symbol,
                    signal.type + " durable event reached the official execution handler and completed • queue id " + id + ".");
        } catch (Throwable t) {
            String symbol = signal == null ? "" : signal.symbol;
            synchronized (FILE_LOCK) {
                try {
                    List<JSONObject> all = loadLocked(c);
                    JSONObject row = findById(all, id);
                    if (row != null) {
                        row.put("state", RETRYABLE);
                        row.put("updatedAt", System.currentTimeMillis());
                        row.put("lastError", safe(t));
                        saveLocked(c, compact(all));
                    }
                } catch (Throwable ignored) {}
            }
            DiagnosticsStore.error(c, "OFFICIAL_SIGNAL_DURABLE_RETRY", symbol,
                    "Official signal handler was interrupted/failed; event remains durable for broker-truth replay.", t);
            OfficialSignalRecoveryScheduler.scheduleAfter(c, 60_000L);
        } finally {
            synchronized (IDLE_LOCK) {
                IN_FLIGHT.remove(id);
                IDLE_LOCK.notifyAll();
            }
        }
    }

    private static boolean isTerminal(String state) {
        return COMPLETE.equals(state) || CANCELLED.equals(state);
    }

    private static void markCancelled(Context c, String id, String symbol, String reason) {
        synchronized (FILE_LOCK) {
            try {
                List<JSONObject> all = loadLocked(c);
                JSONObject row = findById(all, id);
                if (row != null) {
                    row.put("state", CANCELLED);
                    row.put("updatedAt", System.currentTimeMillis());
                    row.put("completedAt", System.currentTimeMillis());
                    row.put("lastError", reason);
                    saveLocked(c, compact(all));
                }
            } catch (Throwable ignored) {}
        }
        DiagnosticsStore.runtime(c, "OFFICIAL_SIGNAL_DURABLE_CANCELLED", symbol, reason + " • queue id " + id + ".");
    }

    private static List<JSONObject> pendingLocked(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject row : loadLocked(c)) {
            if (!isTerminal(row.optString("state"))) out.add(row);
        }
        return out;
    }

    private static JSONObject findById(List<JSONObject> all, String id) {
        for (JSONObject row : all) if (id.equals(row.optString("id"))) return row;
        return null;
    }

    private static List<JSONObject> compact(List<JSONObject> all) {
        long cutoff = System.currentTimeMillis() - COMPLETE_RETENTION_MS;
        List<JSONObject> keep = new ArrayList<>();
        for (JSONObject row : all) {
            if (isTerminal(row.optString("state")) && row.optLong("completedAt", 0L) > 0
                    && row.optLong("completedAt", 0L) < cutoff) continue;
            keep.add(row);
        }
        if (keep.size() <= MAX_RECORDS) return keep;
        keep.sort(Comparator.comparingLong(o -> o.optLong("updatedAt", 0L)));
        return new ArrayList<>(keep.subList(keep.size() - MAX_RECORDS, keep.size()));
    }

    private static List<JSONObject> loadLocked(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
            JSONArray a = new JSONArray(b.toString());
            for (int i = 0; i < a.length(); i++) {
                JSONObject row = a.optJSONObject(i);
                if (row != null) out.add(row);
            }
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "OFFICIAL_SIGNAL_QUEUE_READ_FAILED", "",
                    "Unable to read durable official signal queue.", t);
        }
        return out;
    }

    private static void saveLocked(Context c, List<JSONObject> all) {
        File target = file(c);
        File temp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        JSONArray a = new JSONArray();
        for (JSONObject row : all) a.put(row);
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(a.toString());
            w.flush();
            fos.getFD().sync();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to write durable official signal queue.", e);
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception fallbackFailed) {
                throw new IllegalStateException("Unable to install durable official signal queue.", fallbackFailed);
            }
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    private static String cleanSymbol(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
