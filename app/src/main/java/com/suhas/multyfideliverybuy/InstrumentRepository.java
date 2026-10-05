package com.suhas.multyfideliverybuy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InstrumentRepository {
    private static final String ASSET = "nse_cash_eq.csv";
    private static final String CACHE = "nse_cash_eq_cache.csv";
    private static final String OFFICIAL_CSV = "https://growwapi-assets.groww.in/instruments/instrument.csv";
    private static final long REFRESH_MS = 12L * 60L * 60L * 1000L;

    static final class Instrument {
        final String symbol;
        final String name;
        final double tickSize;
        final boolean buyAllowed;
        final boolean sellAllowed;

        Instrument(String symbol, String name, double tickSize, boolean buyAllowed, boolean sellAllowed) {
            this.symbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
            this.name = name == null ? "" : name.trim();
            this.tickSize = normalizeTick(tickSize);
            this.buyAllowed = buyAllowed;
            this.sellAllowed = sellAllowed;
        }

        String display() {
            return name.isEmpty() ? symbol : symbol + " — " + name;
        }
    }

    private InstrumentRepository() {}

    static List<Instrument> load(Context context) {
        List<Instrument> list = Collections.emptyList();
        File cache = new File(context.getFilesDir(), CACHE);
        if (cache.exists()) {
            try (InputStream in = new FileInputStream(cache)) {
                list = parseCompact(in);
            } catch (Exception ignored) {}
        }
        if (!list.isEmpty()) return list;
        try (InputStream in = context.getAssets().open(ASSET)) {
            return parseCompact(in);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    static boolean refreshIfStale(Context context) {
        File cache = new File(context.getFilesDir(), CACHE);
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS) return false;
        File tmp = new File(context.getFilesDir(), CACHE + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(OFFICIAL_CSV).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "text/csv,*/*");
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return false;
            List<Instrument> all = parseOfficial(c.getInputStream());
            if (all.size() < 100) return false;
            writeCompact(tmp, all);
            if (cache.exists() && !cache.delete()) return false;
            return tmp.renameTo(cache);
        } catch (Exception ignored) {
            if (tmp.exists()) tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Instrument resolve(List<Instrument> instruments, String typed) {
        if (typed == null || instruments == null) return null;
        String raw = typed.trim();
        if (raw.isEmpty()) return null;

        String symbol = raw;
        int dash = raw.indexOf('—');
        if (dash > 0) symbol = raw.substring(0, dash).trim();
        int hy = raw.indexOf(" - ");
        if (hy > 0) symbol = raw.substring(0, hy).trim();
        symbol = stripSeries(symbol.toUpperCase(Locale.US));

        // 1) Exchange trading symbol is authoritative.
        for (Instrument i : instruments) if (stripSeries(i.symbol).equals(symbol)) return i;

        // 2) Exact company-name match after punctuation/legal-suffix normalization.
        String wanted = normalizedName(raw);
        if (!wanted.isEmpty()) {
            Instrument exact = null;
            for (Instrument i : instruments) {
                if (normalizedName(i.name).equals(wanted)) {
                    if (exact != null && !exact.symbol.equals(i.symbol)) return null; // ambiguous: never guess
                    exact = i;
                }
            }
            if (exact != null) return exact;
        }

        // 3) Safe unique high-confidence company-name match. This fixes common Univest display-name vs NSE-symbol cases.
        Instrument best = null;
        int bestScore = 0;
        int ties = 0;
        for (Instrument i : instruments) {
            int score = nameScore(wanted, normalizedName(i.name), i.symbol);
            if (score > bestScore) { bestScore = score; best = i; ties = 1; }
            else if (score == bestScore && score > 0) ties++;
        }
        return bestScore >= 90 && ties == 1 ? best : null;
    }

    static List<String> suggestions(List<Instrument> instruments, String typed, int limit) {
        List<String> out = new ArrayList<>();
        if (instruments == null || typed == null || limit <= 0) return out;
        final String wanted = normalizedName(typed);
        List<Instrument> copy = new ArrayList<>(instruments);
        copy.sort((a, b) -> Integer.compare(
                nameScore(wanted, normalizedName(b.name), b.symbol),
                nameScore(wanted, normalizedName(a.name), a.symbol)));
        for (Instrument i : copy) {
            int score = nameScore(wanted, normalizedName(i.name), i.symbol);
            if (score <= 0) continue;
            out.add(i.display() + " [score " + score + "]");
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static String stripSeries(String s) {
        String v = s == null ? "" : s.trim().toUpperCase(Locale.US);
        if (v.endsWith("-BE") || v.endsWith("-EQ")) v = v.substring(0, v.length() - 3);
        return v;
    }

    static String normalizedName(String s) {
        String v = s == null ? "" : s.toUpperCase(Locale.US);
        v = v.replace('&', ' ');
        v = v.replaceAll("[^A-Z0-9]+", " ").trim();
        v = v.replaceAll("\\b(?:LIMITED|LTD|PRIVATE|PVT|INDIA|INDUSTRIES|INDUSTRY|CORPORATION|CORP|COMPANY|CO|THE)\\b", " ");
        return v.replaceAll("\\s+", " ").trim();
    }

    private static int nameScore(String wanted, String candidate, String symbol) {
        if (wanted == null || wanted.isEmpty()) return 0;
        String sym = stripSeries(symbol);
        String compactWanted = wanted.replace(" ", "");
        if (compactWanted.equals(sym.replaceAll("[^A-Z0-9]", ""))) return 100;
        if (candidate.equals(wanted)) return 100;
        if (candidate.startsWith(wanted + " ") || wanted.startsWith(candidate + " ")) return 94;
        String[] wa = wanted.split(" ");
        String[] ca = candidate.split(" ");
        int common = 0;
        for (String w : wa) {
            if (w.length() < 2) continue;
            for (String c : ca) if (w.equals(c)) { common++; break; }
        }
        if (common == 0) return 0;
        int denom = Math.max(wa.length, ca.length);
        int score = (int) Math.round(85.0 * common / Math.max(1, denom));
        if (wa.length > 0 && ca.length > 0 && wa[0].equals(ca[0])) score += 8;
        return Math.min(93, score);
    }

    static List<String> displays(List<Instrument> instruments) {
        List<String> out = new ArrayList<>();
        if (instruments != null) for (Instrument i : instruments) out.add(i.display());
        return out;
    }

    private static List<Instrument> parseCompact(InputStream in) throws Exception {
        List<Instrument> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return out;
            List<String> header = csv(line);
            Map<String,Integer> idx = index(header);
            while ((line = br.readLine()) != null) {
                List<String> r = csv(line);
                String symbol = val(r, idx, "trading_symbol");
                if (symbol.isEmpty()) continue;
                String name = val(r, idx, "name");
                double tick = number(val(r, idx, "tick_size"), 0.05);
                boolean buy = flag(val(r, idx, "buy_allowed"));
                boolean sell = flag(val(r, idx, "sell_allowed"));
                out.add(new Instrument(symbol, name, tick, buy, sell));
            }
        }
        out.sort(Comparator.comparing(a -> a.symbol));
        return out;
    }

    private static List<Instrument> parseOfficial(InputStream in) throws Exception {
        List<Instrument> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return out;
            List<String> header = csv(line);
            Map<String,Integer> idx = index(header);
            while ((line = br.readLine()) != null) {
                List<String> r = csv(line);
                if (!"NSE".equalsIgnoreCase(val(r, idx, "exchange"))) continue;
                if (!"CASH".equalsIgnoreCase(val(r, idx, "segment"))) continue;
                if (!"EQ".equalsIgnoreCase(val(r, idx, "instrument_type"))) continue;
                String series = val(r, idx, "series");
                if (!series.isEmpty() && !"EQ".equalsIgnoreCase(series) && !"BE".equalsIgnoreCase(series)) continue;
                if (flag(val(r, idx, "is_reserved"))) continue;
                String symbol = val(r, idx, "trading_symbol");
                if (symbol.isEmpty()) continue;
                String name = val(r, idx, "name");
                double tick = number(val(r, idx, "tick_size"), 0.05);
                boolean buy = flag(val(r, idx, "buy_allowed"));
                boolean sell = flag(val(r, idx, "sell_allowed"));
                if (!buy && !sell) continue;
                out.add(new Instrument(symbol, name, tick, buy, sell));
            }
        }
        out.sort(Comparator.comparing(a -> a.symbol));
        return out;
    }

    private static void writeCompact(File file, List<Instrument> list) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write("trading_symbol,name,tick_size,buy_allowed,sell_allowed\n");
            for (Instrument i : list) {
                w.write(q(i.symbol)); w.write(',');
                w.write(q(i.name)); w.write(',');
                w.write(String.format(Locale.US, "%.4f", i.tickSize)); w.write(',');
                w.write(i.buyAllowed ? "1" : "0"); w.write(',');
                w.write(i.sellAllowed ? "1" : "0"); w.write('\n');
            }
        }
    }

    private static String q(String s) {
        String v = s == null ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static Map<String,Integer> index(List<String> header) {
        Map<String,Integer> m = new HashMap<>();
        for (int i = 0; i < header.size(); i++) m.put(header.get(i).trim().toLowerCase(Locale.US), i);
        return m;
    }

    private static String val(List<String> row, Map<String,Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null || i < 0 || i >= row.size()) return "";
        String s = row.get(i);
        return s == null ? "" : s.trim();
    }

    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString()); cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean flag(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.US);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v);
    }

    private static double number(String s, double fallback) {
        try { return Double.parseDouble(s); } catch (Exception e) { return fallback; }
    }

    private static double normalizeTick(double v) {
        if (!(v > 0)) return 0.05;
        // Groww's current official instrument master is authoritative for tick size.
        return v;
    }
}
