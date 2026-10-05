package com.suhas.multyfideliverybuy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ResearchMath {
    static final class Features {
        double close;
        double sma20;
        double sma50;
        double rsi14;
        double atr14;
        double relativeVolume20;
        double return5Pct;
        double return20Pct;
        double return60Pct;
        double prior20High;
        double volatility20Pct;
        int dataPoints;
    }

    static final class StrategyScores {
        int volumeBreakout;
        int trendPullback;
        int momentum;
        int qualityRerating;
        int catalystSector;
        int consensus;
        int bestScore;
        String bestStrategy;

        int scoreFor(String strategy) {
            if ("VOLUME_BREAKOUT".equals(strategy)) return volumeBreakout;
            if ("TREND_PULLBACK".equals(strategy)) return trendPullback;
            if ("MOMENTUM_CONTINUATION".equals(strategy)) return momentum;
            if ("QUALITY_RERATING".equals(strategy)) return qualityRerating;
            if ("CATALYST_SECTOR".equals(strategy)) return catalystSector;
            return 0;
        }
    }

    private ResearchMath() {}

    static Features fromCandles(List<GrowwClient.Candle> candles) {
        Features f = new Features();
        if (candles == null || candles.size() < 15) return f;
        List<GrowwClient.Candle> c = new ArrayList<>(candles);
        c.sort((a,b) -> Long.compare(a.epochSeconds, b.epochSeconds));
        int n = c.size();
        GrowwClient.Candle last = c.get(n - 1);
        f.close = last.close;
        f.dataPoints = n;
        f.sma20 = sma(c, 20);
        f.sma50 = sma(c, 50);
        f.rsi14 = rsi(c, 14);
        f.atr14 = atr(c, 14);
        f.relativeVolume20 = relativeVolume(c, 20);
        f.return5Pct = ret(c, 5);
        f.return20Pct = ret(c, 20);
        f.return60Pct = ret(c, 60);
        f.prior20High = priorHigh(c, 20);
        f.volatility20Pct = volatility(c, 20);
        return f;
    }

    static StrategyScores score(Features f, int catalystCount, double sectorRelativePct, boolean fundamentalsPositive) {
        StrategyScores s = new StrategyScores();
        if (f == null || !(f.close > 0) || f.dataPoints < 15) {
            s.bestStrategy = "INSUFFICIENT_DATA"; return s;
        }

        int breakout = 0;
        breakout += between(f.close / Math.max(0.01, f.prior20High), 0.985, 1.08) ? 25 : 0;
        breakout += f.relativeVolume20 >= 1.25 ? 25 : (f.relativeVolume20 >= 1.0 ? 12 : 0);
        breakout += f.sma20 > 0 && f.sma50 > 0 && f.sma20 >= f.sma50 ? 20 : 0;
        breakout += between(f.rsi14, 52, 75) ? 15 : 0;
        breakout += f.return20Pct > 0 ? 15 : 0;
        s.volumeBreakout = clamp(breakout);

        int pullback = 0;
        double d20 = f.sma20 > 0 ? Math.abs(f.close - f.sma20) / f.sma20 * 100.0 : 99;
        pullback += f.sma20 > 0 && f.sma50 > 0 && f.sma20 > f.sma50 ? 25 : 0;
        pullback += d20 <= 3.0 ? 25 : (d20 <= 5.5 ? 12 : 0);
        pullback += between(f.rsi14, 44, 65) ? 20 : 0;
        pullback += f.return60Pct > 4 ? 20 : (f.return60Pct > 0 ? 10 : 0);
        pullback += f.relativeVolume20 >= 0.75 && f.relativeVolume20 <= 1.8 ? 10 : 0;
        s.trendPullback = clamp(pullback);

        int momentum = 0;
        momentum += f.return5Pct > 0 ? 20 : 0;
        momentum += f.return20Pct > 3 ? 25 : (f.return20Pct > 0 ? 12 : 0);
        momentum += f.sma20 > 0 && f.sma50 > 0 && f.close > f.sma20 && f.sma20 > f.sma50 ? 25 : 0;
        momentum += between(f.rsi14, 55, 76) ? 20 : 0;
        momentum += f.relativeVolume20 >= 1.0 ? 10 : 0;
        s.momentum = clamp(momentum);

        int quality = 0;
        quality += fundamentalsPositive ? 35 : 0;
        quality += f.return60Pct > 0 ? 20 : 0;
        quality += f.volatility20Pct > 0 && f.volatility20Pct < 3.2 ? 20 : (f.volatility20Pct < 5.0 ? 10 : 0);
        quality += f.sma50 > 0 && f.close > f.sma50 ? 15 : 0;
        quality += f.rsi14 >= 45 && f.rsi14 <= 70 ? 10 : 0;
        s.qualityRerating = clamp(quality);

        int catalyst = 0;
        catalyst += Math.min(35, catalystCount * 10);
        catalyst += sectorRelativePct > 2 ? 25 : (sectorRelativePct > 0 ? 12 : 0);
        catalyst += f.return20Pct > 0 ? 15 : 0;
        catalyst += f.relativeVolume20 >= 1.2 ? 15 : 0;
        catalyst += f.rsi14 >= 50 && f.rsi14 <= 75 ? 10 : 0;
        s.catalystSector = clamp(catalyst);

        int[] scores = {s.volumeBreakout, s.trendPullback, s.momentum, s.qualityRerating, s.catalystSector};
        String[] names = {"VOLUME_BREAKOUT","TREND_PULLBACK","MOMENTUM_CONTINUATION","QUALITY_RERATING","CATALYST_SECTOR"};
        int best = -1, bi = 0, consensus = 0;
        for (int i=0;i<scores.length;i++) {
            if (scores[i] >= 70) consensus++;
            if (scores[i] > best) { best=scores[i]; bi=i; }
        }
        s.bestScore = Math.max(0,best);
        s.bestStrategy = names[bi];
        s.consensus = consensus;
        return s;
    }

    static double[] learnedZones(Features f, double learnedExitPct) {
        if (f == null || !(f.close > 0)) return new double[]{0,0,0,0,0};
        double atr = f.atr14 > 0 ? f.atr14 : f.close * 0.025;
        double buyLow = Math.max(0.01, f.close - 0.25 * atr);
        double buyHigh = f.close + 0.35 * atr;
        double chase = f.close + 0.80 * atr;
        double targetPct = learnedExitPct > 1.0 ? learnedExitPct : Math.max(4.0, (2.2 * atr / f.close) * 100.0);
        double sellLow = f.close * (1.0 + targetPct / 100.0);
        double sellHigh = f.close * (1.0 + (targetPct + Math.max(1.5, targetPct * 0.35)) / 100.0);
        return new double[]{buyLow,buyHigh,chase,sellLow,sellHigh};
    }

    static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> x = new ArrayList<>(v);
        Collections.sort(x);
        int n=x.size();
        return n%2==1 ? x.get(n/2) : (x.get(n/2-1)+x.get(n/2))/2.0;
    }

    private static double sma(List<GrowwClient.Candle> c, int p) {
        int n=c.size(); int start=Math.max(0,n-p); double sum=0; int count=0;
        for(int i=start;i<n;i++){ if(c.get(i).close>0){sum+=c.get(i).close;count++;}}
        return count==0?0:sum/count;
    }

    private static double rsi(List<GrowwClient.Candle> c, int p) {
        int n=c.size(); if(n<2)return 0; int start=Math.max(1,n-p);
        double gains=0,losses=0; int count=0;
        for(int i=start;i<n;i++){double d=c.get(i).close-c.get(i-1).close;if(d>0)gains+=d;else losses-=d;count++;}
        if(count==0)return 0; double ag=gains/count, al=losses/count; if(al==0)return ag>0?100:50;
        double rs=ag/al; return 100.0-(100.0/(1.0+rs));
    }

    private static double atr(List<GrowwClient.Candle> c, int p) {
        int n=c.size(); if(n<2)return 0; int start=Math.max(1,n-p);double sum=0;int count=0;
        for(int i=start;i<n;i++){GrowwClient.Candle x=c.get(i),prev=c.get(i-1);double tr=Math.max(x.high-x.low,Math.max(Math.abs(x.high-prev.close),Math.abs(x.low-prev.close)));sum+=tr;count++;}
        return count==0?0:sum/count;
    }

    private static double relativeVolume(List<GrowwClient.Candle> c,int p){
        int n=c.size(); if(n<2)return 0; int start=Math.max(0,n-1-p);double sum=0;int count=0;
        for(int i=start;i<n-1;i++){if(c.get(i).volume>0){sum+=c.get(i).volume;count++;}}
        double avg=count==0?0:sum/count; return avg>0?c.get(n-1).volume/avg:0;
    }

    private static double ret(List<GrowwClient.Candle> c,int sessions){
        int n=c.size(); int idx=Math.max(0,n-1-sessions);double a=c.get(idx).close,b=c.get(n-1).close;return a>0?(b/a-1.0)*100.0:0;
    }

    private static double priorHigh(List<GrowwClient.Candle> c,int p){
        int n=c.size();int start=Math.max(0,n-1-p);double h=0;for(int i=start;i<n-1;i++)h=Math.max(h,c.get(i).high);return h>0?h:c.get(n-1).high;
    }

    private static double volatility(List<GrowwClient.Candle> c,int p){
        int n=c.size();int start=Math.max(1,n-p);List<Double> r=new ArrayList<>();
        for(int i=start;i<n;i++){double prev=c.get(i-1).close;if(prev>0)r.add((c.get(i).close/prev-1.0)*100.0);}
        if(r.isEmpty())return 0;double mean=0;for(double x:r)mean+=x;mean/=r.size();double v=0;for(double x:r)v+=(x-mean)*(x-mean);return Math.sqrt(v/r.size());
    }

    private static boolean between(double x,double a,double b){return x>=a&&x<=b;}
    private static int clamp(int x){return Math.max(0,Math.min(100,x));}
}
