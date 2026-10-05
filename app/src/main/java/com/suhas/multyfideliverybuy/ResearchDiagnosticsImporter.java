package com.suhas.multyfideliverybuy;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class ResearchDiagnosticsImporter {
    private ResearchDiagnosticsImporter(){}

    static void importOfficialSignals(Context c){
        try{
            Set<String> existing=new HashSet<>();
            for(JSONObject j:ResearchStore.signals(c))existing.add(key(j.optString("type"),j.optString("symbol"),j.optLong("signalAt")));
            File dir=new File(c.getFilesDir(),"univest_diagnostics");
            File[] files=dir.listFiles((d,n)->n.startsWith("notifications-")&&n.endsWith(".jsonl"));
            if(files==null)return;
            Arrays.sort(files,Comparator.comparing(File::getName));
            for(File f:files){
                try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
                    String line;
                    while((line=r.readLine())!=null){
                        try{
                            JSONObject row=new JSONObject(line);JSONObject d=row.optJSONObject("detail");if(d==null||!d.optBoolean("isTradingSignal",false))continue;
                            String type=d.optString("signalType",""),symbol=d.optString("parsedSymbol",""),raw=d.optString("raw","");
                            long at=row.optLong("timestampMillis",0L);String k=key(type,symbol,at);if(existing.contains(k))continue;
                            UnivestParser.Type t;try{t=UnivestParser.Type.valueOf(type);}catch(Exception e){continue;}
                            ResearchStore.captureSignal(c,new UnivestParser.Signal(t,symbol,raw),at);existing.add(k);
                        }catch(Exception ignored){}
                    }
                }catch(Exception ignored){}
            }
        }catch(Throwable ignored){}
    }

    private static String key(String t,String s,long at){return t+"|"+s+"|"+at;}
}
