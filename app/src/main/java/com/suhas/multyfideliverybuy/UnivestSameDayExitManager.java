package com.suhas.multyfideliverybuy;

import android.content.Context;

/**
 * Broker-aware same-day close of only APP-ATTRIBUTED official campaigns.
 * An attempted SELL is never called a fill; crossed/pending orders and
 * unattributed broker quantities force a safety alert, not another order.
 */
final class UnivestSameDayExitManager {
    private UnivestSameDayExitManager() {}

    static void tick(Context c) {
        long now=System.currentTimeMillis();
        boolean closeWindow=SameDayForecastPolicy.closingWindow(now);
        boolean trading=NseTradingCalendar.isRegularMarketOpen(now);
        if(!closeWindow && !trading)return;
        for(UnivestStateStore.State s:UnivestStateStore.all(c)) {
            if(s == null || s.entryAt<=0 || s.symbol.isEmpty()
                    || UnivestStateStore.EXITED.equals(s.phase))continue;
            boolean overnight=trading && NseTradingCalendar.dayKey(now).compareTo(
                    NseTradingCalendar.dayKey(s.entryAt)) > 0;
            if(!closeWindow && !overnight)continue;
            synchronized(UnivestManager.campaignLock(s.symbol)) {
                closeOne(c,s.symbol,overnight);
            }
        }
    }

    private static void closeOne(Context c,String symbol,boolean overdue) {
        UnivestStateStore.State state=UnivestStateStore.get(c,symbol);
        if(state==null || state.entryAt<=0 || state.quantity<=0 ||
                UnivestStateStore.EXITED.equals(state.phase))return;
        if(!AppPrefs.isLiveMode(c)) {
            attention(c,symbol,"Live official holding cannot be exited while mode is PAPER");
            return;
        }
        if(UnivestStateStore.EXITING_OFFICIAL.equals(state.phase)
                && state.exitOrderId != null && !state.exitOrderId.isEmpty()) {
            if("EOD_SELL_RESERVED_UNKNOWN".equals(state.exitOrderId)) {
                attention(c,symbol,"Prior EOD SELL acceptance uncertain; check Groww before manual action");
                return;
            }
            if(!"EOD_SELL_RESERVING".equals(state.exitOrderId)) {
                GrowwClient.ExecutionResult prior=GrowwClient.checkCashOrderExecution(
                        c,state.exitOrderId,Math.max(1,state.exitRequestedQty));
                if(prior.filled && prior.filledQuantity>=state.exitRequestedQty) {
                    state.phase=UnivestStateStore.EXITED;
                    state.quantity=0;state.principal=0;
                    state.lastAction="Same-day official position CLOSED after confirmed Groww fill.";
                    UnivestStateStore.put(c,state);
                } else attention(c,symbol,"Existing broker SELL remains pending/partly filled");
                return;
            }
            attention(c,symbol,"Prior EOD SELL reservation is unresolved; no second SELL");
            return;
        }

        // If the original official SELL already requested a fill, never cross it.
        GrowwClient.Result pendingSell=GrowwClient.checkForActiveCncSellOrder(c,symbol);
        if(pendingSell.unknown || pendingSell.success) {
            attention(c,symbol,"Existing or uncertain CNC SELL order: "+pendingSell.message);
            return;
        }
        if(!SharedIntradayAveraging.cancel(c,symbol)) {
            attention(c,symbol,"Averaging GTT cancellation uncertain; no crossed SELL submitted");
            return;
        }
        // Cancel GTTs created by older official ladder in the same symbol as well.
        if(UnivestAveragingRegistry.countForSymbol(c,symbol)>0) {
            attention(c,symbol,"Some app-created GTTs remain registered; reconcile Groww first");
            return;
        }
        if(state.deferredProfitSellGttUncertain) {
            attention(c,symbol,"Old profit GTT acceptance is uncertain; no duplicate sell");
            return;
        }
        if(state.deferredProfitSellGttId != null &&
                !state.deferredProfitSellGttId.isEmpty()) {
            GrowwClient.Result cancel=GrowwClient.cancelCashGtt(c,state.deferredProfitSellGttId);
            if(!cancel.success) {
                attention(c,symbol,"Profit-recovery GTT cancellation not broker-confirmed");
                return;
            }
            state.deferredProfitSellGttId="";
            state.deferredProfitSellGttQty=0;
            state.deferredProfitSellGttPrice=0;
            UnivestStateStore.put(c,state);
        }

        GrowwClient.PositionSnapshot broker=GrowwClient.getCncPosition(c,symbol);
        if(!broker.success || broker.quantity<=0) {
            attention(c,symbol,"Broker holding unknown/flat; reconciliation needed: "+broker.message);
            return;
        }
        // Never dispose of unattributed manual/Forecast quantities.
        if(broker.quantity!=state.quantity) {
            attention(c,symbol,"Qty mismatch (broker "+broker.quantity+
                    ", official "+state.quantity+"). Manual verification required.");
            return;
        }

        String reference=UnivestManager.stableRef("UD",symbol,
                "SAME_DAY_CLOSE|"+state.entryAt, state.entryAt);
        int qty=state.quantity;
        state.phase=UnivestStateStore.EXITING_OFFICIAL;
        state.exitOrderId="EOD_SELL_RESERVING";
        state.exitRequestedQty=qty;
        state.lastAction="EOD market SELL reserved, checking broker acceptance.";
        UnivestStateStore.put(c,state);
        GrowwClient.ExecutionResult result;
        try{result=GrowwClient.placeCncMarketSell(c,symbol,qty,reference);}
        catch(Throwable t){
            state.exitOrderId="EOD_SELL_RESERVED_UNKNOWN";
            state.lastAction="EOD SELL outcome uncertain. Inspect Groww; do not double sell.";
            UnivestStateStore.put(c,state);
            DiagnosticsStore.error(c,"UNIVEST_EOD_SELL_UNKNOWN",symbol,state.lastAction,t);
            return;
        }
        if(!result.submitted) {
            if(result.unknown) {
                state.exitOrderId="EOD_SELL_RESERVED_UNKNOWN";
                state.lastAction="EOD SELL broker acceptance unknown; manual reconciliation required.";
            } else {
                state.exitOrderId="";
                state.phase=UnivestStateStore.ACTIVE; // eligible for another checked EOD attempt
                state.lastAction="EOD SELL definitely rejected: "+result.message;
            }
            UnivestStateStore.put(c,state);
            attention(c,symbol,state.lastAction);
            return;
        }
        state.exitOrderId=result.orderId == null ? "" : result.orderId;
        if(state.exitOrderId.isEmpty()) state.exitOrderId="EOD_SELL_RESERVED_UNKNOWN";
        if(result.filled && result.filledQuantity>=qty) {
            state.phase=UnivestStateStore.EXITED;
            state.quantity=0;state.principal=0;
            state.lastAction="Groww confirmed full forced same-session CNC SELL fill.";
        } else {
            state.phase=UnivestStateStore.EXITING_OFFICIAL;
            state.lastAction="EOD SELL submitted • waiting for broker fill; overnight risk if unfilled.";
        }
        UnivestStateStore.put(c,state);
        DiagnosticsStore.trade(c,"UNIVEST_SAME_DAY_EXIT",symbol,state.lastAction,result);
    }

    private static void attention(Context c,String symbol,String detail){
        DiagnosticsStore.runtime(c,"UNIVEST_EOD_EXIT_ATTENTION",symbol,detail);
        AppPrefs.setUnivestStatus(c,"SAME-DAY EXIT NEEDS ATTENTION • "+symbol+" • "+detail);
    }
}
