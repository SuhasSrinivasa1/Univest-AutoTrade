package com.suhas.multyfideliverybuy;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Durable local evidence ledger for official Univest, broker, runtime and Research events.
 *
 * The SQLite database survives normal APK upgrades. Uninstall still removes app-private storage, so
 * HistoryBackupManager maintains an optional user-owned portable ZIP outside the app sandbox that
 * can be re-selected/restored after a new-signature install.
 */
final class UnivestHistoryDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "univest_history.db";
    private static final int DB_VERSION = 1;
    private static volatile UnivestHistoryDb INSTANCE;
    private static final Object IMPORT_LOCK = new Object();

    private UnivestHistoryDb(Context c) {
        super(c.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    private static UnivestHistoryDb db(Context c) {
        UnivestHistoryDb v = INSTANCE;
        if (v == null) {
            synchronized (UnivestHistoryDb.class) {
                v = INSTANCE;
                if (v == null) INSTANCE = v = new UnivestHistoryDb(c);
            }
        }
        return v;
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE event_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "event_time INTEGER NOT NULL," +
                "stream TEXT NOT NULL," +
                "event_type TEXT NOT NULL," +
                "symbol TEXT NOT NULL DEFAULT ''," +
                "detail_json TEXT NOT NULL DEFAULT '{}'," +
                "fingerprint TEXT NOT NULL UNIQUE)");
        d.execSQL("CREATE INDEX idx_event_time ON event_log(event_time)");
        d.execSQL("CREATE INDEX idx_event_symbol ON event_log(symbol,event_time)");
        d.execSQL("CREATE INDEX idx_event_type ON event_log(event_type,event_time)");
        d.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '')");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {}

    static void ensureInitialized(Context c) {
        db(c).getWritableDatabase();
        importLegacyDiagnosticsOnce(c.getApplicationContext());
    }

    static synchronized void recordDiagnostic(Context c, long time, String stream, String event,
                                              String symbol, JSONObject detail) {
        try {
            SQLiteDatabase d = db(c).getWritableDatabase();
            insert(d, time, stream, event, symbol, detail == null ? "{}" : detail.toString());
        } catch (Throwable ignored) {}
    }

    static synchronized int count(Context c) {
        try (Cursor cur = db(c).getReadableDatabase().rawQuery("SELECT COUNT(*) FROM event_log", null)) {
            return cur.moveToFirst() ? cur.getInt(0) : 0;
        } catch (Throwable t) { return 0; }
    }

    static synchronized JSONArray officialTradingSignals(Context c) {
        JSONArray out = new JSONArray();
        try (Cursor cur = db(c).getReadableDatabase().query(
                "event_log",
                new String[]{"event_time","symbol","detail_json"},
                "stream=? AND event_type=?",
                new String[]{"notifications","NOTIFICATION_RECEIVED"},
                null, null, "event_time ASC")) {
            while (cur.moveToNext()) {
                try {
                    JSONObject detail = new JSONObject(cur.getString(2));
                    if (!detail.optBoolean("isTradingSignal", false)) continue;
                    JSONObject row = new JSONObject();
                    row.put("eventTime", cur.getLong(0));
                    row.put("symbol", cur.getString(1));
                    row.put("signalType", detail.optString("signalType", ""));
                    row.put("raw", detail.optString("raw", ""));
                    out.put(row);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static synchronized JSONObject exportJson(Context c) {
        JSONObject root = new JSONObject();
        JSONArray rows = new JSONArray();
        try {
            root.put("schemaVersion", 1);
            root.put("exportedAt", System.currentTimeMillis());
            try (Cursor cur = db(c).getReadableDatabase().query(
                    "event_log",
                    new String[]{"event_time","stream","event_type","symbol","detail_json","fingerprint"},
                    null, null, null, null, "event_time ASC")) {
                while (cur.moveToNext()) {
                    JSONObject r = new JSONObject();
                    r.put("eventTime", cur.getLong(0));
                    r.put("stream", cur.getString(1));
                    r.put("eventType", cur.getString(2));
                    r.put("symbol", cur.getString(3));
                    try { r.put("detail", new JSONObject(cur.getString(4))); }
                    catch (Throwable t) { r.put("detailText", cur.getString(4)); }
                    r.put("fingerprint", cur.getString(5));
                    rows.put(r);
                }
            }
            root.put("rows", rows);
        } catch (Throwable ignored) {}
        return root;
    }

    static synchronized int importJson(Context c, JSONObject root) {
        if (root == null) return 0;
        int before = count(c);
        JSONArray rows = root.optJSONArray("rows");
        if (rows == null) return 0;
        SQLiteDatabase d = db(c).getWritableDatabase();
        d.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String detail;
                JSONObject dj = r.optJSONObject("detail");
                if (dj != null) detail = dj.toString();
                else detail = r.optString("detailText", "{}");
                insert(d, r.optLong("eventTime", System.currentTimeMillis()),
                        r.optString("stream", "restored"),
                        r.optString("eventType", "RESTORED"),
                        r.optString("symbol", ""), detail,
                        r.optString("fingerprint", ""));
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return Math.max(0, count(c) - before);
    }

    static File databaseFile(Context c) {
        return c.getDatabasePath(DB_NAME);
    }

    private static void importLegacyDiagnosticsOnce(Context c) {
        synchronized (IMPORT_LOCK) {
            SQLiteDatabase d = db(c).getWritableDatabase();
            String done = meta(d, "legacy_diagnostics_imported");
            if ("1".equals(done)) return;
            File dir = new File(c.getFilesDir(), "univest_diagnostics");
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                d.beginTransaction();
                try {
                    for (File f : files) {
                        if (!f.isFile() || !f.getName().endsWith(".jsonl")) continue;
                        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                try {
                                    JSONObject j = new JSONObject(line);
                                    JSONObject detail = j.optJSONObject("detail");
                                    insert(d,
                                            j.optLong("timestampMillis", System.currentTimeMillis()),
                                            j.optString("stream", "legacy"),
                                            j.optString("event", "LEGACY"),
                                            j.optString("symbol", ""),
                                            detail == null ? "{}" : detail.toString());
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                    setMeta(d, "legacy_diagnostics_imported", "1");
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
            } else {
                setMeta(d, "legacy_diagnostics_imported", "1");
            }
        }
    }

    private static void insert(SQLiteDatabase d, long time, String stream, String event,
                               String symbol, String detail) {
        insert(d, time, stream, event, symbol, detail, "");
    }

    private static void insert(SQLiteDatabase d, long time, String stream, String event,
                               String symbol, String detail, String suppliedFingerprint) {
        String st = stream == null ? "" : stream;
        String ev = event == null ? "" : event;
        String sy = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        String de = detail == null ? "{}" : detail;
        String fp = suppliedFingerprint == null || suppliedFingerprint.trim().isEmpty()
                ? fingerprint(time + "|" + st + "|" + ev + "|" + sy + "|" + de)
                : suppliedFingerprint;
        ContentValues v = new ContentValues();
        v.put("event_time", Math.max(0L, time));
        v.put("stream", st);
        v.put("event_type", ev);
        v.put("symbol", sy);
        v.put("detail_json", de);
        v.put("fingerprint", fp);
        d.insertWithOnConflict("event_log", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static String meta(SQLiteDatabase d, String key) {
        try (Cursor c = d.query("meta", new String[]{"v"}, "k=?", new String[]{key}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    private static void setMeta(SQLiteDatabase d, String key, String value) {
        ContentValues v = new ContentValues();
        v.put("k", key); v.put("v", value == null ? "" : value);
        d.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String fingerprint(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte x : b) out.append(String.format(Locale.US, "%02x", x));
            return out.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
