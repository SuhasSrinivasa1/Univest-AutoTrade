package com.suhas.multyfideliverybuy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ResearchActivity extends Activity {
    private static final int BG=Color.rgb(5,9,14),CARD=Color.rgb(14,23,33),TEXT=Color.rgb(241,246,250),MUTED=Color.rgb(153,169,183);
    private static final int ACCENT=Color.rgb(53,224,193),BLUE=Color.rgb(92,132,255),WARN=Color.rgb(255,190,90);
    private TextView status,nextExpected,archive,strategies,intelligence;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());ResearchScheduler.ensureScheduled(getApplicationContext());refresh();
    }
    @Override protected void onResume(){super.onResume();refresh();}

    private ScrollView buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(18),dp(16),dp(34));scroll.addView(root);
        root.addView(text("UNIVEST RESEARCH LAB",27,TEXT,true));
        root.addView(text("v2.3.0 - OFF-MARKET STRATEGY DISCOVERY",12,ACCENT,true),margins(0,4,0,10));

        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);
        Button auto=button("AUTOTRADE",Color.rgb(35,48,62));auto.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
        Button lab=button("RESEARCH LAB",ACCENT);lab.setTextColor(Color.rgb(3,12,12));lab.setEnabled(false);
        nav.addView(auto,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(8),0,0,0);nav.addView(lab,p);root.addView(nav,margins(0,0,0,14));

        LinearLayout state=card();state.addView(section("RESEARCH STATUS"));status=text("Research Lab has not run yet.",14,TEXT,false);state.addView(status,margins(0,8,0,0));
        state.addView(text("Heavy analysis is hard-blocked during NSE market hours. Scheduled scan: about 5:30 PM IST on trading days.",12,MUTED,false),margins(0,8,0,0));
        Button run=button("RUN OFF-MARKET RESEARCH NOW",BLUE);run.setOnClickListener(v->{if(!ResearchEngine.isOffMarketNowIst()){Toast.makeText(this,"Research is locked during market hours.",Toast.LENGTH_LONG).show();return;}run.setEnabled(false);new Thread(()->{ResearchEngine.runNightly(getApplicationContext());runOnUiThread(()->{run.setEnabled(true);refresh();});},"research-manual").start();});state.addView(run,margins(0,10,0,0));root.addView(state,margins(0,0,0,12));

        LinearLayout expected=card();expected.addView(section("NEXT EXPECTED RECOMMENDATIONS"));expected.addView(text("Ranks NSE candidates by similarity to observed 1-3 month Univest recommendation fingerprints. Predictions never enter the official execution engine.",12,MUTED,false),margins(0,7,0,8));nextExpected=text("No scan yet.",13,TEXT,false);expected.addView(nextExpected);root.addView(expected,margins(0,0,0,12));

        LinearLayout execution=card();execution.addView(section("RESEARCH EXECUTION"));execution.addView(text("Prediction-driven broker execution is not enabled in this build. Use the learned buy/sell zones as research evidence while official Univest AutoTrade remains isolated.",12,WARN,false),margins(0,7,0,0));root.addView(execution,margins(0,0,0,12));

        LinearLayout rec=card();rec.addView(section("RECOMMENDATION ARCHIVE"));rec.addView(text("Historical official notification logs are imported, so eligible 1-3 month calls remain available for strategy learning.",12,MUTED,false),margins(0,7,0,8));archive=text("No recommendations archived yet.",13,TEXT,false);rec.addView(archive);root.addView(rec,margins(0,0,0,12));

        LinearLayout strat=card();strat.addView(section("STRATEGY DNA & CHAMPIONS"));strat.addView(text("Starting families: volume breakout, trend pullback, momentum continuation, quality/re-rating, catalyst/sector. Champion status requires repeated official matches.",12,MUTED,false),margins(0,7,0,8));strategies=text("No fingerprints yet.",13,TEXT,false);strat.addView(strategies);root.addView(strat,margins(0,0,0,12));

        LinearLayout ranges=card();ranges.addView(section("BUY -> SELL DNA"));ranges.addView(text("Each candidate receives an ATR-normalized buy-pattern zone, chase ceiling and provisional sell-pattern zone. These ranges become increasingly data-driven as completed official campaigns accumulate.",12,TEXT,false),margins(0,7,0,0));root.addView(ranges,margins(0,0,0,12));

        LinearLayout intel=card();intel.addView(section("NATIONAL + INTERNATIONAL INTELLIGENCE"));intel.addView(text("Top candidates receive a bounded news pass after technical ranking. News is evidence only and cannot overwrite unavailable data.",12,MUTED,false),margins(0,7,0,8));intelligence=text("News intelligence will populate after the next scan.",13,TEXT,false);intel.addView(intelligence);intel.addView(text("Fundamental ratios are not guessed. A time-stamped fundamentals provider can be connected later without changing official AutoTrade.",12,WARN,false),margins(0,10,0,0));root.addView(intel);

        root.addView(text("Isolation contract: Research Lab cannot block, cancel, alter or consume official Univest AutoTrade signals.",11,MUTED,false),margins(2,14,2,0));
        return scroll;
    }

    private void refresh(){if(status==null)return;status.setText(AppPrefs.getResearchStatus(this));nextExpected.setText(ResearchEngine.predictionsText(this,10));archive.setText(ResearchStore.recentRecommendationsText(this,10));strategies.setText(ResearchEngine.strategiesText(this));intelligence.setText(ResearchEngine.intelligenceText(this));}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(14),dp(14),dp(14),dp(14));GradientDrawable g=new GradientDrawable();g.setColor(CARD);g.setCornerRadius(dp(16));g.setStroke(dp(1),Color.rgb(31,48,62));l.setBackground(g);return l;}
    private TextView section(String s){TextView v=text(s,12,ACCENT,true);v.setLetterSpacing(.08f);return v;}
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.12f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(11));b.setBackground(g);b.setGravity(Gravity.CENTER);return b;}
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
