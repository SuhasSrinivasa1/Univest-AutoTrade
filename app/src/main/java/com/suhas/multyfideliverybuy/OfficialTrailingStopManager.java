package com.suhas.multyfideliverybuy;

import android.content.Context;
import java.util.Locale;

/** Sole automatic official-Univest SELL strategy: broker-hosted, rising DOWN GTT.
 * No exit before a +1% gross-price peak; no Univest SELL notifications or EOD exits.
 * Only app-attributed shares are managed. Unknown broker states STOP all new orders.
 */
final class OfficialTrailingStopManager {
    private static final Object TICK_LOCK=new Object();
    private OfficialTrailingStopManager() {}

    static void tick(Context context) {
        Context c=context.getApplicationContext();
        if(!NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis()))return;
        if(!AppPrefs.isLiveMode(c) || !AppPrefs.isUnivestEnabled(c))return;
        synchronized(TICK_LOCK) {
            for(UnivestStateStore.State row:UnivestStateStore.all(c)) {
                if(row==null || row.symbol.isEmpty() || UnivestStateStore.EXITED.equals(row.phase))continue;
                synchronized(UnivestManager.campaignLock(row.symbol)) {
                    manageOne(c,row.symbol);
                }
            }
            OfficialSingleSlotQueue.drain(c);
        }
    }

    private static void manageOne(Context c,String symbol) {
        UnivestStateStore.State s=UnivestStateStore.get(c,symbol);
        if(s==null||UnivestStateStore.EXITED.equals(s.phase))return;
        if(UnivestStateStore.ENTRY_PENDING.equals(s.phase)) {
            // Do not trust an unfilled BUY merely because a broker request was accepted.
            GrowwClient.PositionSnapshot p=GrowwClient.getCncPosition(c,symbol);
            if(!p.success)return;
            if(p.quantity<=s.brokerBaselineQty) return;
            GrowwClient.Result pendingBuy=GrowwClient.checkForActiveCncBuyOrder(c,symbol);
            if(pendingBuy.success||pendingBuy.unknown)return;
            s.phase=UnivestStateStore.ACTIVE;
            s.quantity=p.quantity;
            s.principal=p.netPrice>0 ? p.netPrice*p.quantity : s.principal;
            if(p.netPrice>0)s.anchorPrice=p.netPrice;
            UnivestStateStore.put(c,s);
        }
        GrowwClient.PositionSnapshot p=GrowwClient.getCncPosition(c,symbol);
        if(!p.success)return;
        if(p.quantity<=s.brokerBaselineQty) {
            // A BUY fill may precede holdings visibility: never unlock on a single flat observation.
            GrowwClient.Result pending=GrowwClient.checkForActiveCncBuyOrder(c,symbol);
            if(pending.unknown || pending.success)return;
            long now=System.currentTimeMillis();
            if(s.flatObservedAt<=0) {
                s.flatObservedAt=now;UnivestStateStore.put(c,s);return;
            }
            if(now-s.flatObservedAt<15_000L)return;
            // A triggered GTT may have sold already; broker flat is the unlock criterion.
            if(!s.trailGttId.isEmpty()) {
                GrowwClient.GttStatusResult g=GrowwClient.getCashGttStatus(c,s.trailGttId);
                if(!g.success)return;
                String st=g.status==null?"":g.status.toUpperCase(Locale.US);
                if(st.contains("ACTIVE")||st.contains("OPEN")||st.contains("PENDING")) {
                    GrowwClient.Result cancelled=GrowwClient.cancelCashGtt(c,s.trailGttId);
                    if(!cancelled.success) return; // don't leave an orphan sell GTT
                }
            }
            s.phase=UnivestStateStore.EXITED;s.quantity=0;s.principal=0;
            s.lastAction="Official trailing strategy: broker confirmed app-owned shares are flat.";
            s.trailGttId="";s.trailGttUncertain=false;
            UnivestStateStore.put(c,s);
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_CLOSED",symbol,s.lastAction);
            return;
        }
        if(s.flatObservedAt>0) {
            s.flatObservedAt=0;UnivestStateStore.put(c,s);
        }
        if(UnivestStateStore.EXITING_OFFICIAL.equals(s.phase)
                || UnivestStateStore.EXITING_STOP.equals(s.phase)) {
            // An earlier accepted SELL/old policy still needs broker reconciliation.
            // Never issue a crossing stop order until broker position is flat.
            DiagnosticsStore.runtime(c,"OFFICIAL_EXISTING_SELL_PENDING",symbol,
                    "Existing SELL phase unresolved; no second order issued.");
            return;
        }
        if(s.quantity<=s.brokerBaselineQty && p.quantity>s.brokerBaselineQty) {
            GrowwClient.Result pending=GrowwClient.checkForActiveCncBuyOrder(c,symbol);
            if(pending.success||pending.unknown)return;
            s.quantity=p.quantity;
            if(p.netPrice>0) {
                if(!(s.anchorPrice>0))s.anchorPrice=p.netPrice;
                s.principal=p.netPrice*p.quantity;
            }
            UnivestStateStore.put(c,s);
        }
        if(p.quantity!=s.quantity || s.brokerBaselineQty>0) {
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_QUANTITY_MISMATCH",symbol,
                    "App and broker quantity/baseline must reconcile before installing a SELL GTT.");
            return;
        }
        if(s.trailGttUncertain) {
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_GTT_UNKNOWN",symbol,
                    "Smart-order status uncertain: check Groww before further automated changes.");
            return;
        }
        if(!(p.netPrice>0) && !(s.anchorPrice>0))return;
        double entry=s.anchorPrice>0?s.anchorPrice:p.netPrice;
        if(!retireOldOrders(c,s)) return;
        s=UnivestStateStore.get(c,symbol); // cleanup changed persisted legacy-order fields
        if(s==null)return;
        // An old profit SELL (or a just-triggered GTT child) may still be in flight.
        // Never arm/modify a second SELL while a normal CNC SELL is open or uncertain.
        GrowwClient.Result pendingSell=GrowwClient.checkForActiveCncSellOrder(c,symbol);
        if(pendingSell.unknown || pendingSell.success) {
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_SELL_ORDER_CONFLICT",symbol,
                    "Existing/unknown broker SELL blocks trailing changes: "+pendingSell.message);
            return;
        }
        // Do not replace an accepted broker stop until its current state is known.
        if(!s.trailGttId.isEmpty()) {
            GrowwClient.GttStatusResult status=GrowwClient.getCashGttStatus(c,s.trailGttId);
            if(!status.success) return;
            String st=status.status==null?"":status.status.toUpperCase(Locale.US);
            if(st.contains("TRIGGER")||st.contains("EXECUT")||st.contains("PENDING")) {
                s.lastAction="Trailing GTT "+status.status+" • awaiting broker-confirmed flat position.";
                UnivestStateStore.put(c,s);
                return;
            }
            if(st.contains("CANCEL")||st.contains("EXPIRE")||st.contains("REJECT")||st.contains("FAIL")) {
                s.trailGttId="";s.trailTrigger=0;s.trailFloorPct=0;
                UnivestStateStore.put(c,s);
            } else if(!(st.contains("ACTIVE")||st.contains("OPEN"))) {
                // Never blind-replace an unrecognized or uncertain broker status.
                return;
            }
        }
        final double ltp;
        try { ltp=GrowwClient.getLtpForAutomation(c,symbol); }
        catch(Exception ex){DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_LTP_FAILED",symbol,ex.getMessage());return;}
        double gain=OfficialTrailPolicy.gainPct(entry,ltp);
        if(!Double.isFinite(gain))return;
        s.trailPeakPct=Math.max(s.trailPeakPct,gain);
        double desired=OfficialTrailPolicy.floor(s.trailFloorPct,s.trailPeakPct);
        if(desired < OfficialTrailPolicy.INITIAL_FLOOR_PCT) {
            s.lastAction=String.format(Locale.US,"Waiting for +1.00%% activation • currently %+.2f%%",gain);
            UnivestStateStore.put(c,s);return;
        }
        // Desired floor is not the broker-confirmed stop. Update trailFloorPct ONLY
        // after Groww acknowledges GTT creation/modification.
        double trigger=OfficialTrailPolicy.trigger(entry,s.tickSize,desired);
        if(!(trigger>0))return;
        int qty=p.quantity-s.brokerBaselineQty;
        if(qty<=0)return;
        // If the stop has already been crossed before a protective GTT could be armed,
        // the trailing rule itself permits a single immediate sell (not another strategy).
        if(s.trailGttId.isEmpty() && ltp<=trigger) {
            sellAtTrailingCross(c,s,qty,gain);
            return;
        }
        if(s.trailGttId.isEmpty()) {
            String ref=UnivestManager.stableRef("UT",symbol,"TRAIL|"+s.entryAt,s.entryAt);
            s.trailGttUncertain=true;
            s.lastAction="Reserving official trailing GTT; broker acceptance not confirmed.";
            UnivestStateStore.put(c,s);
            GrowwClient.GttResult result=GrowwClient.createOfficialCncTrailSellGtt(c,symbol,qty,trigger,ref);
            s.trailGttUncertain=result.unknown || (result.success && result.smartOrderId.isEmpty());
            if(result.success && !result.smartOrderId.isEmpty()) {
                s.trailGttId=result.smartOrderId;s.trailTrigger=trigger;
                s.trailFloorPct=desired;
            }
            s.lastAction="Official trail +"+String.format(Locale.US,"%.2f",desired)
                    +"% • broker GTT: "+result.message;
            UnivestStateStore.put(c,s);
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_GTT_CREATE",symbol,s.lastAction);
        } else if(trigger>s.trailTrigger+Math.max(.001,s.tickSize)*.49) {
            // Never cancel/recreate a live GTT: modification keeps a single SELL intent.
            GrowwClient.Result result=GrowwClient.modifySignalCncTrailGtt(c,s.trailGttId,qty,trigger);
            if(result.unknown)s.trailGttUncertain=true;
            if(result.success) { s.trailTrigger=trigger; s.trailFloorPct=desired; }
            s.lastAction="Official trail +"+String.format(Locale.US,"%.2f",desired)
                    +"% • GTT ratchet: "+result.message;
            UnivestStateStore.put(c,s);
            DiagnosticsStore.runtime(c,"OFFICIAL_TRAIL_GTT_RATCHET",symbol,s.lastAction);
        } else {
            UnivestStateStore.put(c,s); // persist high-water-mark for process restarts
        }
    }

    /** Existing v2.9.25 averaging/profit orders must be retired and confirmed before
     * installing the new trail. Any uncertain cancellation blocks new sells and buys.
     */
    private static boolean retireOldOrders(Context c,UnivestStateStore.State state) {
        String symbol=state.symbol;
        boolean oldAverages=UnivestAveragingRegistry.countForSymbol(c,symbol)>0
                || (state.averageGtt1Id!=null&&!state.averageGtt1Id.isEmpty())
                || (state.averageGtt2Id!=null&&!state.averageGtt2Id.isEmpty())
                || (state.averageGtt3Id!=null&&!state.averageGtt3Id.isEmpty());
        if(oldAverages)UnivestManager.cancelAveragingLadder(c,state);
        boolean sharedCancelled=SharedIntradayAveraging.cancel(c,symbol);
        UnivestStateStore.State latest=UnivestStateStore.get(c,symbol);
        if(latest==null)return false;
        boolean leftovers=UnivestAveragingRegistry.countForSymbol(c,symbol)>0
                || (latest.averageGtt1Id!=null&&!latest.averageGtt1Id.isEmpty())
                || (latest.averageGtt2Id!=null&&!latest.averageGtt2Id.isEmpty())
                || (latest.averageGtt3Id!=null&&!latest.averageGtt3Id.isEmpty());
        boolean profitUnknown=latest.deferredProfitSellGttUncertain;
        if(latest.deferredProfitSellGttId!=null&&!latest.deferredProfitSellGttId.isEmpty()) {
            GrowwClient.Result cancel=GrowwClient.cancelCashGtt(c,latest.deferredProfitSellGttId);
            if(cancel.success) {
                latest.deferredProfitSellGttId="";
                latest.deferredProfitSellGttPrice=0;
                latest.deferredProfitSellGttQty=0;
                latest.deferredProfitSellGttUncertain=false;
                profitUnknown=false;
            } else profitUnknown=true;
        }
        latest.legacySellsUnresolved=!sharedCancelled||leftovers||profitUnknown;
        UnivestStateStore.put(c,latest);
        if(latest.legacySellsUnresolved) {
            DiagnosticsStore.runtime(c,"OFFICIAL_LEGACY_ORDER_BLOCK",symbol,
                    "Old averaging/profit GTT cancellation not confirmed. No new SELL/BUY until reconciled.");
            return false;
        }
        return true;
    }

    private static void sellAtTrailingCross(Context c,UnivestStateStore.State s,int qty,double currentGain) {
        GrowwClient.Result existing=GrowwClient.checkForActiveCncSellOrder(c,s.symbol);
        if(existing.unknown || existing.success)return;
        s.phase=UnivestStateStore.EXITING_STOP;
        s.exitRequestedQty=qty;
        s.exitOrderId="TRAIL_SELL_RESERVED_UNKNOWN";
        s.lastAction="Trailing floor crossed. Reserving one market SELL before dispatch.";
        UnivestStateStore.put(c,s);
        String ref=UnivestManager.stableRef("US",s.symbol,"TRAIL|"+s.entryAt,s.entryAt);
        GrowwClient.ExecutionResult result=GrowwClient.placeCncMarketSell(c,s.symbol,qty,ref);
        s.exitOrderId=result.orderId==null||result.orderId.isEmpty()?"TRAIL_SELL_RESERVED_UNKNOWN":result.orderId;
        if(!result.submitted && !result.unknown) {
            s.phase=UnivestStateStore.ACTIVE;s.exitOrderId="";
        }
        s.lastAction="Trailing threshold sell at "+String.format(Locale.US,"%+.2f",currentGain)
                +"% gross • "+result.message;
        UnivestStateStore.put(c,s);
        DiagnosticsStore.trade(c,"OFFICIAL_TRAIL_SELL",s.symbol,s.lastAction,result);
    }
}