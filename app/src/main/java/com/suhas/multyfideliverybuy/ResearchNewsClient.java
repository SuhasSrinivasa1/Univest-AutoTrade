package com.suhas.multyfideliverybuy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ResearchNewsClient {
    static final class Summary {
        int nationalCount;
        int internationalCount;
        int positiveCatalysts;
        int negativeCatalysts;
        String topHeadlines = "";
        String catalystLabel = "No material headline signal";
    }

    private ResearchNewsClient() {}

    static Summary fetch(String companyName, String symbol) {
        Summary out = new Summary();
        try {
            String q = companyName == null || companyName.trim().isEmpty() ? symbol : companyName;
            q = q == null ? "" : q.trim();
            if (q.isEmpty()) return out;
            String endpoint = "https://api.gdeltproject.org/api/v2/doc/doc?query="
                    + URLEncoder.encode("\"" + q + "\"", StandardCharsets.UTF_8.name())
                    + "&mode=artlist&maxrecords=10&format=json&sort=datedesc";
            HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(6500);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("User-Agent","UnivestResearchLab/2.3");
            int code=c.getResponseCode();
            if(code<200||code>=300)return out;
            StringBuilder body=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                String line; while((line=r.readLine())!=null)body.append(line);
            } finally { c.disconnect(); }
            JSONObject root=new JSONObject(body.toString());
            JSONArray a=root.optJSONArray("articles");
            if(a==null)return out;
            StringBuilder heads=new StringBuilder();
            for(int i=0;i<a.length()&&i<10;i++){
                JSONObject j=a.optJSONObject(i); if(j==null)continue;
                String title=j.optString("title","");
                String country=j.optString("sourcecountry","");
                if("India".equalsIgnoreCase(country))out.nationalCount++;else out.internationalCount++;
                String l=title.toLowerCase(Locale.US);
                if(contains(l,"order win","wins order","contract","approval","profit rises","revenue rises","beat estimates","expansion","capacity","acquisition","upgrade","record high","strong demand","partnership")) out.positiveCatalysts++;
                if(contains(l,"fraud","probe","downgrade","profit falls","loss widens","default","penalty","ban","resigns","weak demand","cuts guidance","misses estimates")) out.negativeCatalysts++;
                if(!title.isEmpty()&&heads.length()<600){ if(heads.length()>0)heads.append("\n"); heads.append("• ").append(title); }
            }
            out.topHeadlines=heads.toString();
            if(out.positiveCatalysts>out.negativeCatalysts && out.positiveCatalysts>0) out.catalystLabel="Positive catalyst/news skew";
            else if(out.negativeCatalysts>out.positiveCatalysts && out.negativeCatalysts>0) out.catalystLabel="Negative catalyst/news skew";
            else if(out.nationalCount+out.internationalCount>0) out.catalystLabel="Mixed/neutral news flow";
        } catch(Throwable ignored) {}
        return out;
    }

    private static boolean contains(String text,String... tokens){for(String t:tokens)if(text.contains(t))return true;return false;}
}
