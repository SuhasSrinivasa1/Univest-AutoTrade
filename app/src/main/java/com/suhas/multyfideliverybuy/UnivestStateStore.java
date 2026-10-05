package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class UnivestStateStore {
    private static final String FILE = "univest_equity_state";
    private static final String KEY = "states_json";

    static final String ENTRY_PENDING = "ENTRY_PENDING";
    static final String ACTIVE = "ACTIVE";
    static final String WAIT_REENTRY = "WAIT_REENTRY";
    static final String FLAT_WAIT_EXIT = "FLAT_WAIT_EXIT";
    static final String EXITING_PROFIT = "EXITING_PROFIT";
    static final String EXITING_OFFICIAL = "EXITING_OFFICIAL";
    static final String EXITING_STOP = "EXITING_STOP";
    static final String EXITED = "EXITED";
    static final String ERROR = "ERROR";

    static final class State {
        String symbol = "";
        String phase = ACTIVE;
        double anchorPrice = 0.0;
        double tickSize = 0.05;
        int quantity = 0;
        double principal = 0.0;
        double estimatedBuyCharges = 0.0;
        int averageLevel = 0;
        boolean reentryUsed = false;
        String averageGtt1Id = "";
        String averageGtt2Id = "";
        String averageGtt3Id = "";
        double averageGtt1Price = 0.0;
        double averageGtt2Price = 0.0;
        double averageGtt3Price = 0.0;
        String activeGttId = "";
        String protectiveStopGttId = "";
        double protectiveStopPrice = 0.0;
        int protectiveStopQty = 0;
        int protectiveStopBrokerBaseline = -1;
        String activeGttKind = "";
        int activeGttQty = 0;
        double activeGttPrice = 0.0;
        int brokerQtyAfterLastFill = -1;
        String exitOrderId = "";
        int exitRequestedQty = 0;
        String lastAction = "";
        long updatedAt = System.currentTimeMillis();

        JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                j.put("symbol", symbol);
                j.put("phase", phase);
                j.put("anchorPrice", anchorPrice);
                j.put("tickSize", tickSize);
                j.put("quantity", quantity);
                j.put("principal", principal);
                j.put("estimatedBuyCharges", estimatedBuyCharges);
                j.put("averageLevel", averageLevel);
                j.put("reentryUsed", reentryUsed);
                j.put("averageGtt1Id", averageGtt1Id);
                j.put("averageGtt2Id", averageGtt2Id);
                j.put("averageGtt3Id", averageGtt3Id);
                j.put("averageGtt1Price", averageGtt1Price);
                j.put("averageGtt2Price", averageGtt2Price);
                j.put("averageGtt3Price", averageGtt3Price);
                j.put("activeGttId", activeGttId);
                j.put("protectiveStopGttId", protectiveStopGttId);
                j.put("protectiveStopPrice", protectiveStopPrice);
                j.put("protectiveStopQty", protectiveStopQty);
                j.put("protectiveStopBrokerBaseline", protectiveStopBrokerBaseline);
                j.put("activeGttKind", activeGttKind);
                j.put("activeGttQty", activeGttQty);
                j.put("activeGttPrice", activeGttPrice);
                j.put("brokerQtyAfterLastFill", brokerQtyAfterLastFill);
                j.put("exitOrderId", exitOrderId);
                j.put("exitRequestedQty", exitRequestedQty);
                j.put("lastAction", lastAction);
                j.put("updatedAt", updatedAt);
            } catch (Exception ignored) {}
            return j;
        }

        static State fromJson(JSONObject j) {
            State s = new State();
            s.symbol = j.optString("symbol", "");
            s.phase = j.optString("phase", ACTIVE);
            s.anchorPrice = j.optDouble("anchorPrice", 0.0);
            s.tickSize = j.optDouble("tickSize", 0.05);
            s.quantity = j.optInt("quantity", 0);
            s.principal = j.optDouble("principal", 0.0);
            s.estimatedBuyCharges = j.optDouble("estimatedBuyCharges", 0.0);
            s.averageLevel = j.optInt("averageLevel", 0);
            s.reentryUsed = j.optBoolean("reentryUsed", false);
            s.averageGtt1Id = j.optString("averageGtt1Id", "");
            s.averageGtt2Id = j.optString("averageGtt2Id", "");
            s.averageGtt3Id = j.optString("averageGtt3Id", "");
            s.averageGtt1Price = j.optDouble("averageGtt1Price", 0.0);
            s.averageGtt2Price = j.optDouble("averageGtt2Price", 0.0);
            s.averageGtt3Price = j.optDouble("averageGtt3Price", 0.0);
            s.activeGttId = j.optString("activeGttId", "");
            s.protectiveStopGttId = j.optString("protectiveStopGttId", "");
            s.protectiveStopPrice = j.optDouble("protectiveStopPrice", 0.0);
            s.protectiveStopQty = j.optInt("protectiveStopQty", 0);
            s.protectiveStopBrokerBaseline = j.optInt("protectiveStopBrokerBaseline", -1);
            s.activeGttKind = j.optString("activeGttKind", "");
            s.activeGttQty = j.optInt("activeGttQty", 0);
            s.activeGttPrice = j.optDouble("activeGttPrice", 0.0);
            s.brokerQtyAfterLastFill = j.optInt("brokerQtyAfterLastFill", -1);
            s.exitOrderId = j.optString("exitOrderId", "");
            s.exitRequestedQty = j.optInt("exitRequestedQty", 0);
            s.lastAction = j.optString("lastAction", "");
            s.updatedAt = j.optLong("updatedAt", System.currentTimeMillis());
            return s;
        }
    }

    private UnivestStateStore() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized State get(Context c, String symbol) {
        for (State s : all(c)) if (s.symbol.equalsIgnoreCase(symbol)) return s;
        return null;
    }

    static boolean canReservePhase(String phase) {
        return phase == null || EXITED.equals(phase);
    }

    static boolean canExecuteReservedPhase(String phase) {
        return ENTRY_PENDING.equals(phase);
    }

    static synchronized boolean reserveNewEntry(Context c, String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return false;
        String sym = symbol.trim().toUpperCase();
        State existing = get(c, sym);
        if (existing != null && !canReservePhase(existing.phase)) return false;

        State pending = new State();
        pending.symbol = sym;
        pending.phase = ENTRY_PENDING;
        pending.lastAction = "New Univest recommendation reserved before BUY dispatch.";
        put(c, pending);
        return true;
    }

    static synchronized void releasePendingEntry(Context c, String symbol, String reason) {
        State state = get(c, symbol);
        if (state == null || !ENTRY_PENDING.equals(state.phase)) return;
        state.phase = EXITED;
        state.quantity = 0;
        state.principal = 0.0;
        state.estimatedBuyCharges = 0.0;
        state.lastAction = reason == null ? "Pending Univest entry released without an order." : reason;
        put(c, state);
    }

    static synchronized List<State> all(Context c) {
        List<State> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                State s = State.fromJson(j);
                if (!s.symbol.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static synchronized void put(Context c, State state) {
        if (state == null || state.symbol == null || state.symbol.trim().isEmpty()) return;
        state.symbol = state.symbol.trim().toUpperCase();
        state.updatedAt = System.currentTimeMillis();
        List<State> list = all(c);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).symbol.equalsIgnoreCase(state.symbol)) {
                list.set(i, state);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(state);
        save(c, list);
    }

    private static void save(Context c, List<State> list) {
        JSONArray a = new JSONArray();
        for (State s : list) a.put(s.toJson());
        p(c).edit().putString(KEY, a.toString()).apply();
    }
}
