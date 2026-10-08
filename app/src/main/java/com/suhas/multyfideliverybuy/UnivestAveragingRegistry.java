package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Independent registry of every Univest-created downward averaging GTT.
 *
 * It deliberately lives outside the campaign state so a stale/overwritten campaign object cannot make a
 * broker smart order invisible to later EXIT cleanup. Only IDs created by this app are stored here; manual
 * Groww smart orders are never discovered/cancelled by this registry.
 */
final class UnivestAveragingRegistry {
    private static final String FILE = "univest_averaging_registry.json";
    private static final Object LOCK = new Object();

    private UnivestAveragingRegistry() {}

    static void record(Context c, String symbol, String smartOrderId, String referenceId) {
        String id = clean(smartOrderId);
        if (id.isEmpty()) return;
        synchronized (LOCK) {
            JSONArray a = load(c);
            JSONArray out = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                if (id.equals(clean(j.optString("smartOrderId")))) {
                    out.put(row(symbol, id, referenceId, j.optLong("createdAt", System.currentTimeMillis())));
                    replaced = true;
                } else out.put(j);
            }
            if (!replaced) out.put(row(symbol, id, referenceId, System.currentTimeMillis()));
            save(c, out);
        }
    }

    static List<String> idsForSymbol(Context c, String symbol) {
        String wanted = sym(symbol);
        Set<String> ids = new LinkedHashSet<>();
        synchronized (LOCK) {
            JSONArray a = load(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null || !wanted.equals(sym(j.optString("symbol")))) continue;
                String id = clean(j.optString("smartOrderId"));
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    static void remove(Context c, String smartOrderId) {
        String id = clean(smartOrderId);
        if (id.isEmpty()) return;
        synchronized (LOCK) {
            JSONArray a = load(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null || id.equals(clean(j.optString("smartOrderId")))) continue;
                out.put(j);
            }
            save(c, out);
        }
    }

    static JSONArray exportJson(Context c) {
        synchronized (LOCK) { return load(c); }
    }

    static void importJson(Context c, JSONArray source) {
        if (source == null) return;
        synchronized (LOCK) {
            JSONArray out = new JSONArray();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < source.length(); i++) {
                JSONObject j = source.optJSONObject(i);
                if (j == null) continue;
                String id = clean(j.optString("smartOrderId"));
                String symbol = sym(j.optString("symbol"));
                if (id.isEmpty() || symbol.isEmpty() || !seen.add(id)) continue;
                out.put(row(symbol, id, clean(j.optString("referenceId")), j.optLong("createdAt", System.currentTimeMillis())));
            }
            save(c, out);
        }
    }

    static int countForSymbol(Context c, String symbol) { return idsForSymbol(c, symbol).size(); }

    private static JSONObject row(String symbol, String id, String ref, long createdAt) {
        JSONObject j = new JSONObject();
        try {
            j.put("symbol", sym(symbol));
            j.put("smartOrderId", clean(id));
            j.put("referenceId", clean(ref));
            j.put("createdAt", Math.max(0L, createdAt));
        } catch (Exception ignored) {}
        return j;
    }

    private static JSONArray load(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return new JSONArray();
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line);
            return new JSONArray(b.toString());
        } catch (Exception ignored) { return new JSONArray(); }
    }

    private static void save(Context c, JSONArray a) {
        File f = new File(c.getFilesDir(), FILE);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(a == null ? "[]" : a.toString());
            w.flush(); fos.getFD().sync();
            if (f.exists() && !f.delete()) return;
            if (!tmp.renameTo(f)) {
                try (FileOutputStream direct = new FileOutputStream(f);
                     OutputStreamWriter dw = new OutputStreamWriter(direct, StandardCharsets.UTF_8)) {
                    dw.write(a == null ? "[]" : a.toString()); dw.flush(); direct.getFD().sync();
                }
                tmp.delete();
            }
        } catch (Exception ignored) { tmp.delete(); }
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String sym(String s) { return clean(s).toUpperCase(Locale.US); }
}
