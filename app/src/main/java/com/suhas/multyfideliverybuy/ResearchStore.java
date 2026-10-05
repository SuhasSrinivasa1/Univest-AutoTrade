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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResearchStore {
    private static final String DIR = "research_lab";
    private static final String SIGNALS = "recommendations.jsonl";
    private static final String FEATURES = "feature_snapshots.jsonl";
    private static final String PREDICTIONS = "predictions.json";
    private static final String FORECAST_HISTORY = "forecast_history.jsonl";
    private static final String STRATEGIES = "strategies.json";
    private static final String INTELLIGENCE = "intelligence.json";
    private static final String POSITIONS = "research_positions.json";
    private static final Pattern DURATION = Pattern.compile("(?i)(?:duration|holding(?:\\s+period)?|time\\s*horizon)\\s*[:\\-]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:(?:-|–|—|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*months?");

    private ResearchStore() {}

    static synchronized void captureSignal(Context context, UnivestParser.Signal signal, long postTime) {
        if (signal == null) return;
        try {
            String symbol = signal.symbol == null ? "" : signal.symbol.trim().toUpperCase(Locale.US);
            long at = postTime > 0 ? postTime : System.currentTimeMillis();
            String raw = signal.rawText == null ? "" : signal.rawText.trim();
            List<JSONObject> recent = readJsonLines(context, SIGNALS, 30);
            for (int i = recent.size() - 1; i >= 0; i--) {
                JSONObject prior = recent.get(i);
                if (!signal.type.name().equals(prior.optString("type"))) continue;
                if (!symbol.equalsIgnoreCase(prior.optString("symbol"))) continue;
                long priorAt = prior.optLong("signalAt", 0L);
                if (Math.abs(at - priorAt) <= 30000L && raw.equals(prior.optString("raw", "").trim())) return;
            }
            try {
                InstrumentRepository.Instrument i = InstrumentRepository.resolve(InstrumentRepository.load(context), signal.symbol);
                if (i != null) symbol = i.symbol;
            } catch (Throwable ignored) {}
            JSONObject j = new JSONObject();
            j.put("capturedAt", System.currentTimeMillis());
            j.put("signalAt", at);
            j.put("type", signal.type.name());
            j.put("symbol", symbol);
            j.put("durationMonths", durationMonths(signal.rawText));
            j.put("raw", raw);
            append(context, SIGNALS, j);
        } catch (Exception e) {
            DiagnosticsStore.error(context, "RESEARCH_CAPTURE_FAILED", signal.symbol, "Unable to archive Research Lab signal.", e);
        }
    }

    static synchronized List<JSONObject> signals(Context context) { return readJsonLines(context, SIGNALS, 5000); }
    static synchronized List<JSONObject> recentSignals(Context context, int limit) { return readJsonLines(context, SIGNALS, Math.max(1, limit)); }
    static synchronized List<JSONObject> featureSnapshots(Context context) { return readJsonLines(context, FEATURES, 5000); }

    static synchronized void appendFeature(Context context, JSONObject feature) {
        try { append(context, FEATURES, feature); }
        catch (Exception e) { DiagnosticsStore.error(context, "RESEARCH_FEATURE_SAVE_FAILED", feature == null ? "" : feature.optString("symbol"), "Unable to save feature snapshot.", e); }
    }

    static synchronized void savePredictions(Context c, JSONArray a) { writeJson(c, PREDICTIONS, a == null ? new JSONArray().toString() : a.toString()); }
    static synchronized JSONArray predictions(Context c) { return readArray(c, PREDICTIONS); }

    static synchronized void appendForecastSnapshot(Context c, JSONArray predictions, long frozenAt) {
        appendForecastSnapshot(c, predictions, frozenAt, "EOD_FROZEN", NseTradingCalendar.nextTradingDayKey(frozenAt));
    }

    static synchronized void appendForecastSnapshot(Context c, JSONArray predictions, long frozenAt,
                                                    String freezeType, String targetSessionKey) {
        try {
            JSONObject row = new JSONObject();
            row.put("schemaVersion", 2);
            row.put("frozenAt", frozenAt);
            row.put("createdDayKey", AppPrefs.istDayKey(frozenAt));
            row.put("targetSessionKey", targetSessionKey == null ? "" : targetSessionKey);
            row.put("freezeType", freezeType == null ? "" : freezeType);
            row.put("predictions", predictions == null ? new JSONArray() : new JSONArray(predictions.toString()));
            append(c, FORECAST_HISTORY, row);
        } catch (Exception e) {
            DiagnosticsStore.error(c, "FORECAST_HISTORY_SAVE_FAILED", "", "Unable to save frozen forecast snapshot.", e);
        }
    }

    static synchronized List<JSONObject> forecastHistory(Context c, int limit) {
        return readJsonLines(c, FORECAST_HISTORY, Math.max(1, limit));
    }
    static synchronized void saveStrategies(Context c, JSONObject o) { writeJson(c, STRATEGIES, o == null ? new JSONObject().toString() : o.toString()); }
    static synchronized JSONObject strategies(Context c) { return readObject(c, STRATEGIES); }
    static synchronized void saveIntelligence(Context c, JSONArray a) { writeJson(c, INTELLIGENCE, a == null ? new JSONArray().toString() : a.toString()); }
    static synchronized JSONArray intelligence(Context c) { return readArray(c, INTELLIGENCE); }
    static synchronized JSONArray positions(Context c) { return readArray(c, POSITIONS); }
    static synchronized void savePositions(Context c, JSONArray a) { writeJson(c, POSITIONS, a == null ? new JSONArray().toString() : a.toString()); }

    static double durationMonths(String raw) {
        Matcher m = DURATION.matcher(raw == null ? "" : raw);
        if (!m.find()) return 0;
        try {
            double a=Double.parseDouble(m.group(1));
            double b=m.group(2)==null?a:Double.parseDouble(m.group(2));
            return Math.max(a,b);
        } catch(Exception e){ return 0; }
    }

    static String recentRecommendationsText(Context c, int limit) {
        List<JSONObject> all=recentSignals(c, Math.max(limit*4,20));
        StringBuilder b=new StringBuilder(); int n=0;
        for(int i=all.size()-1;i>=0 && n<limit;i--){
            JSONObject j=all.get(i);
            if(!"ENTRY".equals(j.optString("type"))) continue;
            if(b.length()>0)b.append("\n\n");
            b.append(j.optString("symbol","?")).append(" • ")
             .append(j.optDouble("durationMonths",0)>0?trim(j.optDouble("durationMonths"))+" month horizon":"eligible 1–3 month")
             .append("\n").append(AppPrefs.istDayKey(j.optLong("signalAt",0)));
            n++;
        }
        return b.length()==0?"No eligible 1–3 month recommendations archived yet.":b.toString();
    }

    private static String trim(double v){ return Math.abs(v-Math.rint(v))<0.001?String.valueOf((int)Math.rint(v)):String.format(Locale.US,"%.1f",v); }

    private static File dir(Context c) {
        File d=new File(c.getFilesDir(),DIR); if(!d.exists()) d.mkdirs(); return d;
    }

    private static void append(Context c,String name,JSONObject j) throws Exception {
        File f=new File(dir(c),name);
        try(Writer w=new OutputStreamWriter(new FileOutputStream(f,true),StandardCharsets.UTF_8)){
            w.write(j.toString());w.write("\n");
        }
    }

    private static List<JSONObject> readJsonLines(Context c,String name,int limit){
        File f=new File(dir(c),name); if(!f.exists()) return new ArrayList<>();
        ArrayList<JSONObject> out=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            String line; while((line=r.readLine())!=null){ try{out.add(new JSONObject(line));}catch(Exception ignored){} }
        }catch(Exception ignored){}
        if(out.size()<=limit)return out;
        return new ArrayList<>(out.subList(out.size()-limit,out.size()));
    }

    private static void writeJson(Context c,String name,String text){
        try(Writer w=new OutputStreamWriter(new FileOutputStream(new File(dir(c),name),false),StandardCharsets.UTF_8)){w.write(text);}
        catch(Exception e){DiagnosticsStore.error(c,"RESEARCH_STORE_WRITE_FAILED","",name+" could not be written.",e);}
    }
    private static String readText(Context c,String name){
        File f=new File(dir(c),name); if(!f.exists())return "";
        StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)b.append(line);}
        catch(Exception ignored){}
        return b.toString();
    }
    private static JSONArray readArray(Context c,String name){try{String t=readText(c,name);return t.isEmpty()?new JSONArray():new JSONArray(t);}catch(Exception e){return new JSONArray();}}
    private static JSONObject readObject(Context c,String name){try{String t=readText(c,name);return t.isEmpty()?new JSONObject():new JSONObject(t);}catch(Exception e){return new JSONObject();}}
}
