package com.suhas.multyfideliverybuy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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

public class DashboardActivity extends Activity {
    private static final int BG = Color.rgb(7, 15, 27);
    private static final int NAV_BG = Color.rgb(8, 18, 31);
    private static final int SURFACE = Color.rgb(14, 27, 43);
    private static final int SURFACE_2 = Color.rgb(18, 35, 54);
    private static final int BORDER = Color.rgb(35, 61, 83);
    private static final int TEXT = Color.rgb(244, 249, 252);
    private static final int SUBTEXT = Color.rgb(166, 191, 209);
    private static final int TEAL = Color.rgb(28, 201, 184);
    private static final int BLUE = Color.rgb(82, 145, 255);
    private static final int GREEN = Color.rgb(75, 220, 158);
    private static final int AMBER = Color.rgb(255, 185, 92);
    private static final int RED = Color.rgb(255, 108, 124);
    private static final int REQUEST_EXPORT = 8240;
    private static final int REQUEST_HISTORY_CREATE = 8241;
    private static final int REQUEST_HISTORY_RESTORE = 8242;

    private FrameLayout contentHost;
    private LinearLayout bottomNav;
    private int selectedTab = 0;
    private File pendingExport;
    private long lastBrokerSyncAt = 0L;
    private String selectedResearchSymbol = "";
    private String selectedResearchAction = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(NAV_BG);
        ResearchScheduler.ensureScheduled(getApplicationContext());
        ResearchMonitorScheduler.ensureScheduled(getApplicationContext());
        PreMarketReadinessScheduler.ensureScheduled(getApplicationContext());
        UnivestHistoryDb.ensureInitialized(getApplicationContext());
        DurableOfficialSignalQueue.recoverPending(getApplicationContext());
        OfficialSignalRecoveryScheduler.scheduleNow(getApplicationContext());
        requestNotificationPermissionIfNeeded();
        applyIntent(getIntent());
        setContentView(buildShell());

        new Thread(() -> {
            ResearchDiagnosticsImporter.importOfficialSignals(getApplicationContext());
            try { UnivestManager.migrateLegacyRules(getApplicationContext()); } catch (Throwable ignored) {}
            try {
                UnivestManager.reconcileAll(getApplicationContext());
                lastBrokerSyncAt = System.currentTimeMillis();
            } catch (Throwable t) {
                DiagnosticsStore.error(getApplicationContext(), "DASHBOARD_INITIAL_RECONCILE_FAILED", "",
                        "Initial broker reconciliation failed.", t);
            }
            try { ResearchOrchestrator.tick(getApplicationContext()); } catch (Throwable ignored) {}
            runOnUiThread(this::render);
        }, "univest-final-init").start();

