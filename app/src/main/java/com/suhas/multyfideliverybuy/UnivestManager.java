package com.suhas.multyfideliverybuy;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class UnivestManager {
    static final int ENTRY_BUDGET = 20000;   // legacy/default value; runtime uses AppPrefs
    static final int REENTRY_BUDGET = 5000; // legacy/default value; runtime uses AppPrefs
    static final int AVERAGE_BUDGET = 5000; // legacy/default value; runtime uses AppPrefs
    static final double AVERAGE_STEP_PCT = 2.0;
    static final int MAX_AVERAGE_LEVELS = 3;
    private static final ConcurrentHashMap<String, Object> CAMPAIGN_LOCKS = new ConcurrentHashMap<>();

    private UnivestManager() {}

    static void handle(Context context, UnivestParser.Signal signal, long notificationPostTime) {
        if (signal == null) return;
        if (signal.type == UnivestParser.Type.ENTRY) handleEntry(context, signal, notificationPostTime);
        else if (signal.type == UnivestParser.Type.REENTRY) handleReentry(context, signal, notificationPostTime);
        else handleExit(context, signal, notificationPostTime);
    }

    static void handleEntry(Context context, UnivestParser.Signal signal, long notificationPostTime) {
        InstrumentRepository.Instrument instrument = resolve(context, signal, "ENTRY");
        if (instrument == null) return;
        String symbol = instrument.symbol;
        final int entryBudget = AppPrefs.getUnivestBudget(context);
        if (entryBudget <= 0) {
            String msg = "UNIVEST ENTRY IGNORED • " + symbol + " • initial-entry budget is ₹0 (disabled).";
            DiagnosticsStore.runtime(context, "ENTRY_BUDGET_DISABLED", symbol, msg);
            status(context, msg);
            return;
        }

        if (AppPrefs.isPaperMode(context)) {
            String msg = "PAPER BUY • " + rupees(entryBudget) + " CNC delivery simulation • no Groww order sent.";
            DiagnosticsStore.paperTrade(context, "PAPER_BUY", symbol, msg);
            status(context, "PAPER MODE • UNIVEST ENTRY • " + symbol + " • simulated " + rupees(entryBudget) + " CNC delivery buy. No Groww order sent.");
            return;
        }

        if (!instrument.buyAllowed) { fail(context, "ENTRY_BUY_BLOCKED", symbol, "Groww instrument master marks buy_allowed=0.", null); return; }

        // Every distinct official "New Equity" notification starts a fresh entry cycle. Existing holdings are
        // broker truth to carry forward, not a duplicate gate. Only an actually open broker BUY blocks another BUY.
        GrowwClient.PositionSnapshot before = GrowwClient.getCncPosition(context, symbol);
        if (!before.success) { fail(context, "ENTRY_HOLDING_CHECK_FAILED", symbol, before.message, null); return; }

        GrowwClient.Result pending = GrowwClient.checkForActiveCncBuyOrder(context, symbol);
        if (pending.unknown) { fail(context, "ENTRY_PENDING_ORDER_CHECK_UNKNOWN", symbol, pending.message, null); return; }
        if (!freshEntryMayProceed(before.quantity, pending.success)) {
            status(context, "UNIVEST ENTRY WAITING • " + symbol + " • " + pending.message);
            DiagnosticsStore.runtime(context, "ENTRY_PENDING_BROKER_ORDER", symbol, pending.message);
            return;
        }
        OfficialExecutionLatency.brokerReady(context, signal.type, symbol, notificationPostTime, System.currentTimeMillis());

        UnivestStateStore.State prior = UnivestStateStore.get(context, symbol);
        if (prior != null) {
            // A fresh recommendation gets a fresh averaging ladder. Retire the older ladder first so two
            // recommendation cycles cannot leave overlapping smart BUY triggers behind.
            cancelAveragingLadder(context, prior);
            cancelLegacyTrackedOrders(context, prior);
        }

        UnivestStateStore.State reservation = prior == null ? new UnivestStateStore.State() : prior;
        reservation.symbol = symbol;
        reservation.phase = UnivestStateStore.ENTRY_PENDING;
        reservation.tickSize = instrument.tickSize;
        reservation.quantity = Math.max(0, before.quantity);
        reservation.principal = before.netPrice > 0 ? before.netPrice * Math.max(0, before.quantity) : Math.max(0, reservation.principal);
        reservation.anchorPrice = 0.0; // set from the fresh BUY fill, not from an older cycle
        reservation.averageLevel = 0;
        reservation.reentryUsed = false;
        reservation.exitOrderId = "";
        reservation.exitRequestedQty = 0;
        reservation.lastAction = "Fresh official New Equity recommendation reserved • broker baseline qty "
                + before.quantity + " • configured initial budget " + rupees(entryBudget) + ".";
        UnivestStateStore.put(context, reservation);

        DiagnosticsStore.runtime(context, "ENTRY_ACCEPTED_FRESH_CYCLE", symbol,
                "Fresh official New Equity recommendation accepted regardless of existing broker holding • baseline qty "
                        + before.quantity + " • configured " + rupees(entryBudget) + " CNC delivery budget.");

        String orderRef = stableRef("UE", symbol, signal.rawText, notificationPostTime);
        GrowwClient.ExecutionResult r = GrowwClient.placeUnivestCncMarketBuy(context, symbol, entryBudget, orderRef);
        OfficialExecutionLatency.orderDispatched(context, signal.type, symbol, notificationPostTime, r.dispatchAtMillis);
        if (!r.submitted) {
            // Restore the local campaign from live broker truth instead of marking an existing holding EXITED.
            GrowwClient.PositionSnapshot restore = GrowwClient.getCncPosition(context, symbol);
            if (restore.success && restore.quantity > 0) {
                syncExistingHolding(context, symbol, restore, instrument);
                UnivestStateStore.State restored = UnivestStateStore.get(context, symbol);
                if (restored != null) {
                    restored.lastAction = "Fresh New Equity BUY was not submitted; existing broker holding preserved. " + r.message;
                    UnivestStateStore.put(context, restored);
                }
            } else {
                UnivestStateStore.releasePendingEntry(context, symbol, "Fresh New Equity BUY not submitted: " + r.message);
            }
            fail(context, "ENTRY_NOT_SUBMITTED", symbol, r.message, null);
            DiagnosticsStore.trade(context, "BUY_FAILED", symbol, r.message, r);
            return;
        }

        GrowwClient.PositionSnapshot after = GrowwClient.getCncPosition(context, symbol);
        UnivestStateStore.State state = UnivestStateStore.get(context, symbol);
        if (state == null) { state = new UnivestStateStore.State(); state.symbol = symbol; }
        state.tickSize = instrument.tickSize;
        state.phase = r.filled && r.filledQuantity > 0 ? UnivestStateStore.ACTIVE : UnivestStateStore.ENTRY_PENDING;
        state.anchorPrice = r.averagePrice > 0 ? r.averagePrice : 0.0; // fresh cycle averaging anchor
        if (after.success && after.quantity >= 0) {
            state.quantity = after.quantity;
            state.principal = after.netPrice > 0 ? after.netPrice * Math.max(0, after.quantity)
                    : Math.max(0, before.netPrice) * Math.max(0, before.quantity)
                    + Math.max(0, r.averagePrice) * Math.max(0, r.filledQuantity);
        } else {
            state.quantity = Math.max(0, before.quantity) + Math.max(0, r.filledQuantity);
            state.principal = Math.max(0, before.netPrice) * Math.max(0, before.quantity)
                    + Math.max(0, r.averagePrice) * Math.max(0, r.filledQuantity);
        }
        state.estimatedBuyCharges = state.principal > 0 ? DeliveryNetTarget.buyCharges(state.principal) : 0;
        state.averageLevel = 0;
        state.reentryUsed = false;
        state.exitOrderId = r.orderId;
        state.exitRequestedQty = 0;
        state.lastAction = r.filled
                ? "Fresh " + rupees(entryBudget) + " CNC delivery BUY executed • added qty " + r.filledQuantity
                    + " • broker total qty " + state.quantity + " • fresh anchor ₹" + money(r.averagePrice)
                : "Fresh CNC BUY submitted; fill not yet confirmed • order " + r.orderId
                    + " • broker baseline qty " + before.quantity;
        UnivestStateStore.put(context, state);

        if (r.filled && r.averagePrice > 0) armAveragingLadder(context, state, r.orderId);
        DiagnosticsStore.trade(context, r.filled ? "BUY_EXECUTED_FRESH_CYCLE" : "BUY_SUBMITTED_FILL_UNCONFIRMED",
                symbol, state.lastAction, r);
        long age = r.dispatchAtMillis > 0 && notificationPostTime > 0 ? Math.max(0, r.dispatchAtMillis - notificationPostTime) : -1;
        status(context, "UNIVEST FRESH CNC BUY " + (r.filled ? "EXECUTED" : "SUBMITTED") + " • " + symbol
                + " • " + rupees(entryBudget) + " budget • broker total qty " + state.quantity
                + (age >= 0 ? " • source age " + age + " ms" : "") + " • " + r.message);
    }

    static boolean freshEntryMayProceed(int brokerQuantity, boolean activeBrokerBuy) {
        // brokerQuantity is intentionally not a blocker: a distinct official New Equity call is a new cycle.
        return !activeBrokerBuy;
    }

    static void handleReentry(Context context, UnivestParser.Signal signal, long notificationPostTime) {
        InstrumentRepository.Instrument instrument = resolve(context, signal, "REENTRY");
        if (instrument == null) return;
        String symbol = instrument.symbol;

        final int initialBudget = AppPrefs.getUnivestBudget(context);
        final int addBudget = AppPrefs.getUnivestAddBudget(context);

        if (AppPrefs.isPaperMode(context)) {
            String msg = "PAPER BACK-IN-RANGE • broker-flat would use " + rupees(initialBudget)
                    + " initial/missed-entry budget; held would use " + rupees(addBudget)
                    + " add budget • no Groww order sent.";
            DiagnosticsStore.paperTrade(context, "PAPER_REENTRY", symbol, msg);
            status(context, "PAPER MODE • UNIVEST BACK-IN-RANGE • " + symbol + " • simulated decision only. No Groww order sent.");
            return;
        }

        if (!instrument.buyAllowed) { fail(context, "REENTRY_BUY_BLOCKED", symbol, "Groww instrument master marks buy_allowed=0.", null); return; }
        GrowwClient.PositionSnapshot before = GrowwClient.getCncPosition(context, symbol);
        if (!before.success) { fail(context, "REENTRY_HOLDING_CHECK_FAILED", symbol, before.message, null); return; }

        GrowwClient.Result pending = GrowwClient.checkForActiveCncBuyOrder(context, symbol);
        if (pending.unknown) { fail(context, "REENTRY_PENDING_ORDER_CHECK_UNKNOWN", symbol, pending.message, null); return; }
        if (pending.success) {
            DiagnosticsStore.runtime(context, "REENTRY_PENDING_BROKER_ORDER", symbol, pending.message);
            status(context, "UNIVEST BACK-IN-RANGE • " + symbol + " • no second BUY: broker already has an open CNC BUY.");
            return;
        }
        OfficialExecutionLatency.brokerReady(context, signal.type, symbol, notificationPostTime, System.currentTimeMillis());

        final boolean missedInitial = before.quantity <= 0;
        final int budget = chooseBackInRangeBudget(before.quantity, false, initialBudget, addBudget);
        final String kind = missedInitial ? "MISSED_INITIAL_ENTRY" : "HELD_BACK_IN_RANGE_ADD";
        if (budget <= 0) {
            String msg = "UNIVEST BACK-IN-RANGE IGNORED • " + symbol + " • "
                    + (missedInitial ? "initial-entry" : "re-entry/averaging") + " budget is ₹0 (disabled).";
            DiagnosticsStore.runtime(context, "REENTRY_BUDGET_DISABLED", symbol, msg);
            status(context, msg);
            return;
        }

        if (missedInitial) {
            UnivestStateStore.State stale = UnivestStateStore.get(context, symbol);
            if (stale != null && !UnivestStateStore.EXITED.equals(stale.phase)) {
                cancelAveragingLadder(context, stale);
                stale.phase = UnivestStateStore.EXITED; stale.quantity = 0; stale.principal = 0;
                stale.lastAction = "State repaired from broker truth before back-in-range: no CNC holding and no open CNC BUY.";
                UnivestStateStore.put(context, stale);
                DiagnosticsStore.runtime(context, "STALE_STATE_REPAIRED", symbol, stale.lastAction);
            }
            if (!UnivestStateStore.reserveNewEntry(context, symbol)) {
                fail(context, "REENTRY_RESERVATION_FAILED", symbol, "Unable to reserve missed initial entry after broker reconciliation.", null); return;
            }
        }

        DiagnosticsStore.runtime(context, "REENTRY_ACCEPTED", symbol,
                kind + " • broker qty " + before.quantity + " • selected budget ₹" + budget + " CNC delivery.");
        String orderRef = stableRef(missedInitial ? "UM" : "UR", symbol, signal.rawText, notificationPostTime);
        GrowwClient.ExecutionResult r = GrowwClient.placeUnivestCncMarketBuy(context, symbol, budget, orderRef);
        OfficialExecutionLatency.orderDispatched(context, signal.type, symbol, notificationPostTime, r.dispatchAtMillis);
        if (!r.submitted) {
            if (missedInitial) UnivestStateStore.releasePendingEntry(context, symbol, "Missed-entry CNC BUY not submitted: " + r.message);
            fail(context, "REENTRY_NOT_SUBMITTED", symbol, r.message, null);
            DiagnosticsStore.trade(context, missedInitial ? "MISSED_ENTRY_BUY_FAILED" : "REENTRY_BUY_FAILED", symbol, r.message, r);
            return;
        }

        UnivestStateStore.State state = UnivestStateStore.get(context, symbol);
        if (state == null) { state = new UnivestStateStore.State(); state.symbol = symbol; }
        state.tickSize = instrument.tickSize;
        if (missedInitial) {
            state.phase = r.filled && r.filledQuantity > 0 ? UnivestStateStore.ACTIVE : UnivestStateStore.ENTRY_PENDING;
            state.quantity = Math.max(0, r.filledQuantity);
            state.anchorPrice = r.filled ? r.averagePrice : 0;
            state.principal = Math.max(0, r.averagePrice) * Math.max(0, r.filledQuantity);
            state.estimatedBuyCharges = state.principal > 0 ? DeliveryNetTarget.buyCharges(state.principal) : 0;
            state.averageLevel = 0; state.reentryUsed = false; state.exitOrderId = r.orderId;
            state.lastAction = r.filled
                    ? "Missed initial " + rupees(initialBudget) + " CNC entry recovered from back-in-range • qty " + r.filledQuantity + " • avg ₹" + money(r.averagePrice)
                    : "Missed initial " + rupees(initialBudget) + " CNC BUY submitted; fill not yet confirmed • order " + r.orderId;
            UnivestStateStore.put(context, state);
            if (r.filled && r.averagePrice > 0) armAveragingLadder(context, state, r.orderId);
        } else {
            state.phase = r.filled ? UnivestStateStore.ACTIVE : state.phase;
            if (state.anchorPrice <= 0 && before.netPrice > 0) state.anchorPrice = before.netPrice;
            if (r.filled) {
                state.quantity = before.quantity + r.filledQuantity;
                state.principal = Math.max(0, state.principal) + Math.max(0, r.averagePrice) * Math.max(0, r.filledQuantity);
                state.reentryUsed = true;
                state.lastAction = rupees(addBudget) + " back-in-range CNC add executed • qty " + r.filledQuantity + " • avg ₹" + money(r.averagePrice);
            } else state.lastAction = rupees(addBudget) + " CNC add submitted but fill not confirmed • order " + r.orderId;
            UnivestStateStore.put(context, state);
        }

        DiagnosticsStore.trade(context,
                missedInitial ? (r.filled ? "MISSED_ENTRY_BUY_EXECUTED" : "MISSED_ENTRY_FILL_UNCONFIRMED")
                              : (r.filled ? "REENTRY_BUY_EXECUTED" : "REENTRY_FILL_UNCONFIRMED"),
                symbol, state.lastAction, r);
        long age = r.dispatchAtMillis > 0 && notificationPostTime > 0 ? Math.max(0, r.dispatchAtMillis - notificationPostTime) : -1;
        status(context, "UNIVEST " + (missedInitial ? rupees(initialBudget) + " MISSED INITIAL CNC ENTRY " : rupees(addBudget) + " CNC BACK-IN-RANGE ADD ")
                + (r.filled ? "EXECUTED" : "SUBMITTED") + " • " + symbol
                + (age >= 0 ? " • source age " + age + " ms" : "") + " • " + r.message);
    }

    static int chooseBackInRangeBudget(int brokerQuantity, boolean pendingBuy, int entryBudget, int addBudget) {
        if (pendingBuy) return 0;
        return brokerQuantity > 0 ? Math.max(0, addBudget) : Math.max(0, entryBudget);
    }

    static int budgetForBackInRange(Context context, int brokerQuantity, boolean pendingBuy) {
        return chooseBackInRangeBudget(brokerQuantity, pendingBuy,
                AppPrefs.getUnivestBudget(context), AppPrefs.getUnivestAddBudget(context));
    }

    // Compatibility helper for older unit tests; represents the historical defaults only.
    static int budgetForBackInRange(int brokerQuantity, boolean pendingBuy) {
        return chooseBackInRangeBudget(brokerQuantity, pendingBuy, ENTRY_BUDGET, REENTRY_BUDGET);
    }

    static void handleExit(Context context, UnivestParser.Signal signal, long notificationPostTime) {
        // EXIT must never wait for the 12-hour instrument-master network refresh. The packaged/cached NSE
        // instrument master is enough to resolve the exact symbol and tick size, and Groww remains broker truth.
        InstrumentRepository.Instrument instrument = resolveCached(context, signal, "EXIT");
        if (instrument == null) return;
        String symbol = instrument.symbol;

        if (AppPrefs.isPaperMode(context)) {
            String msg = "PAPER SELL • official Univest book-profit/exit simulation • no Groww order sent.";
            DiagnosticsStore.paperTrade(context, "PAPER_EXIT", symbol, msg);
            status(context, "PAPER MODE • UNIVEST EXIT • " + symbol + " • simulated full CNC holding sell. No Groww order sent.");
            return;
        }

        UnivestStateStore.State state;
        boolean hadTrackedCampaign;
        synchronized (campaignLock(symbol)) {
            state = UnivestStateStore.get(context, symbol);
            hadTrackedCampaign = state != null && !UnivestStateStore.EXITED.equals(state.phase);
            if (state == null) { state = new UnivestStateStore.State(); state.symbol = symbol; }
            // Same-symbol tombstone: no reconciliation/averaging BUY may be armed until a later fresh ENTRY.
            state.phase = UnivestStateStore.EXITING_OFFICIAL;
            state.lastAction = "Official Univest EXIT received • broker-truth full-CNC exit requested.";
            UnivestStateStore.put(context, state);
        }
        long sourceAge = notificationPostTime > 0 ? Math.max(0, System.currentTimeMillis() - notificationPostTime) : -1;
        DiagnosticsStore.runtime(context, "EXIT_FAST_PATH_START", symbol,
                "Official EXIT locked against averaging before broker calls"
                        + (sourceAge >= 0 ? " • source age " + sourceAge + " ms." : "."));

        // Univest EXIT is authoritative for this exact NSE CASH symbol even when the app did not buy it.
        // Snapshot broker truth, cancel conflicting SELLs, and retire known app-created averaging GTTs in parallel.
        // This preserves the safety barrier without serial network round-trips making EXIT unnecessarily slow.
        final List<String> exitAverageIds = knownAveragingIds(context, state);
        final UnivestStateStore.State exitStateForCleanup = state;
        ExecutorService exitPrepPool = Executors.newFixedThreadPool(3);
        Future<?> averageCleanup = exitPrepPool.submit(() -> {
            cancelAveragingIds(context, exitStateForCleanup, exitAverageIds);
            cancelLegacyTrackedOrders(context, exitStateForCleanup);
        });
        Future<GrowwClient.Result> conflictCleanup = exitPrepPool.submit(
                () -> GrowwClient.cancelOpenCncSellOrdersForSymbol(context, symbol));
        Future<GrowwClient.ExitSnapshot> snapshotFuture = exitPrepPool.submit(
                () -> GrowwClient.getFastExitSnapshot(context, symbol));

        GrowwClient.Result conflicts;
        GrowwClient.ExitSnapshot snap;
        try {
            try { averageCleanup.get(); }
            catch (Exception e) {
                DiagnosticsStore.error(context, "EXIT_AVERAGING_CLEANUP_EXCEPTION", symbol,
                        "Averaging cleanup task failed; post-exit cleanup remains armed.", e);
            }
            try { conflicts = conflictCleanup.get(); }
            catch (Exception e) { conflicts = new GrowwClient.Result(false, true, 0, "Conflicting SELL cleanup failed: " + safe(e)); }
            try { snap = snapshotFuture.get(); }
            catch (Exception e) { snap = new GrowwClient.ExitSnapshot(false, null, null, 0, "Fast exit snapshot failed: " + safe(e)); }
        } finally {
            exitPrepPool.shutdownNow();
        }

        UnivestStateStore.State refreshedExitState = UnivestStateStore.get(context, symbol);
        if (refreshedExitState != null) state = refreshedExitState;

        // Averaging cancellation and the first quote/holding snapshot intentionally run in parallel for speed.
        // Re-read holdings after averaging cleanup finishes so an averaging GTT that triggered during that narrow
        // window is included in the official full-quantity SELL instead of leaving residual shares behind.
        GrowwClient.PositionSnapshot postCleanupHolding = GrowwClient.getCncPosition(context, symbol);
        if (postCleanupHolding.success) {
            snap = new GrowwClient.ExitSnapshot(
                    snap != null && snap.quote != null && snap.quote.success,
                    postCleanupHolding,
                    snap == null ? null : snap.quote,
                    snap == null ? 0.0 : snap.executableSellPrice,
                    (snap == null ? "" : snap.message) + " • post-cleanup holding refresh qty=" + postCleanupHolding.quantity);
            DiagnosticsStore.runtime(context, "EXIT_POST_CLEANUP_QUANTITY_REFRESH", symbol,
                    "Final Groww CNC quantity after averaging cleanup • qty " + postCleanupHolding.quantity
                            + " • broker avg ₹" + money(postCleanupHolding.netPrice) + ".");
        } else {
            snap = new GrowwClient.ExitSnapshot(false, postCleanupHolding,
                    snap == null ? null : snap.quote, snap == null ? 0.0 : snap.executableSellPrice,
                    "Final post-cleanup holding refresh failed: " + postCleanupHolding.message);
        }

        DiagnosticsStore.broker(context, "CANCEL_CONFLICTING_CNC_SELLS", symbol,
                conflicts.success || conflicts.unknown, conflicts.message);
        if (!conflicts.success) {
            state.lastAction = "Official exit pending: conflicting CNC SELL could not be confirmed cleared. " + conflicts.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            fail(context, "EXIT_CONFLICTING_SELL_UNKNOWN", symbol, state.lastAction, null);
            return;
        }

        GrowwClient.PositionSnapshot holding = snap.holding;
        OfficialExecutionLatency.brokerReady(context, signal.type, symbol, notificationPostTime, System.currentTimeMillis());
        DiagnosticsStore.broker(context, "FAST_EXIT_SNAPSHOT", symbol, snap.success, snap.message);
        if (holding != null && holding.success) {
            DiagnosticsStore.runtime(context, "EXIT_LIVE_QUANTITY_REFRESH", symbol,
                    "Fresh Groww CNC quantity read immediately before official sell decision • qty " + holding.quantity
                            + " • broker avg ₹" + money(holding.netPrice) + ".");
        }
        if (holding == null || !holding.success) {
            state.lastAction = "Official exit pending: live broker holding could not be confirmed. " + snap.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            fail(context, "EXIT_HOLDING_CHECK_FAILED", symbol, state.lastAction, null);
            return;
        }
        if (holding.quantity <= 0) {
            markExited(context, state, symbol, "Official Univest exit received; broker shows no CNC holding.");
            schedulePostExitAveragingSweep(context, symbol, exitAverageIds);
            status(context, "UNIVEST BOOK PROFIT / EXIT • " + symbol + " • no holding found; nothing to sell.");
            DiagnosticsStore.runtime(context, "EXIT_NO_HOLDING", symbol, holding.message); return;
        }
        if (!instrument.sellAllowed) {
            state.quantity = holding.quantity;
            state.exitRequestedQty = holding.quantity;
            state.lastAction = "Official exit pending: Groww instrument master currently marks sell_allowed=0.";
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            fail(context, "EXIT_SELL_BLOCKED", symbol, state.lastAction, null);
            return;
        }

        if (!hadTrackedCampaign) {
            DiagnosticsStore.runtime(context, "UNIVEST_EXIT_EXTERNAL_OR_MANUAL_HOLDING", symbol,
                    "Official Univest EXIT matched an actual Groww CNC holding without an active app-originated campaign. "
                            + "Full broker quantity " + holding.quantity + " is eligible for the same green-only exit rule.");
        }

        state.phase = UnivestStateStore.EXITING_OFFICIAL;
        state.quantity = holding.quantity;
        state.exitRequestedQty = holding.quantity;
        state.exitOrderId = "";
        GrowwClient.GreenSellPlan green = GrowwClient.greenSellPlan(snap, instrument.tickSize);
        if (!green.canSell) {
            state.lastAction = "UNIVEST EXIT DEFERRED — WAITING FOR GREEN • qty " + holding.quantity
                    + " • broker avg ₹" + money(holding.netPrice)
                    + " • executable ₹" + money(snap.executableSellPrice)
                    + " • " + green.reason;
            UnivestStateStore.put(context, state);
            DiagnosticsStore.runtime(context, "EXIT_DEFERRED_NOT_GREEN", symbol, state.lastAction);
            status(context, state.lastAction);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            return;
        }

        state.lastAction = "Official Univest exit • green guard passed • full broker CNC qty "
                + holding.quantity + " • avg ₹" + money(holding.netPrice)
                + " • executable ₹" + money(snap.executableSellPrice)
                + " • protected limit ₹" + money(green.limitPrice);
        UnivestStateStore.put(context, state);

        String orderRef = stableRef("UX", symbol, signal.rawText, notificationPostTime);
        GrowwClient.ExecutionResult r = GrowwClient.placeUnivestCncGreenSell(
                context, symbol, holding.quantity, snap, instrument.tickSize, orderRef);
        OfficialExecutionLatency.orderDispatched(context, signal.type, symbol, notificationPostTime, r.dispatchAtMillis);
        DiagnosticsStore.trade(context, r.submitted ? "SELL_SUBMITTED" : "SELL_FAILED", symbol, state.lastAction, r);
        if (!r.submitted) {
            state.phase = UnivestStateStore.EXITING_OFFICIAL;
            state.exitOrderId = r.orderId == null ? "" : r.orderId;
            state.exitRequestedQty = holding.quantity;
            state.lastAction = "Official green-protected exit not completed; broker-truth recovery remains armed. " + r.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            fail(context, "EXIT_NOT_SUBMITTED", symbol, r.message, null);
            return;
        }

        GrowwClient.PositionSnapshot after = GrowwClient.getCncPosition(context, symbol);
        if (r.filled || (after.success && after.quantity == 0)) {
            markExited(context, state, symbol, "Official Univest exit executed • sold full CNC holding in green.");
            schedulePostExitAveragingSweep(context, symbol, exitAverageIds);
            ResearchTradeEngine.onOfficialExitExecuted(context, symbol, r.averagePrice);
            HistoryBackupManager.forceAutoBackup(context);
            long age = r.dispatchAtMillis > 0 && notificationPostTime > 0
                    ? Math.max(0, r.dispatchAtMillis - notificationPostTime) : -1;
            status(context, "UNIVEST GREEN CNC SELL EXECUTED • " + symbol + " • qty " + holding.quantity
                    + (age >= 0 ? " • source age " + age + " ms" : "") + " • " + r.message);
        } else {
            state.phase = UnivestStateStore.EXITING_OFFICIAL; state.exitOrderId = r.orderId;
            state.lastAction = "Green-protected CNC SELL accepted; fill/position-zero not yet confirmed. " + r.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            status(context, "UNIVEST SELL ACCEPTED — VERIFYING • " + symbol + " • " + r.message);
        }
    }

    static void migrateLegacyRules(Context context) {
        if (AppPrefs.isUnivestV2Migrated(context)) return;
        boolean allClean = true;
        for (UnivestStateStore.State state : UnivestStateStore.all(context)) {
            if (state.activeGttId != null && !state.activeGttId.isEmpty()) {
                GrowwClient.Result r = GrowwClient.cancelCashGtt(context, state.activeGttId);
                DiagnosticsStore.broker(context, "CANCEL_LEGACY_AVERAGE_GTT", state.symbol, r.success || r.unknown, r.message);
                if (r.success) { state.activeGttId = ""; state.activeGttKind = ""; state.activeGttQty = 0; state.activeGttPrice = 0; }
                else allClean = false;
            }
            if (state.protectiveStopGttId != null && !state.protectiveStopGttId.isEmpty()) {
                GrowwClient.Result r = GrowwClient.cancelCashGtt(context, state.protectiveStopGttId);
                DiagnosticsStore.broker(context, "CANCEL_LEGACY_STOP_GTT", state.symbol, r.success || r.unknown, r.message);
                if (r.success) { state.protectiveStopGttId = ""; state.protectiveStopPrice = 0; state.protectiveStopQty = 0; }
                else allClean = false;
            }
            UnivestStateStore.put(context, state);
        }
        if (allClean) {
            AppPrefs.setUnivestV2Migrated(context, true);
            DiagnosticsStore.runtime(context, "V2_RULE_MIGRATION_COMPLETE", "", "Legacy pre-v2 GTT stop/average rules cleaned. v2.1 controlled downward averaging uses dedicated CNC GTT ladder only.");
        }
    }

    static void reconcileAll(Context context) {
        migrateLegacyRules(context);
        if (!AppPrefs.isLiveMode(context)) return;

        boolean readyForBuys = AppPrefs.isReadyForBuy(context);
        for (UnivestStateStore.State observed : UnivestStateStore.all(context)) {
            if (observed == null || observed.symbol == null || observed.symbol.isEmpty()
                    || UnivestStateStore.EXITED.equals(observed.phase)) continue;
            final String symbol = observed.symbol;
            try {
                UnivestStateStore.State before = UnivestStateStore.get(context, symbol);
                if (before == null || UnivestStateStore.EXITED.equals(before.phase)) continue;
                if (UnivestStateStore.EXITING_OFFICIAL.equals(before.phase)) {
                    reconcilePendingOfficialExit(context, before);
                    continue;
                }

                if (!readyForBuys) continue;

                GrowwClient.PositionSnapshot broker = GrowwClient.getCncPosition(context, symbol);
                if (!broker.success) continue;

                UnivestStateStore.State activeForGtt = null;
                UnivestStateStore.State flatForCleanup = null;
                synchronized (campaignLock(symbol)) {
                    UnivestStateStore.State latest = UnivestStateStore.get(context, symbol);
                    if (latest == null || phaseBlocksCampaignResurrection(latest.phase)) {
                        DiagnosticsStore.runtime(context, "RECONCILE_STALE_SNAPSHOT_DROPPED", symbol,
                                "Background broker snapshot ignored because campaign is already "
                                        + (latest == null ? "missing" : latest.phase) + ".");
                        continue;
                    }

                    if (broker.quantity <= 0) {
                        latest.phase = UnivestStateStore.EXITED;
                        latest.quantity = 0;
                        latest.principal = 0;
                        latest.lastAction = "Broker reconciliation found no CNC holding; campaign reset to EXITED.";
                        UnivestStateStore.put(context, latest);
                        flatForCleanup = latest;
                    } else {
                        latest.phase = UnivestStateStore.ACTIVE;
                        latest.quantity = broker.quantity;
                        if (!(latest.anchorPrice > 0) && broker.netPrice > 0) latest.anchorPrice = broker.netPrice;
                        if (broker.netPrice > 0) latest.principal = broker.netPrice * broker.quantity;
                        UnivestStateStore.put(context, latest);
                        activeForGtt = latest;
                    }
                }

                if (flatForCleanup != null) {
                    cancelAveragingLadder(context, flatForCleanup);
                    DiagnosticsStore.runtime(context, "CAMPAIGN_RESET_BROKER_FLAT", symbol, flatForCleanup.lastAction);
                    continue;
                }

                if (activeForGtt != null) {
                    updateAverageLevelFromGtts(context, activeForGtt);
                    synchronized (campaignLock(symbol)) {
                        UnivestStateStore.State latest = UnivestStateStore.get(context, symbol);
                        if (latest == null || !phaseAllowsAveraging(latest.phase)) continue;
                        if (activeForGtt.averageLevel > latest.averageLevel) {
                            latest.averageLevel = activeForGtt.averageLevel;
                            UnivestStateStore.put(context, latest);
                        }
                        activeForGtt = latest;
                    }
                    if (activeForGtt.anchorPrice > 0 && AppPrefs.isAveragingEnabled(context)) {
                        armAveragingLadder(context, activeForGtt, activeForGtt.exitOrderId);
                    }
                }
            } catch (Throwable t) {
                DiagnosticsStore.error(context, "RECONCILIATION_ERROR", symbol, "Campaign reconciliation failed.", t);
            }
        }
    }

    static boolean phaseAllowsAveraging(String phase) {
        return UnivestStateStore.ACTIVE.equals(phase);
    }

    static boolean phaseBlocksCampaignResurrection(String phase) {
        return UnivestStateStore.EXITING_OFFICIAL.equals(phase) || UnivestStateStore.EXITED.equals(phase);
    }

    static boolean hasPendingOfficialExit(Context context) {
        for (UnivestStateStore.State state : UnivestStateStore.all(context)) {
            if (state != null && UnivestStateStore.EXITING_OFFICIAL.equals(state.phase)) return true;
        }
        return false;
    }

    static synchronized boolean reconcileDeferredExit(Context context, String symbol) {
        UnivestStateStore.State state = UnivestStateStore.get(context, symbol);
        if (state == null || !UnivestStateStore.EXITING_OFFICIAL.equals(state.phase)) return true;
        reconcilePendingOfficialExit(context, state);
        UnivestStateStore.State after = UnivestStateStore.get(context, symbol);
        return after == null || !UnivestStateStore.EXITING_OFFICIAL.equals(after.phase);
    }

    private static void reconcilePendingOfficialExit(Context context, UnivestStateStore.State state) {
        String symbol = state.symbol;
        InstrumentRepository.Instrument instrument = InstrumentRepository.resolve(InstrumentRepository.load(context), symbol);
        double tick = instrument != null && instrument.tickSize > 0 ? instrument.tickSize : 0.05;

        GrowwClient.ExitSnapshot snap = GrowwClient.getFastExitSnapshot(context, symbol);
        GrowwClient.PositionSnapshot broker = snap.holding;
        DiagnosticsStore.broker(context, "EXIT_RECOVERY_FAST_SNAPSHOT", symbol, snap.success, snap.message);
        if (broker == null || !broker.success) {
            DiagnosticsStore.error(context, "EXIT_RECOVERY_HOLDING_UNKNOWN", symbol,
                    "Pending official exit could not refresh broker holding; recovery remains armed. " + snap.message, null);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            return;
        }
        if (broker.quantity <= 0) {
            markExited(context, state, symbol, "Pending official exit reconciled: broker is flat.");
            schedulePostExitAveragingSweep(context, symbol, UnivestAveragingRegistry.idsForSymbol(context, symbol));
            ResearchTradeEngine.onOfficialExitExecuted(context, symbol, 0);
            HistoryBackupManager.forceAutoBackup(context);
            DiagnosticsStore.runtime(context, "EXIT_RECOVERY_CONFIRMED_FLAT", symbol,
                    "Broker truth confirms the official exit is complete.");
            return;
        }

        if (state.exitOrderId != null && !state.exitOrderId.isEmpty()) {
            GrowwClient.ExecutionResult existing = GrowwClient.checkCashOrderExecution(
                    context, state.exitOrderId, Math.max(1, state.exitRequestedQty));
            if (existing.filled && existing.filledQuantity >= Math.max(1, state.exitRequestedQty)) {
                GrowwClient.PositionSnapshot after = GrowwClient.getCncPosition(context, symbol);
                if (after.success && after.quantity <= 0) {
                    markExited(context, state, symbol, "Pending official exit reconciled from completed Groww order.");
                    schedulePostExitAveragingSweep(context, symbol, UnivestAveragingRegistry.idsForSymbol(context, symbol));
                    ResearchTradeEngine.onOfficialExitExecuted(context, symbol, existing.averagePrice);
                    HistoryBackupManager.forceAutoBackup(context);
                    DiagnosticsStore.runtime(context, "EXIT_RECOVERY_ORDER_COMPLETE", symbol,
                            "Previously submitted official exit is fully executed.");
                    return;
                }
            }

            if (!GrowwClient.isTerminalFailureStatus(existing.message)) {
                state.quantity = broker.quantity;
                state.lastAction = "Official green-protected exit still pending at Groww • " + existing.message;
                UnivestStateStore.put(context, state);
                OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
                return;
            }

            DiagnosticsStore.runtime(context, "EXIT_RECOVERY_TERMINAL_ORDER", symbol,
                    "Previous official exit reached terminal failure; green/broker-truth retry will be considered. "
                            + existing.message);
            state.exitOrderId = "";
            UnivestStateStore.put(context, state);
        }

        GrowwClient.GreenSellPlan green = GrowwClient.greenSellPlan(snap, tick);
        if (!green.canSell) {
            state.phase = UnivestStateStore.EXITING_OFFICIAL;
            state.quantity = broker.quantity;
            state.exitRequestedQty = broker.quantity;
            state.exitOrderId = "";
            state.lastAction = "UNIVEST EXIT DEFERRED — WAITING FOR GREEN • broker avg ₹"
                    + money(broker.netPrice) + " • executable ₹" + money(snap.executableSellPrice)
                    + " • " + green.reason;
            UnivestStateStore.put(context, state);
            DiagnosticsStore.runtime(context, "EXIT_RECOVERY_STILL_NOT_GREEN", symbol, state.lastAction);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            return;
        }

        GrowwClient.Result conflicts = GrowwClient.cancelOpenCncSellOrdersForSymbol(context, symbol);
        DiagnosticsStore.broker(context, "EXIT_RECOVERY_CANCEL_CONFLICTS", symbol,
                conflicts.success || conflicts.unknown, conflicts.message);
        if (!conflicts.success) {
            state.lastAction = "Official exit recovery paused: conflicting sell state is unknown. " + conflicts.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            return;
        }

        String retryRef = stableRef("UXR", symbol,
                "GREEN-RECOVER|" + state.exitRequestedQty + "|" + state.updatedAt, System.currentTimeMillis());
        GrowwClient.ExecutionResult retry = GrowwClient.placeUnivestCncGreenSell(
                context, symbol, broker.quantity, snap, tick, retryRef);
        DiagnosticsStore.trade(context, retry.submitted ? "SELL_RECOVERY_SUBMITTED" : "SELL_RECOVERY_FAILED",
                symbol, "Green/broker-truth official exit recovery for qty " + broker.quantity, retry);

        if (!retry.submitted) {
            state.phase = UnivestStateStore.EXITING_OFFICIAL;
            state.exitOrderId = retry.orderId == null ? "" : retry.orderId;
            state.exitRequestedQty = broker.quantity;
            state.lastAction = "Official green exit recovery not completed. " + retry.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
            DeferredGreenExitWatcher.start(context, symbol);
            return;
        }

        GrowwClient.PositionSnapshot after = GrowwClient.getCncPosition(context, symbol);
        if (retry.filled || (after.success && after.quantity <= 0)) {
            markExited(context, state, symbol, "Official exit recovered and broker position is flat in green.");
            schedulePostExitAveragingSweep(context, symbol, UnivestAveragingRegistry.idsForSymbol(context, symbol));
            ResearchTradeEngine.onOfficialExitExecuted(context, symbol, retry.averagePrice);
            HistoryBackupManager.forceAutoBackup(context);
            DiagnosticsStore.runtime(context, "EXIT_RECOVERY_EXECUTED", symbol,
                    "Official exit recovery sold the remaining broker CNC holding in green.");
        } else {
            state.phase = UnivestStateStore.EXITING_OFFICIAL;
            state.exitOrderId = retry.orderId;
            state.exitRequestedQty = broker.quantity;
            state.quantity = broker.quantity;
            state.lastAction = "Official green exit recovery accepted; awaiting broker fill. " + retry.message;
            UnivestStateStore.put(context, state);
            OfficialSignalRecoveryScheduler.scheduleAfter(context, 60_000L);
        }
    }

    private static void syncExistingHolding(Context context, String symbol, GrowwClient.PositionSnapshot before, InstrumentRepository.Instrument instrument) {
        UnivestStateStore.State s = UnivestStateStore.get(context, symbol);
        if (s == null) { s = new UnivestStateStore.State(); s.symbol = symbol; }
        s.phase = UnivestStateStore.ACTIVE; s.quantity = before.quantity;
        if (s.anchorPrice <= 0 && before.netPrice > 0) s.anchorPrice = before.netPrice;
        if (before.netPrice > 0) s.principal = before.netPrice * before.quantity;
        s.tickSize = instrument.tickSize; s.lastAction = "Broker already holds this symbol; no duplicate initial entry was placed.";
        UnivestStateStore.put(context, s);
    }

    private static void armAveragingLadder(Context context, UnivestStateStore.State state, String entrySeed) {
        if (state == null || !AppPrefs.isLiveMode(context) || !AppPrefs.isAveragingEnabled(context) || !(state.anchorPrice > 0)) return;
        final String symbol = state.symbol;
        final double expectedAnchor = state.anchorPrice;
        final int averageBudget = AppPrefs.getUnivestAddBudget(context);
        if (averageBudget <= 0) {
            DiagnosticsStore.runtime(context, "AVERAGING_BUDGET_DISABLED", symbol,
                    "Controlled downward averaging is enabled, but the shared re-entry/averaging budget is ₹0.");
            return;
        }

        int levels = Math.min(MAX_AVERAGE_LEVELS, AppPrefs.getAveragingLevels(context));
        for (int level = 1; level <= levels; level++) {
            synchronized (campaignLock(symbol)) {
                UnivestStateStore.State current = UnivestStateStore.get(context, symbol);
                if (current == null || !phaseAllowsAveraging(current.phase)
                        || Math.abs(current.anchorPrice - expectedAnchor) > 0.0001) {
                    DiagnosticsStore.runtime(context, "AVERAGING_BLOCKED_BY_CAMPAIGN_PHASE", symbol,
                            "No averaging BUY armed because current campaign phase is "
                                    + (current == null ? "missing" : current.phase) + ".");
                    return;
                }
                if (!getAvgId(current, level).isEmpty()) continue;

                double raw = current.anchorPrice * (1.0 - (AVERAGE_STEP_PCT * level / 100.0));
                double trigger = GrowwClient.roundTarget(raw, current.tickSize, false);
                int qty = (int)Math.floor(averageBudget / trigger);
                if (qty < 1) {
                    DiagnosticsStore.runtime(context, "AVERAGE_LEVEL_SKIPPED", symbol,
                            "-" + (int)(AVERAGE_STEP_PCT * level) + "% level skipped because " + rupees(averageBudget)
                                    + " cannot buy one share at trigger ₹" + money(trigger) + ".");
                    continue;
                }

                String ref = stableRef("A" + level, symbol,
                        entrySeed + "|" + level + "|" + money(current.anchorPrice), current.updatedAt);
                GrowwClient.GttResult gtt = GrowwClient.createUnivestCncBuyGtt(context, symbol, qty, trigger, ref);
                DiagnosticsStore.broker(context, "AVERAGE_GTT_LEVEL_" + level, symbol, gtt.success || gtt.unknown,
                        rupees(averageBudget) + " CNC averaging level " + level + " • trigger -"
                                + (int)(AVERAGE_STEP_PCT * level) + "% at ₹" + money(trigger)
                                + " • qty " + qty + " • " + gtt.message);
                if (gtt.success) {
                    UnivestAveragingRegistry.record(context, symbol, gtt.smartOrderId, ref);
                    setAvg(current, level, gtt.smartOrderId, trigger);
                    UnivestStateStore.put(context, current);
                }
            }
        }
    }

    private static void updateAverageLevelFromGtts(Context context, UnivestStateStore.State state) {
        int filled = 0;
        for (int level = 1; level <= MAX_AVERAGE_LEVELS; level++) {
            String id = getAvgId(state, level); if (id.isEmpty()) continue;
            GrowwClient.GttStatusResult st = GrowwClient.getCashGttStatus(context, id);
            if (!st.success) continue;
            String v = st.status == null ? "" : st.status.toUpperCase(Locale.US);
            if (v.contains("COMPLET") || v.contains("EXECUT") || v.contains("TRIGGER")) filled++;
        }
        if (filled > state.averageLevel) {
            state.averageLevel = filled;
            DiagnosticsStore.runtime(context, "AVERAGE_LEVEL_RECONCILED", state.symbol, "Broker smart-order status indicates " + filled + " downward averaging level(s) triggered/completed.");
        }
    }

    private static void cancelAveragingLadder(Context context, UnivestStateStore.State s) {
        if (s == null) return;
        cancelAveragingIds(context, s, knownAveragingIds(context, s));
    }

    private static List<String> knownAveragingIds(Context context, UnivestStateStore.State s) {
        Set<String> ids = new LinkedHashSet<>();
        if (s != null) {
            for (int level = 1; level <= MAX_AVERAGE_LEVELS; level++) {
                String id = getAvgId(s, level);
                if (id != null && !id.isEmpty()) ids.add(id);
            }
            ids.addAll(UnivestAveragingRegistry.idsForSymbol(context, s.symbol));
        }
        return new ArrayList<>(ids);
    }

    private static void cancelAveragingIds(Context context, UnivestStateStore.State s, List<String> ids) {
        if (s == null || ids == null || ids.isEmpty()) return;
        final String symbol = s.symbol;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, Math.max(1, ids.size())));
        List<Future<GrowwClient.Result>> futures = new ArrayList<>();
        for (String id : ids) futures.add(pool.submit(() -> GrowwClient.cancelCashGtt(context, id)));
        pool.shutdown();

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            GrowwClient.Result r;
            try { r = futures.get(i).get(); }
            catch (Exception e) { r = new GrowwClient.Result(false, true, 0, "GTT cancellation task failed: " + safe(e)); }
            DiagnosticsStore.broker(context, "CANCEL_AVERAGE_GTT", symbol, r.success || r.unknown,
                    id + " • " + r.message);
            if (r.success) {
                UnivestAveragingRegistry.remove(context, id);
                synchronized (campaignLock(symbol)) {
                    UnivestStateStore.State current = UnivestStateStore.get(context, symbol);
                    if (current != null) {
                        for (int level = 1; level <= MAX_AVERAGE_LEVELS; level++) {
                            if (id.equals(getAvgId(current, level))) setAvg(current, level, "", 0.0);
                        }
                        UnivestStateStore.put(context, current);
                    }
                }
            } else {
                DiagnosticsStore.error(context, "AVERAGE_GTT_CANCEL_NOT_CONFIRMED", symbol,
                        "Could not confirm cancellation of app-created averaging GTT " + id + ".", null);
            }
        }
    }

    private static void schedulePostExitAveragingSweep(Context context, String symbol, List<String> exitIds) {
        if (exitIds == null || exitIds.isEmpty()) return;
        Context app = context.getApplicationContext();
        List<String> captured = new ArrayList<>(exitIds);
        new Thread(() -> {
            int cleared = 0;
            for (String id : captured) {
                GrowwClient.Result r = GrowwClient.cancelCashGtt(app, id);
                if (r.success) {
                    UnivestAveragingRegistry.remove(app, id);
                    cleared++;
                } else {
                    DiagnosticsStore.error(app, "POST_EXIT_AVERAGING_SWEEP_RETRY", symbol,
                            "Post-exit cancellation still not confirmed for averaging GTT " + id + ". " + r.message, null);
                }
            }
            DiagnosticsStore.runtime(app, "POST_EXIT_AVERAGING_SWEEP", symbol,
                    "Post-exit safety sweep rechecked " + captured.size() + " pre-exit averaging GTT id(s); "
                            + cleared + " confirmed inactive/cancelled. Later fresh BUY campaigns are not touched.");
        }, "univest-post-exit-gtt-" + symbol).start();
    }

    private static String getAvgId(UnivestStateStore.State s, int level) {
        if (level == 1) return s.averageGtt1Id == null ? "" : s.averageGtt1Id;
        if (level == 2) return s.averageGtt2Id == null ? "" : s.averageGtt2Id;
        return s.averageGtt3Id == null ? "" : s.averageGtt3Id;
    }

    private static void setAvg(UnivestStateStore.State s, int level, String id, double price) {
        if (level == 1) { s.averageGtt1Id = id; s.averageGtt1Price = price; }
        else if (level == 2) { s.averageGtt2Id = id; s.averageGtt2Price = price; }
        else { s.averageGtt3Id = id; s.averageGtt3Price = price; }
    }

    private static Object campaignLock(String symbol) {
        String key = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        return CAMPAIGN_LOCKS.computeIfAbsent(key, ignored -> new Object());
    }

    private static InstrumentRepository.Instrument resolveCached(Context context, UnivestParser.Signal signal, String action) {
        List<InstrumentRepository.Instrument> instruments = InstrumentRepository.load(context);
        InstrumentRepository.Instrument i = InstrumentRepository.resolve(instruments, signal.symbol);
        if (i != null) {
            if (!i.symbol.equalsIgnoreCase(signal.symbol)) DiagnosticsStore.runtime(context, "SYMBOL_MAPPED", i.symbol,
                    "Univest stock text '" + signal.symbol + "' mapped to NSE symbol " + i.symbol + " (" + i.name + ").");
            return i;
        }
        List<String> suggestions = InstrumentRepository.suggestions(instruments, signal.symbol, 5);
        String msg = action + " symbol mapping failed for Univest stock text '" + signal.symbol
                + "' using the local instrument master. Candidates: " + suggestions;
        fail(context, "SYMBOL_MAPPING_FAILED", signal.symbol, msg, null);
        return null;
    }

    private static InstrumentRepository.Instrument resolve(Context context, UnivestParser.Signal signal, String action) {
        try { InstrumentRepository.refreshIfStale(context); } catch (Throwable ignored) {}
        List<InstrumentRepository.Instrument> instruments = InstrumentRepository.load(context);
        InstrumentRepository.Instrument i = InstrumentRepository.resolve(instruments, signal.symbol);
        if (i != null) {
            if (!i.symbol.equalsIgnoreCase(signal.symbol)) DiagnosticsStore.runtime(context, "SYMBOL_MAPPED", i.symbol,
                    "Univest stock text '" + signal.symbol + "' mapped to NSE symbol " + i.symbol + " (" + i.name + ").");
            return i;
        }
        List<String> suggestions = InstrumentRepository.suggestions(instruments, signal.symbol, 5);
        String msg = action + " symbol mapping failed for Univest stock text '" + signal.symbol + "'. Candidates: " + suggestions;
        fail(context, "SYMBOL_MAPPING_FAILED", signal.symbol, msg, null); return null;
    }

    private static void cancelLegacyTrackedOrders(Context context, UnivestStateStore.State s) {
        if (s == null) return;
        final String symbol = s.symbol;
        final String activeId = s.activeGttId == null ? "" : s.activeGttId;
        final String stopId = s.protectiveStopGttId == null ? "" : s.protectiveStopGttId;

        GrowwClient.Result activeResult = null;
        GrowwClient.Result stopResult = null;
        if (!activeId.isEmpty()) {
            activeResult = GrowwClient.cancelCashGtt(context, activeId);
            DiagnosticsStore.broker(context, "CANCEL_TRACKED_GTT_BEFORE_EXIT", symbol,
                    activeResult.success || activeResult.unknown, activeResult.message);
        }
        if (!stopId.isEmpty()) {
            stopResult = GrowwClient.cancelCashGtt(context, stopId);
            DiagnosticsStore.broker(context, "CANCEL_TRACKED_STOP_BEFORE_EXIT", symbol,
                    stopResult.success || stopResult.unknown, stopResult.message);
        }

        if ((activeResult != null && activeResult.success) || (stopResult != null && stopResult.success)) {
            synchronized (campaignLock(symbol)) {
                UnivestStateStore.State current = UnivestStateStore.get(context, symbol);
                if (current == null) return;
                if (activeResult != null && activeResult.success && activeId.equals(current.activeGttId)) {
                    current.activeGttId = "";
                    current.activeGttKind = "";
                    current.activeGttQty = 0;
                    current.activeGttPrice = 0;
                }
                if (stopResult != null && stopResult.success && stopId.equals(current.protectiveStopGttId)) {
                    current.protectiveStopGttId = "";
                    current.protectiveStopPrice = 0;
                    current.protectiveStopQty = 0;
                }
                UnivestStateStore.put(context, current);
            }
        }
    }

    private static void markExited(Context c, UnivestStateStore.State s, String symbol, String message) {
        if (s == null) { s = new UnivestStateStore.State(); s.symbol = symbol; }
        s.phase = UnivestStateStore.EXITED; s.quantity = 0; s.principal = 0; s.estimatedBuyCharges = 0; s.averageLevel = 0;
        s.exitRequestedQty = 0; s.activeGttId = ""; s.protectiveStopGttId = "";
        s.averageGtt1Id = ""; s.averageGtt2Id = ""; s.averageGtt3Id = "";
        s.averageGtt1Price = 0; s.averageGtt2Price = 0; s.averageGtt3Price = 0; s.lastAction = message;
        UnivestStateStore.put(c, s);
    }

    private static void fail(Context c, String event, String symbol, String message, Throwable t) {
        DiagnosticsStore.error(c, event, symbol, message, t); status(c, "UNIVEST ERROR • " + symbol + " • " + message);
    }

    private static void status(Context c, String text) {
        AppPrefs.setUnivestStatus(c, text); DiagnosticsStore.runtime(c, "STATUS", "", text);
    }

    static String stableRef(String prefix, String symbol, String seed, long postTime) {
        try {
            // Broker idempotency is tied to one concrete Android notification, not a time window.
            // Replaying the same durable event gets the same reference; a genuine later notification even seconds
            // later gets a different reference and remains fully eligible for execution.
            long eventTime = postTime > 0 ? postTime : System.currentTimeMillis();
            String raw = prefix + "|" + symbol + "|" + seed + "|" + eventTime;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder(); for (int i = 0; i < 5; i++) h.append(String.format(Locale.US, "%02X", d[i]));
            String day = AppPrefs.istDayKey(postTime > 0 ? postTime : System.currentTimeMillis());
            String p = (prefix == null ? "UV" : prefix.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", ""));
            if (p.length() > 3) p = p.substring(0, 3);
            String out = p + day + h;
            return out.length() > 20 ? out.substring(0, 20) : out;
        } catch (Exception e) {
            String out = (prefix + AppPrefs.istDayKey(System.currentTimeMillis()) + Math.abs((symbol + seed).hashCode())).replaceAll("[^A-Za-z0-9]", "");
            return out.length() > 20 ? out.substring(0, 20) : out;
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static String rupees(int v) {
        java.text.NumberFormat f = java.text.NumberFormat.getIntegerInstance(new Locale("en", "IN"));
        return "₹" + f.format(Math.max(0, v));
    }

    private static String money(double v) { return String.format(Locale.US, "%.2f", v); }
}
