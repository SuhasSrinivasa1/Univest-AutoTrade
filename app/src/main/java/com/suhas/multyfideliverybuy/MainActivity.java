package com.suhas.multyfideliverybuy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 9, 14);
    private static final int CARD = Color.rgb(14, 23, 33);
    private static final int CARD2 = Color.rgb(20, 32, 44);
    private static final int TEXT = Color.rgb(241, 246, 250);
    private static final int MUTED = Color.rgb(153, 169, 183);
    private static final int ACCENT = Color.rgb(53, 224, 193);
    private static final int BLUE = Color.rgb(92, 132, 255);
    private static final int WARN = Color.rgb(255, 190, 90);
    private static final int BAD = Color.rgb(255, 112, 112);
    private static final int REQUEST_EXPORT = 8200;

    private Switch liveModeSwitch, armSwitch, averagingSwitch;
    private EditText totpTokenInput, totpSecretInput, expectedIpInput;
    private TextView liveBanner, sourceStatus, connectionStatus, lastAction, holdingsStatus, recentEvents, listenerStatus;
    private TextView signalsToday, tradesToday, errorsToday, archiveCount;
    private File pendingExport;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshStatus();
            handler.postDelayed(this, 3000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        loadSettings();
        requestNotificationPermissionIfNeeded();
        new Thread(() -> {
            try { UnivestManager.migrateLegacyRules(getApplicationContext()); }
            catch (Throwable t) { DiagnosticsStore.error(getApplicationContext(), "MIGRATION_ERROR", "", "Rule migration failed.", t); }
        }, "univest-v21-migration").start();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(refresher);
        refresher.run();
        // Broker truth is authoritative in LIVE mode. Reconcile once per screen resume, not every UI refresh,
        // to avoid unnecessary API traffic/rate-limit pressure.
        new Thread(() -> {
            try { UnivestManager.reconcileAll(getApplicationContext()); }
            catch (Throwable t) { DiagnosticsStore.error(getApplicationContext(), "RESUME_RECONCILIATION_ERROR", "", "Broker reconciliation failed.", t); }
            runOnUiThread(this::refreshStatus);
        }, "univest-v21-reconcile").start();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(text("UNIVEST AUTOTRADE", 27, TEXT, true));
        root.addView(text("v2.2.0 • BROKER TRUTH EXECUTION", 12, ACCENT, true), margins(0,4,0,3));
        root.addView(text("Official Univest signals → NSE equity → CNC delivery only", 14, MUTED, false), margins(0,0,0,10));
        liveBanner = text("PAPER MODE\nNO REAL ORDERS", 18, TEXT, true);
        liveBanner.setGravity(Gravity.CENTER);
        liveBanner.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(liveBanner, margins(0,0,0,14));

        LinearLayout contract = card();
        contract.addView(section("LOCKED TRADING CONTRACT"));
        sourceStatus = text("Source: official Univest app only", 14, TEXT, true);
        contract.addView(sourceStatus, margins(0,8,0,2));
        contract.addView(text("Package source lock: com.univest.capp", 12, MUTED, false));
        contract.addView(text("Product lock: NSE CASH • CNC DELIVERY ONLY • no intraday", 13, ACCENT, true), margins(0,5,0,0));
        contract.addView(text("New eligible ≤3-month equity pick: ₹20,000 initial buy", 13, TEXT, false), margins(0,8,0,0));
        contract.addView(text("Back-in-range: broker flat → ₹20,000 missed entry; holding exists → +₹5,000 CNC", 13, TEXT, false));
        contract.addView(text("Controlled downward averaging: ₹5,000 at -2%, -4%, -6% from initial fill", 13, TEXT, false));
        contract.addView(text("Official book-profit / exit: cancel tracked averaging orders and sell full broker CNC holding", 13, TEXT, false));
        root.addView(contract, margins(0,0,0,12));

        LinearLayout controls = card();
        controls.addView(section("EXECUTION MODE & CONTROL"));
        liveModeSwitch = new Switch(this);
        liveModeSwitch.setText("LIVE TRADING — REAL CNC ORDERS");
        liveModeSwitch.setTextColor(TEXT);
        liveModeSwitch.setTextSize(16);
        liveModeSwitch.setPadding(0,dp(7),0,dp(7));
        liveModeSwitch.setOnCheckedChangeListener((b, checked) -> onModeChanged(checked));
        controls.addView(liveModeSwitch);
        controls.addView(text("OFF = PAPER simulation. Changing PAPER/LIVE automatically DISARMS the app.", 12, MUTED, false));

        averagingSwitch = new Switch(this);
        averagingSwitch.setText("ENABLE CONTROLLED DOWNWARD AVERAGING");
        averagingSwitch.setTextColor(TEXT);
        averagingSwitch.setTextSize(15);
        averagingSwitch.setPadding(0,dp(10),0,dp(4));
        averagingSwitch.setOnCheckedChangeListener((b, checked) -> {
            AppPrefs.setAveragingEnabled(this, checked);
            AppPrefs.setUnivestEnabled(this, false);
            DiagnosticsStore.runtime(this, "AVERAGING_SETTING_CHANGED", "", "Downward averaging " + (checked ? "ENABLED" : "DISABLED") + "; automation disarmed for explicit re-arm.");
            refreshStatus();
        });
        controls.addView(averagingSwitch);
        controls.addView(text("Enabled: broker-hosted CNC GTT buys of about ₹5,000 each at -2%, -4%, -6%. Maximum 3 levels per campaign.", 12, MUTED, false));

        armSwitch = new Switch(this);
        armSwitch.setText("ARM UNIVEST AUTOTRADE");
        armSwitch.setTextColor(TEXT);
        armSwitch.setTextSize(17);
        armSwitch.setPadding(0,dp(12),0,dp(7));
        armSwitch.setOnCheckedChangeListener((b, checked) -> onArmRequested(checked));
        controls.addView(armSwitch);

        connectionStatus = text("Groww connection status", 13, MUTED, false);
        controls.addView(connectionStatus, margins(0,5,0,0));
        listenerStatus = text("Notification access status", 13, MUTED, false);
        controls.addView(listenerStatus, margins(0,4,0,0));
        Button access = button("OPEN NOTIFICATION ACCESS", BLUE);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        controls.addView(access, margins(0,10,0,0));
        root.addView(controls, margins(0,0,0,12));

        LinearLayout status = card();
        status.addView(section("BROKER-RECONCILED STATUS"));
        lastAction = text("No action yet.", 14, TEXT, false);
        status.addView(lastAction, margins(0,8,0,0));
        holdingsStatus = text("No tracked Univest campaigns.", 13, MUTED, false);
        status.addView(holdingsStatus, margins(0,10,0,0));
        status.addView(text("Order safety uses Groww holdings/open orders + broker order-reference idempotency. Notification history/similarity and local ACTIVE state never block a broker-flat valid entry.", 12, MUTED, false), margins(0,10,0,0));
        root.addView(status, margins(0,0,0,12));

        LinearLayout signals = card();
        signals.addView(section("TODAY'S TRADING SIGNALS"));
        signals.addView(text("Only actionable Univest equity signals appear here. Marketing, index, commodity and options notifications are still archived for export but do not clutter this view.", 12, MUTED, false), margins(0,7,0,8));
        archiveCount = text("Official Univest notifications archived today: 0", 12, ACCENT, true);
        signals.addView(archiveCount);
        signalsToday = text("No trading-signal notifications recorded today.", 13, TEXT, false);
        signals.addView(signalsToday, margins(0,9,0,0));
        root.addView(signals, margins(0,0,0,12));

        LinearLayout trades = card();
        trades.addView(section("TODAY'S TRADE JOURNAL"));
        trades.addView(text("PAPER/LIVE buy, re-entry, averaging and sell outcomes. A new IST trading day starts with a clean on-screen view; historical files remain exportable.", 12, MUTED, false), margins(0,7,0,8));
        tradesToday = text("No trade events recorded today.", 13, TEXT, false);
        trades.addView(tradesToday);
        root.addView(trades, margins(0,0,0,12));

        LinearLayout errors = card();
        errors.addView(section("TODAY'S ERROR DIAGNOSTICS"));
        errors.addView(text("Readiness blocks, symbol mapping failures, broker failures and runtime exceptions appear here.", 12, MUTED, false), margins(0,7,0,8));
        errorsToday = text("No errors recorded today.", 13, WARN, false);
        errors.addView(errorsToday);
        root.addView(errors, margins(0,0,0,12));

        LinearLayout diagnostics = card();
        diagnostics.addView(section("DIAGNOSTICS & EXPORT"));
        diagnostics.addView(text("The UI resets by IST date, but historical daily notification/trade/broker/error files are retained in the export. Groww TOTP token, secret, generated OTP and access token are never exported.", 12, MUTED, false), margins(0,7,0,10));
        Button export = button("EXPORT COMPLETE DEBUG ZIP", ACCENT);
        export.setTextColor(Color.rgb(3,12,12));
        export.setOnClickListener(v -> createExport());
        diagnostics.addView(export);
        recentEvents = text("No runtime events recorded today.", 12, MUTED, false);
        diagnostics.addView(recentEvents, margins(0,12,0,0));
        root.addView(diagnostics, margins(0,0,0,12));

        LinearLayout settings = card();
        settings.addView(section("GROWW TOTP CONNECTION"));
        settings.addView(text("Use the Groww TOTP token plus its Base32 secret. The app reuses a cached access token first to avoid unnecessary authentication calls/rate limits.", 12, MUTED, false), margins(0,7,0,8));
        totpTokenInput = input("Groww TOTP token (not API key)", true);
        settings.addView(totpTokenInput);
        totpSecretInput = input("TOTP Base32 secret", true);
        settings.addView(totpSecretInput, margins(0,8,0,0));
        expectedIpInput = input("Groww-whitelisted static public IP", false);
        settings.addView(expectedIpInput, margins(0,8,0,0));
        Button save = button("SAVE CONNECTION SETTINGS", BLUE);
        save.setOnClickListener(v -> saveSettings());
        settings.addView(save, margins(0,10,0,0));
        Button test = button("TEST CONNECTION & REFRESH AUTH", ACCENT);
        test.setTextColor(Color.rgb(3,12,12));
        test.setOnClickListener(v -> testConnection(test));
        settings.addView(test, margins(0,8,0,0));
        Button instruments = button("REFRESH NSE INSTRUMENT MAP", CARD2);
        instruments.setOnClickListener(v -> refreshInstruments(instruments));
        settings.addView(instruments, margins(0,8,0,0));
        root.addView(settings);

        root.addView(text("v2.2 architecture: broker truth > local state • every official Univest signal evaluated • deterministic broker order references • daily operational logs + historical archive • fixed 3-level controlled averaging.", 11, MUTED, false), margins(2,14,2,0));
        return scroll;
    }

    private void onModeChanged(boolean live) {
        AppPrefs.setExecutionMode(this, live ? AppPrefs.MODE_LIVE : AppPrefs.MODE_PAPER);
        AppPrefs.setUnivestEnabled(this, false);
        String msg = "Execution mode changed to " + (live ? "LIVE" : "PAPER") + "; automation DISARMED for explicit confirmation.";
        AppPrefs.setUnivestStatus(this, msg);
        DiagnosticsStore.runtime(this, "EXECUTION_MODE_CHANGED", "", msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void onArmRequested(boolean checked) {
        if (checked && AppPrefs.isLiveMode(this) && !AppPrefs.isReadyForBuy(this)) {
            setArmSilently(false);
            AppPrefs.setUnivestEnabled(this, false);
            String msg = "LIVE ARM blocked. Run TEST CONNECTION first; Groww auth + static IP must be READY.";
            DiagnosticsStore.error(this, "LIVE_ARM_READINESS_BLOCK", "", msg, null);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        AppPrefs.setUnivestEnabled(this, checked);
        String mode = AppPrefs.getExecutionMode(this);
        String msg = checked ? "UNIVEST AUTOTRADE ARMED • " + mode + " mode • source + CNC locks active." : "UNIVEST AUTOTRADE DISARMED.";
        AppPrefs.setUnivestStatus(this, msg);
        DiagnosticsStore.runtime(this, checked ? "ARMED" : "DISARMED", "", msg);
        refreshStatus();
    }

    private void loadSettings() {
        totpTokenInput.setText(AppPrefs.getApiKey(this));
        totpSecretInput.setText(AppPrefs.getTotpSecret(this));
        expectedIpInput.setText(AppPrefs.getExpectedStaticIp(this));
    }

    private void saveSettings() {
        AppPrefs.setApiKey(this, totpTokenInput.getText().toString());
        AppPrefs.setTotpSecret(this, totpSecretInput.getText().toString());
        AppPrefs.setExpectedStaticIp(this, expectedIpInput.getText().toString());
        AppPrefs.invalidateConnectionReadiness(this);
        AppPrefs.setUnivestEnabled(this, false);
        DiagnosticsStore.runtime(this, "CONNECTION_SETTINGS_CHANGED", "", "Connection readiness invalidated. Run TEST CONNECTION before LIVE arming.");
        Toast.makeText(this, "Saved. Run TEST CONNECTION before LIVE arming.", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void testConnection(Button button) {
        long cooldown = AppPrefs.getAuthCooldownUntil(this);
        if (cooldown > System.currentTimeMillis()) {
            long secs = Math.max(1L, (cooldown - System.currentTimeMillis() + 999L) / 1000L);
            Toast.makeText(this, "Groww auth cooldown active. Retry in about " + secs + " seconds.", Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        button.setText("TESTING…");
        new Thread(() -> {
            NetworkCheck.Result ip = NetworkCheck.detectAndCompare(getApplicationContext());
            GrowwClient.Result auth = ip.match
                    ? GrowwClient.refreshAndTestAuthentication(getApplicationContext())
                    : new GrowwClient.Result(false,false,0,"Authentication not tested because static IP does not match.");
            DiagnosticsStore.broker(getApplicationContext(), "STATIC_IP_TEST", "", ip.match, ip.message);
            DiagnosticsStore.broker(getApplicationContext(), "GROWW_AUTH_TEST", "", auth.success, auth.message);
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("TEST CONNECTION & REFRESH AUTH");
                refreshStatus();
                Toast.makeText(this, ip.message + "\n" + auth.message, Toast.LENGTH_LONG).show();
            });
        }, "univest-connection-test").start();
    }

    private void refreshInstruments(Button button) {
        button.setEnabled(false);
        button.setText("REFRESHING…");
        new Thread(() -> {
            boolean changed = InstrumentRepository.refreshIfStale(getApplicationContext());
            List<InstrumentRepository.Instrument> list = InstrumentRepository.load(getApplicationContext());
            DiagnosticsStore.runtime(getApplicationContext(), "INSTRUMENT_MAP_REFRESH", "", "NSE CASH instruments available: " + list.size() + (changed ? " • downloaded fresh map" : " • cache/current asset used"));
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("REFRESH NSE INSTRUMENT MAP");
                Toast.makeText(this, "Instrument map ready: " + list.size() + " NSE CASH symbols.", Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }, "univest-instrument-refresh").start();
    }

    private void createExport() {
        Toast.makeText(this, "Preparing diagnostic ZIP…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                pendingExport = DiagnosticsStore.createExport(getApplicationContext());
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, pendingExport.getName());
                    startActivityForResult(i, REQUEST_EXPORT);
                });
            } catch (Exception e) {
                DiagnosticsStore.error(getApplicationContext(), "EXPORT_FAILED", "", "Unable to create diagnostics ZIP.", e);
                runOnUiThread(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "univest-export").start();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null || pendingExport == null) return;
        Uri uri = data.getData();
        try (FileInputStream in = new FileInputStream(pendingExport); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Cannot open selected export destination.");
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b,0,n);
            out.flush();
            Toast.makeText(this, "Diagnostic ZIP exported. Upload it here for full analysis.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to save export: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        boolean live = AppPrefs.isLiveMode(this);
        boolean enabled = AppPrefs.isUnivestEnabled(this);
        boolean averaging = AppPrefs.isAveragingEnabled(this);

        liveModeSwitch.setOnCheckedChangeListener(null);
        liveModeSwitch.setChecked(live);
        liveModeSwitch.setOnCheckedChangeListener((b,c) -> onModeChanged(c));
        averagingSwitch.setOnCheckedChangeListener(null);
        averagingSwitch.setChecked(averaging);
        averagingSwitch.setOnCheckedChangeListener((b,c) -> {
            AppPrefs.setAveragingEnabled(this,c); AppPrefs.setUnivestEnabled(this,false);
            DiagnosticsStore.runtime(this,"AVERAGING_SETTING_CHANGED","","Downward averaging " + (c ? "ENABLED" : "DISABLED") + "; automation disarmed for explicit re-arm.");
            refreshStatus();
        });
        setArmSilently(enabled);

        sourceStatus.setText("Source: LOCKED ✓ • official Univest app only");
        sourceStatus.setTextColor(ACCENT);
        boolean ready = AppPrefs.isReadyForBuy(this);
        String mode = live ? "LIVE" : "PAPER";
        boolean liveReady = live && enabled && ready;
        liveBanner.setText(liveReady ? "LIVE ORDERS ENABLED\nREAL MONEY / CNC DELIVERY"
                : (live ? "LIVE MODE — DISARMED / NOT READY\nNO NEW BUY ORDERS" : "PAPER MODE\nNO REAL ORDERS"));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(dp(14));
        bannerBg.setColor(liveReady ? Color.rgb(150, 35, 35) : (live ? Color.rgb(88, 58, 18) : Color.rgb(18, 74, 67)));
        liveBanner.setBackground(bannerBg);
        connectionStatus.setText("Mode: " + mode + " • Groww: " + (ready ? "READY ✓" : "NOT READY")
                + " • Static IP " + (AppPrefs.isStaticIpMatch(this) ? "MATCH ✓" : "not confirmed")
                + "\n" + AppPrefs.getAuthTestMessage(this));
        connectionStatus.setTextColor(live ? (ready ? ACCENT : WARN) : ACCENT);

        boolean listener = notificationAccessEnabled();
        listenerStatus.setText("Notification access: " + (listener ? "ENABLED ✓" : "NOT ENABLED"));
        listenerStatus.setTextColor(listener ? ACCENT : BAD);

        String last = AppPrefs.getUnivestStatus(this);
        long at = AppPrefs.getUnivestStatusTime(this);
        lastAction.setText((enabled ? "ARMED" : "DISARMED") + " • " + mode + "\n" + last + (at > 0 ? "\n" + stamp(at) : ""));

        List<UnivestStateStore.State> states = UnivestStateStore.all(this);
        StringBuilder sb = new StringBuilder();
        for (UnivestStateStore.State s : states) {
            if (s == null || s.symbol == null || s.symbol.isEmpty() || UnivestStateStore.EXITED.equals(s.phase)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s.symbol).append(" • ").append(s.phase).append(" • broker-tracked qty ").append(s.quantity);
            if (s.anchorPrice > 0) sb.append(" • anchor ₹").append(String.format(Locale.US,"%.2f",s.anchorPrice));
            if (averaging && s.anchorPrice > 0) {
                sb.append("\nAvg ladder: ");
                appendAvg(sb, 1, s.averageGtt1Id, s.averageGtt1Price);
                appendAvg(sb, 2, s.averageGtt2Id, s.averageGtt2Price);
                appendAvg(sb, 3, s.averageGtt3Id, s.averageGtt3Price);
            }
        }
        if (sb.length() == 0) sb.append("No active tracked Univest campaigns. LIVE entries reconcile against Groww holdings/open orders before any buy.");
        holdingsStatus.setText(sb.toString());

        archiveCount.setText("Official Univest notifications archived today: " + DiagnosticsStore.todayNotificationCount(this));
        signalsToday.setText(DiagnosticsStore.todayTradingSignals(this, 8));
        tradesToday.setText(DiagnosticsStore.todayTrades(this, 8));
        errorsToday.setText(DiagnosticsStore.todayErrors(this, 8));
        recentEvents.setText(DiagnosticsStore.recent(this, 7));
    }

    private void appendAvg(StringBuilder sb, int level, String id, double price) {
        if (level > 1) sb.append(" • ");
        sb.append("-").append(level * 2).append("% ");
        if (id != null && !id.isEmpty()) sb.append("ARMED");
        else sb.append("not armed");
        if (price > 0) sb.append(" @₹").append(String.format(Locale.US,"%.2f",price));
    }

    private void setArmSilently(boolean checked) {
        armSwitch.setOnCheckedChangeListener(null);
        armSwitch.setChecked(checked);
        armSwitch.setOnCheckedChangeListener((b,c) -> onArmRequested(c));
    }

    private boolean notificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 700);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14),dp(14),dp(14),dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(16)); g.setStroke(dp(1), Color.rgb(31,48,62));
        l.setBackground(g);
        return l;
    }
    private TextView section(String s) { TextView v=text(s,12,ACCENT,true); v.setLetterSpacing(0.08f); return v; }
    private TextView text(String s, int sp, int color, boolean bold) { TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0,1.12f); if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v; }
    private EditText input(String hint, boolean secret) { EditText e=new EditText(this); e.setHint(hint); e.setHintTextColor(Color.rgb(105,123,138)); e.setTextColor(TEXT); e.setTextSize(14); e.setSingleLine(true); e.setPadding(dp(12),dp(10),dp(12),dp(10)); GradientDrawable g=new GradientDrawable(); g.setColor(CARD2); g.setCornerRadius(dp(10)); g.setStroke(dp(1),Color.rgb(44,63,78)); e.setBackground(g); if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); return e; }
    private Button button(String label, int color) { Button b=new Button(this); b.setText(label); b.setTextSize(13); b.setTextColor(TEXT); b.setAllCaps(false); GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(11)); b.setBackground(g); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private String stamp(long ms) { return new SimpleDateFormat("dd MMM yyyy • hh:mm:ss a", Locale.US).format(new Date(ms)); }
}