        render();
    }

    @Override protected void onResume() {
        super.onResume();
        ResearchScheduler.ensureScheduled(getApplicationContext());
        ResearchMonitorScheduler.ensureScheduled(getApplicationContext());
        PreMarketReadinessScheduler.ensureScheduled(getApplicationContext());
        UnivestHistoryDb.ensureInitialized(getApplicationContext());
        DurableOfficialSignalQueue.recoverPending(getApplicationContext());
        if (DurableOfficialSignalQueue.pendingCount(getApplicationContext()) > 0)
            OfficialSignalRecoveryScheduler.scheduleNow(getApplicationContext());
        new Thread(() -> {
            try { ResearchOrchestrator.tick(getApplicationContext()); }
            catch (Throwable ignored) {}
            runOnUiThread(this::render);
        }, "research-orchestrator-resume").start();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        render();
    }

    private void applyIntent(Intent intent) {
        if (intent == null) return;
        selectedTab = intent.getIntExtra("open_tab", selectedTab);
        String symbol = intent.getStringExtra("research_symbol");
        String action = intent.getStringExtra("research_action");
        if (symbol != null && !symbol.trim().isEmpty()) selectedResearchSymbol = symbol.trim().toUpperCase(Locale.US);
        if (action != null && !action.trim().isEmpty()) selectedResearchAction = action.trim().toUpperCase(Locale.US);
    }

    private View buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);

        contentHost = new FrameLayout(this);
        shell.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(8), dp(6), dp(8), dp(8));
        bottomNav.setBackgroundColor(NAV_BG);
        shell.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(80)));
        return shell;
    }

    private void render() {
        if (contentHost == null) return;
        contentHost.removeAllViews();
        if (selectedTab == 0) contentHost.addView(buildUnivestTab());
        else if (selectedTab == 1) contentHost.addView(buildStrategyTab());
        else if (selectedTab == 2) contentHost.addView(buildForecastTab());
        else contentHost.addView(buildSettingsTab());
        renderBottomNav();
    }

    private View buildUnivestTab() {
        ScrollView scroll = baseScroll();
        LinearLayout root = scrollRoot(scroll);
        root.addView(appHeader("UNIVEST", "Official signal execution"));

        root.addView(healthCard(), margins(0, 20, 0, 14));

        Button sync = secondaryButton("SYNC BROKER STATUS");
        sync.setOnClickListener(v -> syncBrokerStatus(sync));
        root.addView(sync, fixedMargins(-1, 52, 0, 0, 0, 14));

        root.addView(executionSummaryCard(), margins(0, 0, 0, 14));

        LinearLayout campaigns = card();
        campaigns.addView(sectionRow("BROKER-RECONCILED STATUS", "Today"));
        campaigns.addView(body(activeCampaignSummary()), margins(0, 12, 0, 0));
        root.addView(campaigns, margins(0, 0, 0, 14));

        LinearLayout signals = card();
        signals.addView(sectionRow("TODAY'S UNIVEST SIGNALS", String.valueOf(DiagnosticsStore.todayNotificationCount(this))));
        signals.addView(body(DiagnosticsStore.todayTradingSignals(this, 6)), margins(0, 12, 0, 0));
        root.addView(signals, margins(0, 0, 0, 14));

        LinearLayout trades = card();
        trades.addView(sectionRow("TRADE JOURNAL", AppPrefs.getExecutionMode(this)));
        trades.addView(body(DiagnosticsStore.todayTrades(this, 6)), margins(0, 12, 0, 0));
        root.addView(trades, margins(0, 0, 0, 22));
        return scroll;
    }

    private View buildStrategyTab() {
        ScrollView scroll = baseScroll();
        LinearLayout root = scrollRoot(scroll);
        root.addView(appHeader("STRATEGY", "Recommendation DNA & champions"));

        LinearLayout status = card();
        status.addView(sectionRow("RESEARCH ENGINE", lastResearchTime()));
        status.addView(body(AppPrefs.getResearchStatus(this)), margins(0, 12, 0, 0));
        status.addView(meta("Off-market only • target run around 17:30 IST"), margins(0, 8, 0, 0));
        status.addView(meta("Scheduler: " + ResearchScheduler.statusText(this),
                AppPrefs.getResearchScheduleMethod(this).startsWith("JOB_") ? GREEN : AMBER), margins(0, 8, 0, 0));
        root.addView(status, margins(0, 20, 0, 14));

        LinearLayout orchestration = card();
        orchestration.addView(sectionRow("RESEARCH ORCHESTRATION", AppPrefs.getResearchOrchestratorStage(this)));
        orchestration.addView(body(AppPrefs.getResearchOrchestratorStatus(this)), margins(0, 12, 0, 0));
        orchestration.addView(meta("17:30 full-NSE scan → 08:45+ pre-open freeze → live minute capture → post-close replay. NSE holidays retain the latest forecast."), margins(0, 8, 0, 0));
        root.addView(orchestration, margins(0, 0, 0, 14));

        LinearLayout accuracy = card();
        accuracy.addView(sectionRow("RESEARCH ACCURACY", "Shadow + Live"));
        accuracy.addView(body(ResearchTradeEngine.accuracyText(this)), margins(0, 12, 0, 0));
        accuracy.addView(meta("Wins require ≥0.5% estimated net profit. Open trades are unresolved, not losses."), margins(0, 8, 0, 0));
        root.addView(accuracy, margins(0, 0, 0, 14));

        LinearLayout failures = card();
        failures.addView(sectionRow("REPEATED FAILURE CLUSTERS", "Replay"));
        failures.addView(body(ResearchTradeEngine.failureClustersText(this)), margins(0, 12, 0, 0));
        failures.addView(meta("A repeated bucket becomes a Challenger-review candidate; one bad trade never rewrites the Champion."), margins(0, 8, 0, 0));
        root.addView(failures, margins(0, 0, 0, 14));

        Button scan = primaryButton("RUN FULL NSE OFF-MARKET RESEARCH");
        scan.setOnClickListener(v -> runResearchNow(scan));
        root.addView(scan, fixedMargins(-1, 54, 0, 0, 0, 14));

        LinearLayout champions = card();
        champions.addView(sectionRow("UNIVEST PLAYBOOK CHAMPIONS", "Top 5 composites"));
        champions.addView(body(ResearchEngine.playbooksText(this)), margins(0, 12, 0, 0));
        champions.addView(meta("Playbooks are non-exclusive combinations. One stock can strongly match several at once; multiple agreeing playbooks increase the forecast vote rather than forcing one family label."), margins(0, 8, 0, 0));
        root.addView(champions, margins(0, 0, 0, 14));

        LinearLayout components = card();
        components.addView(sectionRow("COMPONENT EVIDENCE", "Building blocks"));
        components.addView(body(ResearchEngine.strategiesText(this)), margins(0, 12, 0, 0));
        components.addView(meta("These are component scores used inside composite playbooks, not mutually-exclusive strategy families."), margins(0, 8, 0, 0));
        root.addView(components, margins(0, 0, 0, 14));

        LinearLayout accountability = card();
        accountability.addView(sectionRow("PRE-UNIVEST PREDICTION SCORECARD", "Out-of-sample"));
        accountability.addView(body(ResearchEngine.forecastAccountabilityText(this)), margins(0, 12, 0, 0));
        accountability.addView(meta("Primary test: was the eventual official Univest ENTRY already in a frozen Top 10 before the notification? Historical explanation alone does not count as a prediction."), margins(0, 8, 0, 0));
        root.addView(accountability, margins(0, 0, 0, 14));

        LinearLayout archive = card();
        archive.addView(sectionRow("RECOMMENDATION ARCHIVE", "1–3 month"));
        archive.addView(body(ResearchStore.recentRecommendationsText(this, 10)), margins(0, 12, 0, 0));
        root.addView(archive, margins(0, 0, 0, 14));

        LinearLayout dna = card();
        dna.addView(sectionRow("BUY → SELL DNA", "Pattern learning"));
        dna.addView(body("Captures causality-safe point-in-time ENTRY/EXIT profiles, raw 1m/15m/daily candles, volume, VWAP, momentum, volatility, live quote/depth, market context and matched non-selected controls. Composite playbooks learn recurring combinations rather than one-label families."), margins(0, 12, 0, 0));
        root.addView(dna, margins(0, 0, 0, 14));

        LinearLayout lifecycle = card();
        lifecycle.addView(sectionRow("OFFICIAL ENTRY → EXIT LIFECYCLES", "Reverse engineering"));
        lifecycle.addView(body(ResearchEngine.officialLifecycleText(this, 8)), margins(0, 12, 0, 0));
        root.addView(lifecycle, margins(0, 0, 0, 14));

        LinearLayout study = card();
        study.addView(sectionRow("NIGHTLY UNIVEST STRATEGY STUDY", "Off-market"));
        study.addView(body(AppPrefs.getUnivestStrategyStudy(this)), margins(0, 12, 0, 0));
        study.addView(meta("Descriptive reverse engineering only: it learns recurring observable fingerprints from official calls and keeps pre-signal features separate from later outcomes."), margins(0, 8, 0, 0));
        root.addView(study, margins(0, 0, 0, 14));

        LinearLayout intel = card();
        intel.addView(sectionRow("MARKET INTELLIGENCE", "India + Global"));
        intel.addView(body(ResearchEngine.intelligenceText(this)), margins(0, 12, 0, 0));
        root.addView(intel, margins(0, 0, 0, 22));
        return scroll;
    }

    private View buildForecastTab() {
        ScrollView scroll = baseScroll();
        LinearLayout root = scrollRoot(scroll);
        root.addView(appHeader("FORECAST", "Next expected Univest-like picks"));

        LinearLayout intro = card();
        intro.addView(sectionRow("FORECAST ENGINE", lastResearchTime()));
        intro.addView(body("Scans the full eligible NSE CASH universe off-market, freezes the Top 10, then monitors those candidates live for entry/exit timing."), margins(0, 12, 0, 0));
        intro.addView(meta(AppPrefs.isResearchAutoTradeEnabled(this)
                ? "Research AutoTrade ON • qualified Research entries/exits may place real CNC orders"
                : "Research AutoTrade OFF • entry/exit notifications require your confirmation"), margins(0, 8, 0, 0));
        root.addView(intro, margins(0, 20, 0, 14));

        String actionSymbol = !selectedResearchSymbol.isEmpty() ? selectedResearchSymbol : ResearchTradeEngine.actionSymbol(this);
        String actionType = !selectedResearchAction.isEmpty() ? selectedResearchAction : ResearchTradeEngine.actionType(this);
        if (!actionSymbol.isEmpty() && ("BUY".equals(actionType) || "SELL".equals(actionType))) {
            LinearLayout actionCard = card();
            actionCard.addView(sectionRow("RESEARCH TRADE ACTION", actionType));
            actionCard.addView(body(actionSymbol + " • " + ("BUY".equals(actionType)
                    ? "Entry condition reached. Order uses your configured Initial Entry Budget."
                    : "Exit model detected a profitable weakening condition.")), margins(0, 10, 0, 0));
            Button actionButton = primaryButton(("BUY".equals(actionType) ? "BUY " + formatRupees(AppPrefs.getUnivestBudget(this))
                    : "SELL RESEARCH LOT") + " • " + actionSymbol);
            actionButton.setOnClickListener(v -> executeResearchAction(actionButton, actionSymbol, actionType));
            actionCard.addView(actionButton, fixedMargins(-1, 54, 0, 12, 0, 0));
            root.addView(actionCard, margins(0, 0, 0, 14));
        }

        LinearLayout activeResearch = card();
        activeResearch.addView(sectionRow("ACTIVE RESEARCH TRADES", AppPrefs.isResearchAutoTradeEnabled(this) ? "AUTO" : "MANUAL"));
        activeResearch.addView(body(ResearchTradeEngine.activePositionsText(this)), margins(0, 12, 0, 0));
        root.addView(activeResearch, margins(0, 0, 0, 14));

        LinearLayout forecastScore = card();
        forecastScore.addView(sectionRow("FORECAST ACCOUNTABILITY", "Before Univest"));
        forecastScore.addView(body(ResearchEngine.forecastAccountabilityText(this)), margins(0, 12, 0, 0));
        root.addView(forecastScore, margins(0, 0, 0, 14));

        LinearLayout expected = card();
        expected.addView(sectionRow("NEXT EXPECTED RECOMMENDATIONS", "Top 10"));
        expected.addView(body(ResearchEngine.predictionsText(this, 10)), margins(0, 12, 0, 0));
        root.addView(expected, margins(0, 0, 0, 14));

        LinearLayout ranges = card();
        ranges.addView(sectionRow("ENTRY / EXIT RANGE", "Model"));
        ranges.addView(body("The frozen sell zone is a reference, not a forced target. After entry, the Exit Model waits for ≥0.5% estimated net profit and weakening/exhaustion evidence; temporary drawdowns are tracked as MAE rather than automatically treated as failures."), margins(0, 12, 0, 0));
        root.addView(ranges, margins(0, 0, 0, 14));

        Button scan = primaryButton("REFRESH FORECAST OFF-MARKET");
        scan.setOnClickListener(v -> runResearchNow(scan));
        root.addView(scan, fixedMargins(-1, 54, 0, 0, 0, 22));
        return scroll;
    }

    private View buildSettingsTab() {
        ScrollView scroll = baseScroll();
        LinearLayout root = scrollRoot(scroll);
        root.addView(appHeader("SETTINGS", "Connection, safety & maintenance"));

        LinearLayout connection = card();
        connection.addView(sectionRow("GROWW CONNECTION", AppPrefs.isReadyForBuy(this) ? "READY" : "NOT READY"));

        EditText token = input("Groww TOTP token", true);
        token.setText(AppPrefs.getApiKey(this));
        connection.addView(token, margins(0, 14, 0, 0));

        EditText secret = input("TOTP Base32 secret", true);
        secret.setText(AppPrefs.getTotpSecret(this));
        connection.addView(secret, margins(0, 10, 0, 0));

        EditText ip = input("Whitelisted static public IP", false);
        ip.setText(AppPrefs.getExpectedStaticIp(this));
        connection.addView(ip, margins(0, 10, 0, 0));

        Button save = secondaryButton("SAVE CONNECTION SETTINGS");
        save.setOnClickListener(v -> saveSettings(token, secret, ip));
        connection.addView(save, fixedMargins(-1, 50, 0, 12, 0, 0));

        Button test = primaryButton("TEST CONNECTION & AUTH");
        test.setOnClickListener(v -> testConnection(test));
        connection.addView(test, fixedMargins(-1, 52, 0, 10, 0, 0));
        root.addView(connection, margins(0, 20, 0, 14));

        LinearLayout safety = card();
        safety.addView(sectionRow("EXECUTION SAFETY", AppPrefs.getExecutionMode(this)));

        Switch live = styledSwitch("LIVE MODE — REAL CNC ORDERS", AppPrefs.isLiveMode(this));
        Switch avg = styledSwitch("CONTROLLED DOWNWARD AVERAGING", AppPrefs.isAveragingEnabled(this));
        Switch arm = styledSwitch("ARM UNIVEST AUTOTRADE", AppPrefs.isUnivestEnabled(this));
        Switch researchAuto = styledSwitch("RESEARCH AUTOTRADE — REAL MONEY", AppPrefs.isResearchAutoTradeEnabled(this));

        safety.addView(budgetSlider(
                "INITIAL ENTRY BUDGET",
                "Used for a new eligible 1–3 month recommendation and a missed initial entry.",
                AppPrefs.getUnivestBudget(this),
                true,
                arm), margins(0, 12, 0, 0));

        safety.addView(budgetSlider(
                "RE-ENTRY + AVERAGING BUDGET",
                "One shared amount for an idea back in entry range and for each -2% / -4% / -6% averaging level.",
                AppPrefs.getUnivestAddBudget(this),
                false,
                arm), margins(0, 12, 0, 0));

        safety.addView(meta("₹0 disables that buy leg. Range: ₹0–₹1,00,000 in ₹1,000 steps."), margins(0, 10, 0, 0));
        safety.addView(meta("Budget changes apply to new orders and newly-created averaging GTTs. Existing broker-hosted averaging GTTs are left unchanged for safety."), margins(0, 6, 0, 10));

        safety.addView(live, margins(0, 2, 0, 0));
        safety.addView(avg, margins(0, 0, 0, 0));
        safety.addView(arm, margins(0, 0, 0, 0));
        safety.addView(researchAuto, margins(0, 0, 0, 0));

        live.setOnCheckedChangeListener((b, checked) -> {
            AppPrefs.setExecutionMode(this, checked ? AppPrefs.MODE_LIVE : AppPrefs.MODE_PAPER);
            AppPrefs.setUnivestEnabled(this, false);
            AppPrefs.setUnivestStatus(this, "Execution mode changed to " + (checked ? "LIVE" : "PAPER") + "; automation DISARMED.");
            DiagnosticsStore.runtime(this, "EXECUTION_MODE_CHANGED", "", AppPrefs.getUnivestStatus(this));
            Toast.makeText(this, "Mode changed. AutoTrade disarmed for safety.", Toast.LENGTH_LONG).show();
            render();
        });

        avg.setOnCheckedChangeListener((b, checked) -> {
            AppPrefs.setAveragingEnabled(this, checked);
            AppPrefs.setUnivestEnabled(this, false);
            DiagnosticsStore.runtime(this, "AVERAGING_SETTING_CHANGED", "",
                    "Averaging " + (checked ? "enabled" : "disabled") + "; automation disarmed.");
            Toast.makeText(this, "Averaging changed. AutoTrade disarmed for safety.", Toast.LENGTH_LONG).show();
            render();
        });

        arm.setOnCheckedChangeListener((b, checked) -> onArmRequested(checked));

        researchAuto.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) {
                AppPrefs.setResearchAutoTradeEnabled(this, false);
                DiagnosticsStore.runtime(this, "RESEARCH_AUTOTRADE_OFF", "", "Research AutoTrade disabled.");
                return;
            }
            if (!AppPrefs.isLiveMode(this) || !AppPrefs.isReadyForBuy(this)) {
                AppPrefs.setResearchAutoTradeEnabled(this, false);
                Toast.makeText(this, "Research AutoTrade requires LIVE mode plus current Groww/static-IP readiness.", Toast.LENGTH_LONG).show();
                render();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Enable Research AutoTrade?")
                    .setMessage("Qualified Research entry/exit signals may place real NSE CASH/CNC orders using the same configured budgets. Research execution now reconciles broker fills/GTTs and committed capital, but timing still depends on the Android market-session monitor. Official Univest AutoTrade remains a separate signal source.")
                    .setNegativeButton("Cancel", (d, w) -> render())
                    .setPositiveButton("Enable", (d, w) -> {
                        AppPrefs.setResearchAutoTradeEnabled(this, true);
                        DiagnosticsStore.runtime(this, "RESEARCH_AUTOTRADE_ON", "",
                                "Research AutoTrade enabled with shared configured budgets.");
                        render();
                    }).show();
        });

        safety.addView(researchCapitalGovernor(), margins(0, 12, 0, 0));
        safety.addView(meta("Research capital governor applies only to Research-originated live entries. Official Univest confirmation remains separately attributed and follows the proven official execution path."), margins(0, 8, 0, 0));

        safety.addView(meta("Official source only • NSE CASH • CNC delivery"), margins(0, 10, 0, 0));
        safety.addView(meta(formatRupees(AppPrefs.getUnivestBudget(this)) + " initial • "
                + formatRupees(AppPrefs.getUnivestAddBudget(this)) + " re-entry / each averaging level • -2% / -4% / -6% ladder"),
                margins(0, 6, 0, 0));
        root.addView(safety, margins(0, 0, 0, 14));

        LinearLayout scheduler = card();
        boolean scheduleOk = AppPrefs.getResearchScheduleMethod(this).startsWith("JOB_");
        scheduler.addView(sectionRow("RESEARCH SCHEDULER", scheduleOk ? "ACTIVE" : "NEEDS ATTENTION"));
        scheduler.addView(meta(ResearchScheduler.statusText(this), scheduleOk ? GREEN : AMBER), margins(0, 10, 0, 0));
        scheduler.addView(meta("Orchestrator: " + ResearchOrchestrator.statusText(this)), margins(0, 8, 0, 0));
        scheduler.addView(meta("Pre-market: " + PreMarketReadiness.statusText(this),
                AppPrefs.isPreMarketReady(this) ? GREEN : AMBER), margins(0, 8, 0, 0));
        scheduler.addView(meta("Trading-day targets: 08:25 IST warm-up + 08:55 IST final readiness/freeze. Live EXIT still refreshes only broker quantity + executable quote because those cannot be safely pre-cached."), margins(0, 8, 0, 0));
        Button repair = secondaryButton("REPAIR / RESCHEDULE RESEARCH");
        repair.setOnClickListener(v -> repairSchedule(repair));
        scheduler.addView(repair, fixedMargins(-1, 50, 0, 12, 0, 0));
        scheduler.addView(meta("Live monitor: " + (ResearchMonitorScheduler.isActive(this) ? "ACTIVE (~15 min cadence)" : "NOT ACTIVE"), 
                ResearchMonitorScheduler.isActive(this) ? GREEN : AMBER), margins(0, 8, 0, 0));
        scheduler.addView(meta("Historical scheduler errors remain in diagnostics even after a successful repair."), margins(0, 8, 0, 0));
        root.addView(scheduler, margins(0, 0, 0, 14));

        LinearLayout permissions = card();
        boolean notif = notificationAccessEnabled();
        permissions.addView(sectionRow("NOTIFICATION ACCESS", notif ? "ENABLED" : "REQUIRED"));
        permissions.addView(meta(notif ? "Official Univest notifications can be received." : "Enable notification-listener access before arming.",
                notif ? GREEN : AMBER), margins(0, 10, 0, 0));
        Button access = secondaryButton("OPEN NOTIFICATION ACCESS");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        permissions.addView(access, fixedMargins(-1, 50, 0, 12, 0, 0));
        root.addView(permissions, margins(0, 0, 0, 14));

        LinearLayout device = card();
        boolean batteryFree = DeviceReliability.isIgnoringBatteryOptimizations(this);
        boolean vivo = DeviceReliability.isVivoFamily();
        device.addView(sectionRow(vivo ? "VIVO / FUNTOUCH RELIABILITY" : "DEVICE RELIABILITY",
                batteryFree ? "BATTERY UNRESTRICTED" : "ACTION RECOMMENDED"));
        device.addView(meta(DeviceReliability.deviceLabel()
                + " • Notification listener: " + listenerHealthText()
                + " • Battery optimization: " + (batteryFree ? "ignored" : "active"),
                (notificationAccessEnabled() && batteryFree) ? GREEN : AMBER), margins(0, 10, 0, 0));
        device.addView(meta("For reliable Univest notifications, keep notification access enabled and allow unrestricted background activity. These controls do not change trading rules."), margins(0, 8, 0, 0));
        device.addView(meta("Durable official queue: " + DurableOfficialSignalQueue.statusText(this),
                DurableOfficialSignalQueue.pendingCount(this) == 0 ? GREEN : AMBER), margins(0, 8, 0, 0));

        Button battery = secondaryButton(batteryFree ? "BATTERY EXEMPTION ALREADY ACTIVE" : "ALLOW UNRESTRICTED BATTERY");
        battery.setEnabled(!batteryFree);
        battery.setOnClickListener(v -> {
            if (!DeviceReliability.requestBatteryOptimizationExemption(this))
                Toast.makeText(this, "Open App info → Battery and choose unrestricted/background allowed.", Toast.LENGTH_LONG).show();
        });
        device.addView(battery, fixedMargins(-1, 50, 0, 12, 0, 0));

        Button vivoBg = secondaryButton(vivo ? "OPEN VIVO AUTOSTART / BACKGROUND SETTINGS" : "OPEN APP BACKGROUND SETTINGS");
        vivoBg.setOnClickListener(v -> {
            boolean opened = vivo ? DeviceReliability.openVivoBackgroundSettings(this) : DeviceReliability.openAppDetails(this);
            if (!opened) Toast.makeText(this, "Unable to open device background settings automatically.", Toast.LENGTH_LONG).show();
        });
        device.addView(vivoBg, fixedMargins(-1, 50, 0, 10, 0, 0));

        Button rebind = secondaryButton("REFRESH NOTIFICATION LISTENER");
        rebind.setOnClickListener(v -> {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                        new android.content.ComponentName(this, MultyfiNotificationService.class));
                Toast.makeText(this, "Notification-listener rebind requested.", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Open Notification Access and toggle Univest AutoTrade off/on once.", Toast.LENGTH_LONG).show();
            }
        });
        device.addView(rebind, fixedMargins(-1, 50, 0, 10, 0, 0));
        device.addView(meta("On Vivo/iQOO: allow Auto-start/Background activity for Univest AutoTrade and avoid one-tap cleaners that revoke background permissions."), margins(0, 8, 0, 0));
        root.addView(device, margins(0, 0, 0, 14));

        LinearLayout data = card();
        data.addView(sectionRow("DATA & DIAGNOSTICS", "Maintenance"));

        Button instruments = secondaryButton("REFRESH NSE INSTRUMENT MAP");
        instruments.setOnClickListener(v -> refreshInstruments(instruments));
        data.addView(instruments, fixedMargins(-1, 50, 0, 10, 0, 0));

        Button export = secondaryButton("EXPORT COMPLETE DEBUG ZIP");
        export.setOnClickListener(v -> createExport());
        data.addView(export, fixedMargins(-1, 50, 0, 10, 0, 0));

        data.addView(text("PERSISTENT HISTORY", 12, SUBTEXT, true), margins(0, 16, 0, 6));
        data.addView(meta("SQLite ledger: " + UnivestHistoryDb.count(this) + " events • " + HistoryBackupManager.statusText(this),
                HistoryBackupManager.isConnected(this) ? GREEN : AMBER), margins(0, 0, 0, 8));
        data.addView(meta("The portable ZIP excludes Groww/TOTP credentials. It survives app uninstall, but Android revokes the file permission on uninstall: after a new-signature install, select the same ZIP once with RESTORE to reconnect it."), margins(0, 0, 0, 8));

        Button historyCreate = secondaryButton(HistoryBackupManager.isConnected(this)
                ? "WRITE / RECONNECT PORTABLE HISTORY BACKUP"
                : "CREATE PORTABLE HISTORY BACKUP");
        historyCreate.setOnClickListener(v -> createHistoryBackup());
        data.addView(historyCreate, fixedMargins(-1, 50, 0, 10, 0, 0));

        Button historyRestore = secondaryButton("RESTORE / RECONNECT HISTORY BACKUP");
        historyRestore.setOnClickListener(v -> restoreHistoryBackup());
        data.addView(historyRestore, fixedMargins(-1, 50, 0, 10, 0, 0));

        data.addView(text("Recent errors", 12, SUBTEXT, true), margins(0, 16, 0, 6));
        data.addView(text(DiagnosticsStore.todayErrors(this, 5), 12, TEXT, false));
        root.addView(data, margins(0, 0, 0, 14));

        LinearLayout about = card();
        about.addView(sectionRow("ABOUT", "Orchestrated Research"));
        about.addView(body("Univest AutoTrade v2.8.4"), margins(0, 10, 0, 0));
        about.addView(meta("Package: com.suhas.multyfideliverybuy"), margins(0, 6, 0, 0));
        about.addView(meta("Official Univest execution and Research decisions remain separately attributed; Research→Univest same-symbol confirmation is intentionally additive."), margins(0, 6, 0, 0));
        root.addView(about, margins(0, 0, 0, 22));

        return scroll;
    }

    private View appHeader(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, 30, TEXT, true));
        left.addView(text(subtitle, 12, SUBTEXT, false), margins(0, 3, 0, 0));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView version = text("v2.8.4", 11, TEAL, true);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(Color.rgb(12, 54, 58));
        chip.setCornerRadius(dp(14));
        chip.setStroke(dp(1), Color.rgb(28, 107, 109));
        version.setBackground(chip);
        row.addView(version, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View healthCard() {
        LinearLayout c = card();
        c.addView(sectionRow("SYSTEM HEALTH", lastBrokerSyncAt > 0 ? "Synced " + clock(lastBrokerSyncAt) : "Not synced"));

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(badge("Groww", AppPrefs.isReadyForBuy(this)));
        badges.addView(badge("Univest", notificationAccessEnabled()), badgeLp());
        badges.addView(badge("Research", AppPrefs.getResearchScheduleMethod(this).startsWith("JOB_")
                && ResearchMonitorScheduler.isActive(this)), badgeLp());
        c.addView(badges, margins(0, 14, 0, 0));

        c.addView(meta("Static IP " + (AppPrefs.isStaticIpMatch(this) ? "matched" : "not confirmed")
                + " • " + activeCampaignCount() + " active campaigns"
                + " • " + DiagnosticsStore.todayNotificationCount(this) + " notifications today"
                + " • pre-market " + (AppPrefs.isPreMarketReady(this) ? "ready" : "not ready")), margins(0, 12, 0, 0));

        boolean liveArmed = AppPrefs.isLiveMode(this) && AppPrefs.isUnivestEnabled(this);
        c.addView(statusPill(liveArmed ? "LIVE ORDERS ENABLED" : "SAFE / DISARMED", liveArmed ? GREEN : BLUE),
                margins(0, 12, 0, 0));
        return c;
    }

    private View executionSummaryCard() {
        LinearLayout c = card();
        c.addView(sectionRow("EXECUTION PROFILE", AppPrefs.getExecutionMode(this)));
        c.addView(body(AppPrefs.isUnivestEnabled(this) ? "AutoTrade is ARMED" : "AutoTrade is DISARMED"), margins(0, 10, 0, 0));
        c.addView(meta(formatRupees(AppPrefs.getUnivestBudget(this)) + " initial • "
                + formatRupees(AppPrefs.getUnivestAddBudget(this))
                + " back-in-range / each averaging level at -2% / -4% / -6%"), margins(0, 8, 0, 0));
        c.addView(meta("Official book-profit/exit cancels tracked averaging orders and sells the actual broker CNC holding."), margins(0, 6, 0, 0));

        Button manage = secondaryButton("MANAGE EXECUTION SETTINGS");
        manage.setOnClickListener(v -> {
            selectedTab = 3;
            render();
        });
        c.addView(manage, fixedMargins(-1, 50, 0, 12, 0, 0));
        return c;
    }

    private void syncBrokerStatus(Button button) {
        button.setEnabled(false);
        button.setText("SYNCING…");
        new Thread(() -> {
            try {
                UnivestManager.reconcileAll(getApplicationContext());
                lastBrokerSyncAt = System.currentTimeMillis();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Broker status synchronized.", Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Throwable t) {
                DiagnosticsStore.error(getApplicationContext(), "DASHBOARD_RECONCILE_FAILED", "",
                        "Broker reconciliation failed.", t);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Broker sync failed: " + safeMessage(t), Toast.LENGTH_LONG).show();
                    render();
                });
            }
        }, "univest-broker-sync").start();
    }

    private void repairSchedule(Button button) {
        button.setEnabled(false);
        button.setText("SCHEDULING…");
        new Thread(() -> {
            ResearchScheduler.ensureScheduled(getApplicationContext());
            ResearchMonitorScheduler.ensureScheduled(getApplicationContext());
            ResearchOrchestrator.tick(getApplicationContext());
            runOnUiThread(() -> {
                Toast.makeText(this, ResearchScheduler.statusText(this), Toast.LENGTH_LONG).show();
                render();
            });
        }, "research-schedule-repair").start();
    }

    private void onArmRequested(boolean checked) {
        if (checked && AppPrefs.isLiveMode(this) && !AppPrefs.isReadyForBuy(this)) {
            AppPrefs.setUnivestEnabled(this, false);
            DiagnosticsStore.error(this, "LIVE_ARM_READINESS_BLOCK", "",
                    "LIVE ARM blocked. Test Groww connection and static IP first.", null);
            Toast.makeText(this, "LIVE blocked: test Groww connection and static IP first.", Toast.LENGTH_LONG).show();
            render();
            return;
        }

        if (checked && AppPrefs.isLiveMode(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Arm LIVE Univest AutoTrade?")
                    .setMessage("This permits real-money NSE CASH / CNC orders from official Univest notifications. Research forecasts remain non-executing.")
                    .setNegativeButton("Cancel", (d, w) -> render())
                    .setPositiveButton("Arm LIVE", (d, w) -> {
                        AppPrefs.setUnivestEnabled(this, true);
                        AppPrefs.setUnivestStatus(this, "UNIVEST AUTOTRADE ARMED • LIVE mode • source + CNC locks active.");
                        DiagnosticsStore.runtime(this, "ARMED", "", AppPrefs.getUnivestStatus(this));
                        render();
                    }).show();
            return;
        }

        AppPrefs.setUnivestEnabled(this, checked);
        AppPrefs.setUnivestStatus(this,
                checked ? "UNIVEST AUTOTRADE ARMED • PAPER mode." : "UNIVEST AUTOTRADE DISARMED.");
        DiagnosticsStore.runtime(this, checked ? "ARMED" : "DISARMED", "", AppPrefs.getUnivestStatus(this));
        render();
    }

    private void saveSettings(EditText token, EditText secret, EditText ip) {
        AppPrefs.setApiKey(this, token.getText().toString());
        AppPrefs.setTotpSecret(this, secret.getText().toString());
        AppPrefs.setExpectedStaticIp(this, ip.getText().toString());
        AppPrefs.invalidateConnectionReadiness(this);
        AppPrefs.setUnivestEnabled(this, false);
        DiagnosticsStore.runtime(this, "CONNECTION_SETTINGS_CHANGED", "",
                "Connection settings changed; readiness invalidated and automation disarmed.");
        Toast.makeText(this, "Saved. Run connection test before LIVE arming.", Toast.LENGTH_LONG).show();
        render();
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
                    : new GrowwClient.Result(false, false, 0,
                    "Authentication not tested because static IP does not match.");
            DiagnosticsStore.broker(getApplicationContext(), "STATIC_IP_TEST", "", ip.match, ip.message);
            DiagnosticsStore.broker(getApplicationContext(), "GROWW_AUTH_TEST", "", auth.success, auth.message);
            runOnUiThread(() -> {
                Toast.makeText(this, ip.message + "\n" + auth.message, Toast.LENGTH_LONG).show();
                render();
            });
        }, "dashboard-connection-test").start();
    }

    private void refreshInstruments(Button button) {
        button.setEnabled(false);
        button.setText("REFRESHING…");
        new Thread(() -> {
            boolean changed = InstrumentRepository.refreshIfStale(getApplicationContext());
            List<InstrumentRepository.Instrument> list = InstrumentRepository.load(getApplicationContext());
            DiagnosticsStore.runtime(getApplicationContext(), "INSTRUMENT_MAP_REFRESH", "",
                    "NSE CASH instruments available: " + list.size() + (changed ? " • fresh download" : " • cache/asset"));
            runOnUiThread(() -> {
                Toast.makeText(this, "Instrument map ready: " + list.size() + " NSE CASH symbols.", Toast.LENGTH_LONG).show();
                render();
            });
        }, "dashboard-instrument-refresh").start();
    }

    private void runResearchNow(Button button) {
        long now = System.currentTimeMillis();
        if (!NseTradingCalendar.isTradingDay(now)) {
            Toast.makeText(this, "NSE closed: " + NseTradingCalendar.describe(now)
                    + ". Latest frozen forecast is retained for the next trading session.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ResearchEngine.isOffMarketNowIst()) {
            Toast.makeText(this, "Research is locked during regular NSE market hours.", Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        button.setText("RUNNING…");
        new Thread(() -> {
            ResearchEngine.runNightly(getApplicationContext());
            AppPrefs.setResearchEodScanKey(getApplicationContext(), NseTradingCalendar.dayKey(System.currentTimeMillis()));
            runOnUiThread(() -> {
                Toast.makeText(this, "Full-NSE off-market research completed.", Toast.LENGTH_SHORT).show();
                render();
            });
        }, "research-manual").start();
    }

    private void executeResearchAction(Button button, String symbol, String action) {
        button.setEnabled(false);
        button.setText(("BUY".equals(action) ? "BUYING " : "SELLING ") + symbol + "…");
        new Thread(() -> {
            String result = "BUY".equals(action)
                    ? ResearchTradeEngine.executeBuy(getApplicationContext(), symbol, false)
                    : ResearchTradeEngine.executeSell(getApplicationContext(), symbol, false, "Manual confirmation from Research Exit Ready.");
            runOnUiThread(() -> {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                selectedResearchSymbol = "";
                selectedResearchAction = "";
                render();
            });
        }, "research-manual-action").start();
    }

    private void renderBottomNav() {
        bottomNav.removeAllViews();
        bottomNav.addView(navItem(R.drawable.ic_univest_nav, "Univest", 0), navParams());
        bottomNav.addView(navItem(R.drawable.ic_strategy_nav, "Strategy", 1), navParams());
        bottomNav.addView(navItem(R.drawable.ic_forecast_nav, "Forecast", 2), navParams());
        bottomNav.addView(navItem(R.drawable.ic_settings_nav, "Settings", 3), navParams());
    }

    private View navItem(int iconRes, String label, int tab) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setMinimumHeight(dp(64));
        box.setContentDescription(label + (selectedTab == tab ? ", selected" : ""));

        boolean selected = selectedTab == tab;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? Color.rgb(15, 53, 62) : Color.TRANSPARENT);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(selected ? TEAL : SUBTEXT, PorterDuff.Mode.SRC_IN);
        icon.setContentDescription(null);
        box.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView labelView = text(label, 11, selected ? TEXT : SUBTEXT, selected);
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView, margins(0, 4, 0, 0));

        box.setOnClickListener(v -> {
            if (selectedTab == tab) return;
            selectedTab = tab;
            render();
        });
        return box;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private View badge(String label, boolean ok) {
        TextView v = text(label + " " + (ok ? "✓" : "—"), 11,
                ok ? Color.rgb(5, 30, 27) : SUBTEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ok ? Color.rgb(94, 226, 193) : SURFACE_2);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), ok ? Color.rgb(94, 226, 193) : BORDER);
        v.setBackground(g);
        return v;
    }

    private LinearLayout.LayoutParams badgeLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(8), 0, 0, 0);
        return p;
    }

    private View statusPill(String label, int color) {
        TextView v = text(label, 12, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color == GREEN ? Color.rgb(19, 75, 62) : Color.rgb(23, 58, 94));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), color);
        v.setBackground(g);
        return v;
    }

    private String activeCampaignSummary() {
        List<UnivestStateStore.State> states = UnivestStateStore.all(this);
        StringBuilder b = new StringBuilder();
        for (UnivestStateStore.State s : states) {
            if (s == null || s.symbol == null || s.symbol.isEmpty() ||
                    UnivestStateStore.EXITED.equals(s.phase)) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(s.symbol).append(" • ").append(s.phase).append(" • qty ").append(s.quantity);
            if (s.anchorPrice > 0) b.append(String.format(Locale.US, " • anchor ₹%.2f", s.anchorPrice));
        }
        return b.length() == 0
                ? "No active tracked campaigns. Groww holdings and open orders remain execution truth."
                : b.toString();
    }

    private int activeCampaignCount() {
        int n = 0;
        for (UnivestStateStore.State s : UnivestStateStore.all(this)) {
            if (s != null && s.symbol != null && !s.symbol.isEmpty() &&
                    !UnivestStateStore.EXITED.equals(s.phase)) n++;
        }
        return n;
    }

    private String listenerHealthText() {
        if (!notificationAccessEnabled()) return "ACCESS OFF";
        long t = AppPrefs.getNotificationListenerHeartbeat(this);
        String state = AppPrefs.getNotificationListenerState(this);
        if (t <= 0L) return "GRANTED • waiting for listener connection";
        long age = Math.max(0L, System.currentTimeMillis() - t);
        if (age < 5L * 60L * 1000L) return state + " • heartbeat " + (age / 1000L) + "s ago";
        return state + " • last heartbeat " + (age / 60000L) + " min ago";
    }

    private boolean notificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4310);
        }
    }

    private String lastResearchTime() {
        long t = AppPrefs.getResearchLastNightlyRun(this);
        return t > 0 ? formatTime(t, "dd MMM • HH:mm") : "Not run";
    }

    private String clock(long t) {
        return formatTime(t, "HH:mm:ss");
    }

    private String formatTime(long t, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date(t));
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void createHistoryBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_TITLE, "Univest-History-Portable-v2.8.4.zip");
        startActivityForResult(i, REQUEST_HISTORY_CREATE);
    }

    private void restoreHistoryBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQUEST_HISTORY_RESTORE);
    }

    private void persistHistoryGrant(Intent data, Uri uri) {
        if (uri == null) return;
        int flags = data == null ? 0 : data.getFlags();
        int take = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (take == 0) take = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(uri, take); }
        catch (Throwable ignored) {}
        HistoryBackupManager.rememberUri(this, uri);
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
                runOnUiThread(() ->
                        Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "dashboard-export").start();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQUEST_HISTORY_CREATE) {
            persistHistoryGrant(data, uri);
            Toast.makeText(this, "Writing portable history backup…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    HistoryBackupManager.exportToUri(getApplicationContext(), uri);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Portable history backup connected and written.", Toast.LENGTH_LONG).show();
                        render();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "History backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "history-backup-create").start();
            return;
        }

        if (requestCode == REQUEST_HISTORY_RESTORE) {
            persistHistoryGrant(data, uri);
            Toast.makeText(this, "Restoring Univest history…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    int imported = HistoryBackupManager.importFromUri(getApplicationContext(), uri);
                    UnivestStrategyStudy.runNightly(getApplicationContext());
                    HistoryBackupManager.forceAutoBackup(getApplicationContext());
                    runOnUiThread(() -> {
                        Toast.makeText(this, "History restored/reconnected • " + imported + " new ledger events.", Toast.LENGTH_LONG).show();
                        render();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "History restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "history-backup-restore").start();
            return;
        }

        if (requestCode != REQUEST_EXPORT || pendingExport == null) return;
        try (FileInputStream in = new FileInputStream(pendingExport);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Cannot open selected export destination.");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.flush();
            Toast.makeText(this, "Diagnostic ZIP exported.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to save export: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ScrollView baseScroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        return s;
    }

    private LinearLayout scrollRoot(ScrollView scroll) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return root;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), BORDER);
        l.setBackground(g);
        return l;
    }

    private View sectionRow(String title, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = text(right, 11, SUBTEXT, false);
        r.setGravity(Gravity.END);
        row.addView(r, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView body(String s) {
        return text(s, 13, TEXT, false);
    }

    private TextView meta(String s) {
        return meta(s, SUBTEXT);
    }

    private TextView meta(String s, int color) {
        return text(s, 12, color, false);
    }

    private View researchCapitalGovernor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE_2);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);

        box.addView(text("RESEARCH CAPITAL GOVERNOR", 13, TEXT, true));
        box.addView(meta("Caps Research-originated live exposure and simultaneous Research positions. Filled entries plus active/filled Research averaging commitments count toward the ceiling. Changing either setting disables Research AutoTrade."), margins(0, 6, 0, 8));
        box.addView(meta("Currently committed: " + formatRupees(ResearchTradeEngine.committedCapital(this)),
                ResearchTradeEngine.committedCapital(this) > AppPrefs.getResearchCapitalLimit(this) ? RED : GREEN),
                margins(0, 0, 0, 8));

        TextView capValue = text(formatRupees(AppPrefs.getResearchCapitalLimit(this)), 14, TEAL, true);
        box.addView(capValue);
        SeekBar cap = new SeekBar(this);
        cap.setMax(49); // ₹10,000 to ₹5,00,000 in ₹10,000 steps
        cap.setProgress(Math.max(0, AppPrefs.getResearchCapitalLimit(this) / 10000 - 1));
        box.addView(cap, new LinearLayout.LayoutParams(-1, dp(44)));
        box.addView(meta("Capital limit • ₹10,000–₹5,00,000"), margins(0, 0, 0, 8));

        TextView posValue = text("Max open Research positions: " + AppPrefs.getResearchMaxPositions(this), 13, TEAL, true);
        box.addView(posValue);
        SeekBar positions = new SeekBar(this);
        positions.setMax(4); // 1..5
        positions.setProgress(AppPrefs.getResearchMaxPositions(this) - 1);
        box.addView(positions, new LinearLayout.LayoutParams(-1, dp(44)));

        cap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                capValue.setText(formatRupees((progress + 1) * 10000));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int value = (bar.getProgress() + 1) * 10000;
                AppPrefs.setResearchCapitalLimit(DashboardActivity.this, value);
                Toast.makeText(DashboardActivity.this, "Research capital limit saved. Research AutoTrade disabled for review.", Toast.LENGTH_LONG).show();
                render();
            }
        });

        positions.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                posValue.setText("Max open Research positions: " + (progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                AppPrefs.setResearchMaxPositions(DashboardActivity.this, bar.getProgress() + 1);
                AppPrefs.setResearchAutoTradeEnabled(DashboardActivity.this, false);
                Toast.makeText(DashboardActivity.this, "Research position limit saved. Research AutoTrade disabled for review.", Toast.LENGTH_LONG).show();
                render();
            }
        });
        return box;
    }

    private View budgetSlider(String title, String description, int currentBudget,
                              boolean initialEntry, Switch armSwitch) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE_2);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text(title, 13, TEXT, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView value = text(formatRupees(currentBudget), 15, TEAL, true);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        box.addView(row);

        box.addView(meta(description), margins(0, 6, 0, 4));

        SeekBar seek = new SeekBar(this);
        seek.setMax(AppPrefs.UNIVEST_BUDGET_MAX / AppPrefs.UNIVEST_BUDGET_STEP);
        seek.setProgress(AppPrefs.normalizeUnivestBudget(currentBudget) / AppPrefs.UNIVEST_BUDGET_STEP);
        seek.setContentDescription(title + ", " + formatRupees(currentBudget));
        box.addView(seek, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);
        TextView zero = meta("₹0");
        TextView max = meta("₹1,00,000");
        max.setGravity(Gravity.END);
        limits.addView(zero, new LinearLayout.LayoutParams(0, -2, 1f));
        limits.addView(max, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(limits);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int amount = progress * AppPrefs.UNIVEST_BUDGET_STEP;
                value.setText(formatRupees(amount));
                bar.setContentDescription(title + ", " + formatRupees(amount));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {
                int amount = AppPrefs.normalizeUnivestBudget(bar.getProgress() * AppPrefs.UNIVEST_BUDGET_STEP);
                int old = initialEntry ? AppPrefs.getUnivestBudget(DashboardActivity.this)
                        : AppPrefs.getUnivestAddBudget(DashboardActivity.this);
                if (amount == old) return;

                if (initialEntry) AppPrefs.setUnivestBudget(DashboardActivity.this, amount);
                else AppPrefs.setUnivestAddBudget(DashboardActivity.this, amount);

                boolean wasArmed = AppPrefs.isUnivestEnabled(DashboardActivity.this);
                AppPrefs.setUnivestEnabled(DashboardActivity.this, false);
                AppPrefs.setResearchAutoTradeEnabled(DashboardActivity.this, false);
                String event = initialEntry ? "INITIAL_BUDGET_CHANGED" : "ADD_BUDGET_CHANGED";
                String detail = (initialEntry ? "Initial entry" : "Re-entry/averaging")
                        + " budget changed from " + formatRupees(old) + " to " + formatRupees(amount)
                        + "; Univest AutoTrade and Research AutoTrade disarmed for safety.";
                AppPrefs.setUnivestStatus(DashboardActivity.this, detail);
                DiagnosticsStore.runtime(DashboardActivity.this, event, "", detail);

                if (wasArmed && armSwitch.isChecked()) armSwitch.setChecked(false);
                Toast.makeText(DashboardActivity.this,
                        "Saved " + formatRupees(amount) + ". AutoTrade is disarmed; re-arm when ready.",
                        Toast.LENGTH_LONG).show();
            }
        });
        return box;
    }

    private String formatRupees(int amount) {
        java.text.NumberFormat f = java.text.NumberFormat.getIntegerInstance(new Locale("en", "IN"));
        return "₹" + f.format(Math.max(0, amount));
    }

    private Switch styledSwitch(String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(14);
        s.setTextColor(TEXT);
        s.setChecked(checked);
        s.setMinHeight(dp(52));
        s.setGravity(Gravity.CENTER_VERTICAL);
        return s;
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(111, 139, 160));
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setMinHeight(dp(52));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE_2);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), BORDER);
        e.setBackground(g);
        return e;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0, 1.16f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(4, 25, 27));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(48));
        GradientDrawable g = new GradientDrawable();
        g.setColor(TEAL);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(48));
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE_2);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), BLUE);
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams fixedMargins(int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? -1 : dp(width), dp(height));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
