package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class GrowwClient {
    private static final String TOKEN_URL = "https://api.groww.in/v1/token/api/access";
    private static final String ORDER_URL = "https://api.groww.in/v1/order/create";
    private static final String ORDER_DETAIL_URL = "https://api.groww.in/v1/order/detail/";
    private static final String ORDER_LIST_URL = "https://api.groww.in/v1/order/list";
    private static final String ORDER_CANCEL_URL = "https://api.groww.in/v1/order/cancel";
    private static final String GTT_URL = "https://api.groww.in/v1/order-advance/create";
    private static final String LTP_URL = "https://api.groww.in/v1/live-data/ltp";
    private static final String QUOTE_URL = "https://api.groww.in/v1/live-data/quote";
    private static final String USER_URL = "https://api.groww.in/v1/user/detail";
    private static final String GTT_STATUS_URL = "https://api.groww.in/v1/order-advance/status/CASH/GTT/internal/";
    private static final String GTT_CANCEL_URL = "https://api.groww.in/v1/order-advance/cancel/CASH/GTT/";
    private static final String SMART_MODIFY_URL = "https://api.groww.in/v1/order-advance/modify/";
    private static final String POSITION_SYMBOL_URL = "https://api.groww.in/v1/positions/trading-symbol";
    private static final String HOLDINGS_URL = "https://api.groww.in/v1/holdings/user";
    private static final String HISTORICAL_CANDLES_URL = "https://api.groww.in/v1/historical/candles";
    private static final double OFFICIAL_MARKETABLE_LIMIT_BUFFER_PCT = 0.02;
    private static final ExecutorService EXIT_READ_POOL = Executors.newFixedThreadPool(2);


    static final class Candle {
        final long epochSeconds;
        final double open, high, low, close, volume;
        Candle(long epochSeconds, double open, double high, double low, double close, double volume) {
            this.epochSeconds = epochSeconds; this.open = open; this.high = high; this.low = low;
            this.close = close; this.volume = volume;
        }
    }

    static final class QuoteSnapshot {
        final boolean success;
        final double lastPrice;
        final double bidPrice;
        final double offerPrice;
        final int bidQuantity;
        final int offerQuantity;
        final double upperCircuit;
        final double lowerCircuit;
        final long volume;
        final String message;

        QuoteSnapshot(boolean success, double lastPrice, double bidPrice, double offerPrice,
                      int bidQuantity, int offerQuantity, double upperCircuit, double lowerCircuit,
                      long volume, String message) {
            this.success = success;
            this.lastPrice = lastPrice;
            this.bidPrice = bidPrice;
            this.offerPrice = offerPrice;
            this.bidQuantity = bidQuantity;
            this.offerQuantity = offerQuantity;
            this.upperCircuit = upperCircuit;
            this.lowerCircuit = lowerCircuit;
            this.volume = volume;
            this.message = message == null ? "" : message;
        }

        double spreadPct() {
            double mid = bidPrice > 0 && offerPrice > 0 ? (bidPrice + offerPrice) / 2.0 : lastPrice;
            return mid > 0 && bidPrice > 0 && offerPrice >= bidPrice ? (offerPrice - bidPrice) / mid * 100.0 : -1.0;
        }

        double distanceToUpperCircuitPct() {
            return lastPrice > 0 && upperCircuit > 0 ? (upperCircuit / lastPrice - 1.0) * 100.0 : Double.POSITIVE_INFINITY;
        }
    }

    static final class CircuitOrderPlan {
        final boolean useLimit;
        final double referencePrice;
        final double limitPrice;
        final double lowerCircuit;
        final double upperCircuit;
        final String message;

        CircuitOrderPlan(boolean useLimit, double referencePrice, double limitPrice,
                         double lowerCircuit, double upperCircuit, String message) {
            this.useLimit = useLimit;
            this.referencePrice = referencePrice;
            this.limitPrice = limitPrice;
            this.lowerCircuit = lowerCircuit;
            this.upperCircuit = upperCircuit;
            this.message = message == null ? "" : message;
        }
    }

    static final class Result {
        final boolean success;
        final boolean unknown;
        final int httpCode;
        final String message;
        Result(boolean success, boolean unknown, int httpCode, String message) {
            this.success = success;
            this.unknown = unknown;
            this.httpCode = httpCode;
            this.message = message;
        }
    }

    static final class ManualResult {
        final boolean entrySubmitted;
        final boolean targetSubmitted;
        final boolean unknown;
        final String message;
        ManualResult(boolean entrySubmitted, boolean targetSubmitted, boolean unknown, String message) {
            this.entrySubmitted = entrySubmitted;
            this.targetSubmitted = targetSubmitted;
            this.unknown = unknown;
            this.message = message;
        }
    }

    static final class MultyfiEntryResult {
        final boolean entrySubmitted;
        final boolean gttSubmitted;
        final boolean unknown;
        final String orderId;
        final String smartOrderId;
        final int filledQuantity;
        final double averagePrice;
        final double targetPrice;
        final double breakEvenPrice;
        final double tickSize;
        final String message;
        MultyfiEntryResult(boolean entrySubmitted, boolean gttSubmitted, boolean unknown, String orderId,
                           String smartOrderId, int filledQuantity, double averagePrice, double targetPrice,
                           double breakEvenPrice, double tickSize, String message) {
            this.entrySubmitted = entrySubmitted; this.gttSubmitted = gttSubmitted; this.unknown = unknown;
            this.orderId = orderId; this.smartOrderId = smartOrderId; this.filledQuantity = filledQuantity;
            this.averagePrice = averagePrice; this.targetPrice = targetPrice; this.breakEvenPrice = breakEvenPrice;
            this.tickSize = tickSize; this.message = message;
        }
    }

    static final class ShortOcoResult {
        final boolean shortSubmitted;
        final boolean ocoSubmitted;
        final boolean unknown;
        final int quantity;
        final double averageShortPrice;
        final double targetPrice;
        final double stopPrice;
        final String smartOrderId;
        final String message;
        ShortOcoResult(boolean shortSubmitted, boolean ocoSubmitted, boolean unknown, int quantity,
                       double averageShortPrice, double targetPrice, double stopPrice, String smartOrderId, String message) {
            this.shortSubmitted = shortSubmitted; this.ocoSubmitted = ocoSubmitted; this.unknown = unknown;
            this.quantity = quantity; this.averageShortPrice = averageShortPrice; this.targetPrice = targetPrice;
            this.stopPrice = stopPrice; this.smartOrderId = smartOrderId; this.message = message;
        }
    }

    static final class ExecutionResult {
        final boolean submitted;
        final boolean filled;
        final boolean unknown;
        final String orderId;
        final int requestedQuantity;
        final int filledQuantity;
        final double averagePrice;
        final double sizingLtp;
        final long dispatchAtMillis;
        final String message;
        ExecutionResult(boolean submitted, boolean filled, boolean unknown, String orderId, int requestedQuantity,
                        int filledQuantity, double averagePrice, double sizingLtp, long dispatchAtMillis, String message) {
            this.submitted = submitted; this.filled = filled; this.unknown = unknown; this.orderId = orderId;
            this.requestedQuantity = requestedQuantity; this.filledQuantity = filledQuantity;
            this.averagePrice = averagePrice; this.sizingLtp = sizingLtp; this.dispatchAtMillis = dispatchAtMillis;
            this.message = message;
        }
    }

    static final class GttResult {
        final boolean success;
        final boolean unknown;
        final String smartOrderId;
        final String message;
        GttResult(boolean success, boolean unknown, String smartOrderId, String message) {
            this.success = success; this.unknown = unknown; this.smartOrderId = smartOrderId; this.message = message;
        }
    }

    static final class GttStatusResult {
        final boolean success;
        final String status;
        final String triggeredAt;
        final String message;
        GttStatusResult(boolean success, String status, String triggeredAt, String message) {
            this.success = success; this.status = status; this.triggeredAt = triggeredAt; this.message = message;
        }
    }

    static final class PositionSnapshot {
        final boolean success;
        final int quantity;
        final double netPrice;
        final String message;
        PositionSnapshot(boolean success, int quantity, double netPrice, String message) {
            this.success = success; this.quantity = quantity; this.netPrice = netPrice; this.message = message;
        }
    }

    static final class ExitSnapshot {
        final boolean success;
        final PositionSnapshot holding;
        final QuoteSnapshot quote;
        final double executableSellPrice;
        final String message;

        ExitSnapshot(boolean success, PositionSnapshot holding, QuoteSnapshot quote,
                     double executableSellPrice, String message) {
            this.success = success;
            this.holding = holding;
            this.quote = quote;
            this.executableSellPrice = executableSellPrice;
            this.message = message == null ? "" : message;
        }
    }

    static final class GreenSellPlan {
        final boolean canSell;
        final double averageBuyPrice;
        final double executableSellPrice;
        final double minimumGreenPrice;
        final double limitPrice;
        final String reason;

        GreenSellPlan(boolean canSell, double averageBuyPrice, double executableSellPrice,
                      double minimumGreenPrice, double limitPrice, String reason) {
            this.canSell = canSell;
            this.averageBuyPrice = averageBuyPrice;
            this.executableSellPrice = executableSellPrice;
            this.minimumGreenPrice = minimumGreenPrice;
            this.limitPrice = limitPrice;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class OrderSubmit {
        final boolean success;
        final boolean unknown;
        final int code;
        final String orderId;
        final String message;
        OrderSubmit(boolean success, boolean unknown, int code, String orderId, String message) {
            this.success = success;
            this.unknown = unknown;
            this.code = code;
            this.orderId = orderId;
            this.message = message;
        }
    }

    private static final class Fill {
        final boolean confirmed;
        final int quantity;
        final double averagePrice;
        final String status;
        Fill(boolean confirmed, int quantity, double averagePrice, String status) {
            this.confirmed = confirmed;
            this.quantity = quantity;
            this.averagePrice = averagePrice;
            this.status = status;
        }
    }

    private GrowwClient() {}

    static Result authenticate(Context context) {
        String totpToken = AppPrefs.getApiKey(context);
        String secret = AppPrefs.getTotpSecret(context);
        if (totpToken.isEmpty() || secret.isEmpty()) {
            return new Result(false, false, 0, "Groww TOTP token and TOTP Base32 secret are required.");
        }
        long now = System.currentTimeMillis();
        long cooldown = AppPrefs.getAuthCooldownUntil(context);
        if (cooldown > now) {
            long secs = Math.max(1L, (cooldown - now + 999L) / 1000L);
            return new Result(false, false, 429, "Groww authentication cooldown active. Retry after about " + secs + " seconds.");
        }
        if (!isValidBase32(secret)) {
            return new Result(false, false, 0, "TOTP secret is not valid Base32. Use the secret shown with the Groww TOTP token/QR.");
        }
        try {
            String totp = generateTotp(secret, now);
            DiagnosticsStore.broker(context, "GROWW_TOTP_REQUEST", "", true,
                    "Preparing Groww TOTP token exchange • token length " + totpToken.length() + " • generated 6-digit TOTP locally.");
            JSONObject body = new JSONObject();
            body.put("key_type", "totp");
            body.put("totp", totp);
            // Groww documents X-API-VERSION as mandatory on API requests. It is harmless on token exchange and
            // avoids version-gateway rejection seen in earlier builds. Secrets/TOTP are never logged.
            HttpResponse r = post(TOKEN_URL, totpToken, body.toString(), true);
            if (r.code == 429) {
                AppPrefs.setAuthCooldownUntil(context, now + 60000L);
                String msg = "Groww TOTP authentication rate-limited (HTTP 429). Automatic retry blocked for 60 seconds.";
                DiagnosticsStore.broker(context, "GROWW_TOTP_TOKEN_EXCHANGE", "", false, msg);
                return new Result(false, false, r.code, msg);
            }
            if (r.code >= 200 && r.code < 300) {
                JSONObject json = new JSONObject(r.body);
                String token = json.optString("token", "");
                if (token.isEmpty()) {
                    JSONObject payload = json.optJSONObject("payload");
                    if (payload != null) token = payload.optString("token", "");
                }
                if (!token.isEmpty()) {
                    AppPrefs.setAccessToken(context, token); AppPrefs.clearAuthCooldown(context);
                    String msg = "Groww TOTP authenticated. Access token cached.";
                    DiagnosticsStore.broker(context, "GROWW_TOTP_TOKEN_EXCHANGE", "", true, msg);
                    return new Result(true, false, r.code, msg);
                }
                String msg = "Groww authentication response did not contain an access token.";
                DiagnosticsStore.broker(context, "GROWW_TOTP_TOKEN_EXCHANGE", "", false, msg);
                return new Result(false, false, r.code, msg);
            }
            String msg = "Groww TOTP authentication rejected (HTTP " + r.code + "): " + shortText(r.body);
            DiagnosticsStore.broker(context, "GROWW_TOTP_TOKEN_EXCHANGE", "", false, msg);
            return new Result(false, false, r.code, msg);
        } catch (Exception e) {
            String msg = "Groww TOTP authentication failed: " + safeMessage(e);
            DiagnosticsStore.broker(context, "GROWW_TOTP_TOKEN_EXCHANGE", "", false, msg);
            return new Result(false, false, 0, msg);
        }
    }

    static Result refreshAndTestAuthentication(Context context) {
        // First reuse a cached access token. This prevents unnecessary /token/api/access calls and protects
        // against Groww's authentication rate limits. Only generate a new token when the cached token is rejected.
        if (!AppPrefs.getAccessToken(context).isEmpty()) {
            Result cached = testAccessToken(context);
            if (cached.success) { AppPrefs.setAuthTest(context, true, cached.message); return cached; }
            if (cached.httpCode != 401 && cached.httpCode != 403) { AppPrefs.setAuthTest(context, false, cached.message); return cached; }
            AppPrefs.clearAccessToken(context);
        }
        Result auth = authenticate(context);
        if (!auth.success) { AppPrefs.setAuthTest(context, false, auth.message); return auth; }
        Result tested = testAccessToken(context);
        AppPrefs.setAuthTest(context, tested.success, tested.message);
        return tested;
    }

    private static Result testAccessToken(Context context) {
        try {
            HttpResponse r = get(USER_URL, AppPrefs.getAccessToken(context), true);
            if (r.code >= 200 && r.code < 300) {
                JSONObject json = new JSONObject(r.body);
                boolean apiSuccess = "SUCCESS".equalsIgnoreCase(json.optString("status", "SUCCESS"));
                JSONObject payload = json.optJSONObject("payload");
                boolean nseEnabled = payload != null && payload.optBoolean("nse_enabled", false);
                boolean cashEnabled = false;
                if (payload != null && payload.optJSONArray("active_segments") != null) {
                    for (int i = 0; i < payload.optJSONArray("active_segments").length(); i++) {
                        if ("CASH".equalsIgnoreCase(payload.optJSONArray("active_segments").optString(i))) { cashEnabled = true; break; }
                    }
                }
                if (apiSuccess && nseEnabled && cashEnabled)
                    return new Result(true, false, r.code, "Groww READY — cached/access token valid; NSE CASH profile test passed.");
                return new Result(false, false, r.code, "Groww token works, but NSE CASH is not enabled in the user profile.");
            }
            return new Result(false, false, r.code, "Groww access-token test failed (HTTP " + r.code + "): " + shortText(r.body));
        } catch (Exception e) {
            return new Result(false, false, 0, "Groww access-token test failed: " + safeMessage(e));
        }
    }

    static Result placeDeliveryMarketBuy(Context context, String symbol, int quantity, String referenceId) {
        OrderSubmit s = submitMarketOrder(context, symbol, quantity, "CNC", "BUY", referenceId);
        return new Result(s.success, s.unknown, s.code, s.message);
    }


    /** Univest equity fast path: one LTP read only for ₹20k quantity sizing, then immediate CNC MARKET BUY. */
    /**
     * Official Univest CNC BUY. Groww MARKET orders can be internally price-protected beyond the
     * exchange circuit band and then rejected by RMS. When quote/circuit data is available, submit
     * an aggressive DAY LIMIT inside the live circuit band instead. This preserves immediate-entry
     * intent while preventing a protected-market circuit breach.
     */
    static ExecutionResult placeUnivestCncMarketBuy(Context context, String symbol, int budget, String referenceId) {
        try {
            QuoteSnapshot quote = getQuoteForAutomation(context, symbol);
            double ltp = quote.success && quote.lastPrice > 0 ? quote.lastPrice : getLtp(context, symbol);
            if (!(ltp > 0)) return new ExecutionResult(false, false, false, "", 0, 0, 0, 0, 0,
                    "No valid Groww LTP for " + symbol + "; no Univest order submitted.");

            double tick = tickSizeFor(context, symbol);
            CircuitOrderPlan plan = circuitOrderPlan(quote, "BUY", tick);
            double sizingPrice = plan.useLimit && plan.limitPrice > 0 ? plan.limitPrice : ltp;
            int quantity = (int)Math.floor(budget / sizingPrice);
            if (quantity < 1) return new ExecutionResult(false, false, false, "", 0, 0, 0, ltp, 0,
                    symbol + " circuit-safe executable price ₹" + money(sizingPrice)
                            + " is above the ₹" + inr(budget) + " Univest budget.");

            long dispatch = System.currentTimeMillis();
            OrderSubmit entry;
            if (plan.useLimit) {
                DiagnosticsStore.broker(context, "CIRCUIT_GUARD_BUY", symbol, true,
                        "Official BUY uses circuit-safe marketable LIMIT ₹" + money(plan.limitPrice)
                                + " • band ₹" + money(plan.lowerCircuit) + "–₹" + money(plan.upperCircuit)
                                + " • reference ₹" + money(plan.referencePrice) + ".");
                entry = submitLimitOrder(context, symbol, quantity, "CNC", "BUY", plan.limitPrice, referenceId);
            } else {
                DiagnosticsStore.broker(context, "CIRCUIT_GUARD_UNAVAILABLE_BUY", symbol, true,
                        "Circuit range unavailable; preserving legacy CNC MARKET BUY path. " + plan.message);
                entry = submitMarketOrder(context, symbol, quantity, "CNC", "BUY", referenceId);
            }

            if (!entry.success) return new ExecutionResult(false, false, entry.unknown, entry.orderId,
                    quantity, 0, 0, ltp, dispatch, entry.message);
            if (entry.orderId.isEmpty()) return new ExecutionResult(true, false, false, "", quantity, 0, 0, ltp, dispatch,
                    (plan.useLimit ? "Circuit-safe CNC LIMIT BUY" : "CNC MARKET BUY")
                            + " accepted, but Groww returned no order ID. Verify fill manually.");

            Fill fill = awaitExecution(context, entry.orderId, quantity);
            if (isTerminalFailureStatus(fill.status)) {
                DiagnosticsStore.broker(context, "ORDER_BUY_CNC_TERMINAL_REJECT", symbol, false,
                        "Groww accepted then terminally rejected official BUY • " + fill.status);
                return new ExecutionResult(false, false, false, entry.orderId, quantity, fill.quantity,
                        fill.averagePrice, ltp, dispatch,
                        "Official BUY rejected after submission • " + fill.status);
            }
            return new ExecutionResult(true, fill.quantity >= quantity && fill.averagePrice > 0, false,
                    entry.orderId, quantity, fill.quantity, fill.averagePrice, ltp, dispatch,
                    fill.quantity > 0 && fill.averagePrice > 0
                            ? (plan.useLimit ? "Circuit-safe CNC LIMIT BUY" : "CNC MARKET BUY")
                                + " executed • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            : (plan.useLimit ? "Circuit-safe CNC LIMIT BUY" : "CNC MARKET BUY")
                                + " accepted • order " + entry.orderId + " • fill not confirmed in time.");
        } catch (SocketTimeoutException e) {
            return new ExecutionResult(false, false, true, "", 0, 0, 0, 0, 0,
                    "Univest entry status unknown after network timeout. Check Groww before any manual retry.");
        } catch (Exception e) {
            return new ExecutionResult(false, false, true, "", 0, 0, 0, 0, 0,
                    "Univest entry failed/unknown: " + safeMessage(e));
        }
    }

    static ExecutionResult placeUnivestCncMarketSell(Context context, String symbol, int quantity, String referenceId) {
        if (quantity <= 0) return new ExecutionResult(false, false, false, "", 0, 0, 0, 0, 0, "Sell quantity is zero.");
        long dispatch = System.currentTimeMillis();
        try {
            QuoteSnapshot quote = getQuoteForAutomation(context, symbol);
            double tick = tickSizeFor(context, symbol);
            CircuitOrderPlan plan = circuitOrderPlan(quote, "SELL", tick);
            OrderSubmit sell;
            if (plan.useLimit) {
                DiagnosticsStore.broker(context, "CIRCUIT_GUARD_SELL", symbol, true,
                        "Official SELL uses circuit-safe marketable LIMIT ₹" + money(plan.limitPrice)
                                + " • band ₹" + money(plan.lowerCircuit) + "–₹" + money(plan.upperCircuit)
                                + " • reference ₹" + money(plan.referencePrice) + ".");
                sell = submitLimitOrder(context, symbol, quantity, "CNC", "SELL", plan.limitPrice, referenceId);
            } else {
                DiagnosticsStore.broker(context, "CIRCUIT_GUARD_UNAVAILABLE_SELL", symbol, true,
                        "Circuit range unavailable; preserving legacy CNC MARKET SELL path. " + plan.message);
                sell = submitMarketOrder(context, symbol, quantity, "CNC", "SELL", referenceId);
            }
            if (!sell.success) return new ExecutionResult(false, false, sell.unknown, sell.orderId,
                    quantity, 0, 0, quote.lastPrice, dispatch, sell.message);
            if (sell.orderId.isEmpty()) return new ExecutionResult(true, false, false, "", quantity, 0, 0,
                    quote.lastPrice, dispatch,
                    (plan.useLimit ? "Circuit-safe CNC LIMIT SELL" : "CNC MARKET SELL")
                            + " accepted, but Groww returned no order ID. Verify execution manually.");

            Fill fill = awaitExecution(context, sell.orderId, quantity);
            if (isTerminalFailureStatus(fill.status)) {
                DiagnosticsStore.broker(context, "ORDER_SELL_CNC_TERMINAL_REJECT", symbol, false,
                        "Groww accepted then terminally rejected official SELL • " + fill.status);
                return new ExecutionResult(false, false, false, sell.orderId, quantity, fill.quantity,
                        fill.averagePrice, quote.lastPrice, dispatch,
                        "Official SELL rejected after submission • " + fill.status);
            }
            return new ExecutionResult(true, fill.quantity >= quantity && fill.averagePrice > 0, false,
                    sell.orderId, quantity, fill.quantity, fill.averagePrice, quote.lastPrice, dispatch,
                    fill.quantity > 0 && fill.averagePrice > 0
                            ? (plan.useLimit ? "Circuit-safe CNC LIMIT SELL" : "CNC MARKET SELL")
                                + " executed • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            : (plan.useLimit ? "Circuit-safe CNC LIMIT SELL" : "CNC MARKET SELL")
                                + " accepted • order " + sell.orderId + " • fill not confirmed in time.");
        } catch (Exception e) {
            return new ExecutionResult(false, false, true, "", quantity, 0, 0, 0, dispatch,
                    "Official SELL status unknown after circuit-safe execution error: " + safeMessage(e));
        }
    }

    static ExecutionResult checkCashOrderExecution(Context context, String orderId, int requestedQty) {
        if (orderId == null || orderId.isEmpty()) return new ExecutionResult(false, false, false, "", requestedQty, 0, 0, 0, 0, "No order ID.");
        try {
            Fill fill = getExecutionOnce(context, orderId);
            return new ExecutionResult(true, fill.quantity >= requestedQty && fill.averagePrice > 0, false, orderId, requestedQty,
                    fill.quantity, fill.averagePrice, 0, 0, fill.status);
        } catch (Exception e) {
            return new ExecutionResult(true, false, true, orderId, requestedQty, 0, 0, 0, 0, safeMessage(e));
        }
    }

    static double getLtpForAutomation(Context context, String symbol) throws Exception { return getLtp(context, symbol); }

    static QuoteSnapshot getQuoteForAutomation(Context context, String symbol) {
        if (symbol == null || symbol.trim().isEmpty())
            return new QuoteSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, "Missing trading symbol.");
        try {
            String endpoint = QUOTE_URL + "?exchange=NSE&segment=CASH&trading_symbol="
                    + URLEncoder.encode(symbol.trim().toUpperCase(Locale.US), StandardCharsets.UTF_8.name());
            HttpResponse r = get(endpoint, ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result a = authenticate(context);
                if (!a.success) return new QuoteSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, a.message);
                r = get(endpoint, AppPrefs.getAccessToken(context), true);
            }
            if (r.code < 200 || r.code >= 300)
                return new QuoteSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0,
                        "Quote HTTP " + r.code + ": " + shortText(r.body));
            JSONObject root = new JSONObject(r.body);
            JSONObject p = root.optJSONObject("payload");
            if (p == null) return new QuoteSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, "Quote payload missing.");
            return new QuoteSnapshot(true,
                    p.optDouble("last_price", 0), p.optDouble("bid_price", 0), p.optDouble("offer_price", 0),
                    p.optInt("bid_quantity", 0), p.optInt("offer_quantity", 0),
                    p.optDouble("upper_circuit_limit", 0), p.optDouble("lower_circuit_limit", 0),
                    p.optLong("volume", 0), "Quote OK");
        } catch (Exception t) {
            return new QuoteSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, safeMessage(t));
        }
    }

    static ExitSnapshot getFastExitSnapshot(Context context, String symbol) {
        Future<PositionSnapshot> position = EXIT_READ_POOL.submit(() -> getCncPosition(context, symbol));
        Future<QuoteSnapshot> quote = EXIT_READ_POOL.submit(() -> getQuoteForAutomation(context, symbol));
        try {
            PositionSnapshot p = position.get(6, TimeUnit.SECONDS);
            QuoteSnapshot q = quote.get(6, TimeUnit.SECONDS);
            double executable = q != null && q.bidPrice > 0 ? q.bidPrice
                    : (q != null ? q.lastPrice : 0.0);
            boolean ok = p != null && p.success && q != null && q.success;
            String msg = "Parallel EXIT snapshot • holding=" + (p == null ? "missing" : p.quantity)
                    + " • avg=₹" + money(p == null ? 0 : p.netPrice)
                    + " • bid/LTP=₹" + money(executable)
                    + " • holdingOk=" + (p != null && p.success)
                    + " • quoteOk=" + (q != null && q.success);
            return new ExitSnapshot(ok,
                    p == null ? new PositionSnapshot(false, -1, 0, "Holding snapshot missing.") : p,
                    q == null ? new QuoteSnapshot(false,0,0,0,0,0,0,0,0,"Quote snapshot missing.") : q,
                    executable, msg);
        } catch (Exception e) {
            position.cancel(true); quote.cancel(true);
            return new ExitSnapshot(false,
                    new PositionSnapshot(false, -1, 0, "Parallel holding read failed."),
                    new QuoteSnapshot(false,0,0,0,0,0,0,0,0,"Parallel quote read failed."),
                    0, "Parallel EXIT snapshot failed: " + safeMessage(e));
        }
    }

    static GreenSellPlan greenSellPlan(ExitSnapshot snap, double tickSize) {
        double tick = tickSize > 0 ? tickSize : 0.05;
        if (snap == null || snap.holding == null || snap.quote == null || !snap.holding.success) {
            return new GreenSellPlan(false, 0, 0, 0, 0, "Broker holding is unavailable.");
        }
        double avg = snap.holding.netPrice;
        if (!(avg > 0)) {
            return new GreenSellPlan(false, avg, snap.executableSellPrice, 0, 0,
                    "Broker average buy price is unavailable; green-only EXIT fails closed.");
        }
        if (!snap.quote.success) {
            return new GreenSellPlan(false, avg, 0, 0, 0,
                    "Live quote is unavailable; green-only EXIT fails closed.");
        }
        double executable = snap.quote.bidPrice > 0 ? snap.quote.bidPrice : snap.quote.lastPrice;
        if (!(executable > 0)) {
            return new GreenSellPlan(false, avg, executable, 0, 0,
                    "No executable bid/LTP available.");
        }

        double minimumGreen = roundUpToTick(avg + tick, tick);
        if (executable + 1e-9 < minimumGreen) {
            return new GreenSellPlan(false, avg, executable, minimumGreen, 0,
                    "Deferred: executable sell price ₹" + money(executable)
                            + " is not above broker average ₹" + money(avg)
                            + " by at least one tick.");
        }

        CircuitOrderPlan circuit = circuitOrderPlan(snap.quote, "SELL", tick);
        double limit = circuit.useLimit ? circuit.limitPrice : minimumGreen;
        limit = Math.max(limit, minimumGreen);
        if (snap.quote.lowerCircuit > 0)
            limit = Math.max(limit, roundUpToTick(snap.quote.lowerCircuit, tick));

        if (limit > executable + 1e-9) {
            return new GreenSellPlan(false, avg, executable, minimumGreen, limit,
                    "Deferred: green-protection limit ₹" + money(limit)
                            + " is above the currently executable price ₹" + money(executable) + ".");
        }
        return new GreenSellPlan(true, avg, executable, minimumGreen, limit,
                "Green EXIT ready • avg ₹" + money(avg)
                        + " • executable ₹" + money(executable)
                        + " • protected limit ₹" + money(limit) + ".");
    }

    static ExecutionResult placeUnivestCncGreenSell(Context context, String symbol, int quantity,
                                                    ExitSnapshot snap, double tickSize,
                                                    String referenceId) {
        if (quantity <= 0)
            return new ExecutionResult(false, false, false, "", 0, 0, 0, 0, 0,
                    "Green EXIT quantity is zero.");
        GreenSellPlan plan = greenSellPlan(snap, tickSize);
        if (!plan.canSell)
            return new ExecutionResult(false, false, false, "", quantity, 0, 0,
                    snap == null || snap.quote == null ? 0 : snap.quote.lastPrice,
                    System.currentTimeMillis(), plan.reason);

        long dispatch = System.currentTimeMillis();
        DiagnosticsStore.broker(context, "GREEN_EXIT_GUARD_READY", symbol, true, plan.reason);
        OrderSubmit sell = submitLimitOrder(context, symbol, quantity, "CNC", "SELL",
                plan.limitPrice, referenceId);
        if (!sell.success)
            return new ExecutionResult(false, false, sell.unknown, sell.orderId, quantity, 0, 0,
                    snap.quote.lastPrice, dispatch, sell.message);
        if (sell.orderId.isEmpty())
            return new ExecutionResult(true, false, false, "", quantity, 0, 0,
                    snap.quote.lastPrice, dispatch,
                    "Green-protected CNC LIMIT SELL accepted, but Groww returned no order ID.");

        try {
            Fill fill = awaitExecution(context, sell.orderId, quantity);
            if (isTerminalFailureStatus(fill.status)) {
                DiagnosticsStore.broker(context, "GREEN_EXIT_TERMINAL_REJECT", symbol, false, fill.status);
                return new ExecutionResult(false, false, false, sell.orderId, quantity,
                        fill.quantity, fill.averagePrice, snap.quote.lastPrice, dispatch,
                        "Green-protected official SELL rejected after submission • " + fill.status);
            }
            return new ExecutionResult(true, fill.quantity >= quantity && fill.averagePrice > 0,
                    false, sell.orderId, quantity, fill.quantity, fill.averagePrice,
                    snap.quote.lastPrice, dispatch,
                    fill.quantity > 0 && fill.averagePrice > 0
                            ? "Green-protected CNC LIMIT SELL executed • qty " + fill.quantity
                                + " • avg ₹" + money(fill.averagePrice)
                                + " • broker buy avg ₹" + money(plan.averageBuyPrice)
                            : "Green-protected CNC LIMIT SELL accepted • order " + sell.orderId
                                + " • fill not confirmed in time.");
        } catch (Exception e) {
            return new ExecutionResult(true, false, true, sell.orderId, quantity, 0, 0,
                    snap.quote.lastPrice, dispatch,
                    "Green-protected SELL accepted; fill confirmation uncertain: " + safeMessage(e));
        }
    }

    /**
     * Research-only market BUY. Quantity uses the executable offer when available plus a small
     * slippage reserve so the configured Research budget remains a ceiling under normal liquidity.
     * Official Univest fast-path sizing is deliberately untouched.
     */
    static ExecutionResult placeResearchCncMarketBuy(Context context, String symbol, int budget, String referenceId) {
        QuoteSnapshot q = getQuoteForAutomation(context, symbol);
        if (!q.success || !(q.lastPrice > 0))
            return new ExecutionResult(false, false, false, "", 0, 0, 0, 0, 0,
                    "Research quote unavailable: " + q.message);
        double basis = Math.max(q.lastPrice, q.offerPrice > 0 ? q.offerPrice : q.lastPrice);
        double guarded = basis * 1.005;
        int quantity = (int)Math.floor(budget / guarded);
        if (quantity < 1)
            return new ExecutionResult(false, false, false, "", 0, 0, 0, q.lastPrice, 0,
                    symbol + " executable price is above the Research budget.");
        long dispatch = System.currentTimeMillis();
        CircuitOrderPlan circuit = circuitOrderPlan(q, "BUY", tickSizeFor(context, symbol));
        OrderSubmit entry = circuit.useLimit
                ? submitLimitOrder(context, symbol, quantity, "CNC", "BUY", circuit.limitPrice, referenceId)
                : submitMarketOrder(context, symbol, quantity, "CNC", "BUY", referenceId);
        if (!entry.success)
            return new ExecutionResult(false, false, entry.unknown, entry.orderId, quantity, 0, 0, q.lastPrice, dispatch, entry.message);
        if (entry.orderId.isEmpty())
            return new ExecutionResult(true, false, false, "", quantity, 0, 0, q.lastPrice, dispatch,
                    "Research CNC MARKET BUY accepted, but Groww returned no order ID.");
        try {
            Fill fill = awaitExecution(context, entry.orderId, quantity);
            return new ExecutionResult(true, fill.quantity > 0 && fill.averagePrice > 0, false, entry.orderId, quantity,
                    fill.quantity, fill.averagePrice, q.lastPrice, dispatch,
                    fill.quantity > 0 && fill.averagePrice > 0
                            ? "Research CNC MARKET BUY executed • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            : "Research CNC MARKET BUY accepted • fill not confirmed in time.");
        } catch (Exception t) {
            return new ExecutionResult(true, false, true, entry.orderId, quantity, 0, 0, q.lastPrice, dispatch,
                    "Research CNC MARKET BUY accepted; fill confirmation uncertain: " + safeMessage(t));
        }
    }

    static List<Candle> getHistoricalCandles(Context context, String symbol, long startMillis, long endMillis, String candleInterval) throws Exception {
        if (symbol == null || symbol.trim().isEmpty()) return new ArrayList<>();
        long start = Math.max(0L, startMillis / 1000L);
        long end = Math.max(start + 60L, endMillis / 1000L);
        String interval = candleInterval == null || candleInterval.trim().isEmpty() ? "1day" : candleInterval.trim();
        String endpoint = HISTORICAL_CANDLES_URL
                + "?exchange=NSE&segment=CASH&groww_symbol=" + URLEncoder.encode("NSE-" + symbol.trim().toUpperCase(Locale.US), StandardCharsets.UTF_8.name())
                + "&start_time=" + start
                + "&end_time=" + end
                + "&candle_interval=" + URLEncoder.encode(interval, StandardCharsets.UTF_8.name());
        HttpResponse r = get(endpoint, ensureToken(context), true);
        if (r.code == 401 || r.code == 403) {
            AppPrefs.clearAccessToken(context);
            Result a = authenticate(context);
            if (!a.success) throw new IllegalStateException(a.message);
            r = get(endpoint, AppPrefs.getAccessToken(context), true);
        }
        if (r.code < 200 || r.code >= 300) throw new IllegalStateException("Historical candle HTTP " + r.code + ": " + shortText(r.body));
        JSONObject json = new JSONObject(r.body);
        JSONObject payload = json.optJSONObject("payload");
        JSONArray candles = payload == null ? null : payload.optJSONArray("candles");
        ArrayList<Candle> out = new ArrayList<>();
        if (candles == null) return out;
        for (int i = 0; i < candles.length(); i++) {
            JSONArray a = candles.optJSONArray(i);
            if (a == null || a.length() < 6) continue;
            long epoch = parseEpochSeconds(a.opt(0));
            if (epoch <= 0L) continue;
            out.add(new Candle(epoch, a.optDouble(1, 0), a.optDouble(2, 0),
                    a.optDouble(3, 0), a.optDouble(4, 0), a.optDouble(5, 0)));
        }
        return out;
    }

    static GttResult createResearchCncSellTarget(Context context, String symbol, int quantity, double target, String referenceId) {
        return createGttDetailed(context, symbol, quantity, "CNC", "SELL", "UP", target, referenceId);
    }



    static GttResult createUnivestCncBuyGtt(Context context, String symbol, int quantity, double target, String referenceId) {
        return createGttDetailed(context, symbol, quantity, "CNC", "BUY", "DOWN", target, referenceId);
    }

    static GttResult createUnivestCncStopGtt(Context context, String symbol, int quantity, double trigger, String referenceId) {
        return createGttMarketDetailed(context, symbol, quantity, "CNC", "SELL", "DOWN", trigger, referenceId);
    }

    static Result modifyUnivestCncStopGtt(Context context, String smartOrderId, int quantity, double trigger) {
        if (smartOrderId == null || smartOrderId.isEmpty()) return new Result(false, false, 0, "No protective stop GTT id to modify.");
        try {
            JSONObject order = new JSONObject();
            order.put("order_type", "MARKET");
            order.put("price", JSONObject.NULL);
            order.put("transaction_type", "SELL");
            JSONObject body = new JSONObject();
            body.put("smart_order_type", "GTT");
            body.put("segment", "CASH");
            body.put("quantity", quantity);
            body.put("trigger_price", money(trigger));
            body.put("trigger_direction", "DOWN");
            body.put("order", order);
            HttpResponse r = put(SMART_MODIFY_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return a;
                r = put(SMART_MODIFY_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) return new Result(true, false, r.code, "Protective stop GTT updated • qty " + quantity + " • trigger ₹" + money(trigger));
            return new Result(false, false, r.code, "Protective stop GTT modify rejected HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "Protective stop GTT modification status unknown after timeout; verify Smart Orders before any retry.");
        } catch (Exception e) { return new Result(false, true, 0, "Protective stop GTT modify error: " + safeMessage(e)); }
    }

    static GttStatusResult getCashGttStatus(Context context, String smartOrderId) {
        try {
            HttpResponse r = get(GTT_STATUS_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return new GttStatusResult(false, "", "", a.message);
                r = get(GTT_STATUS_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), AppPrefs.getAccessToken(context), true);
            }
            if (r.code >= 200 && r.code < 300) {
                JSONObject j = new JSONObject(r.body); JSONObject p = j.optJSONObject("payload");
                if (p != null) return new GttStatusResult(true, p.optString("status", ""), p.optString("triggered_at", ""), "OK");
            }
            return new GttStatusResult(false, "", "", "GTT status HTTP " + r.code + ": " + shortText(r.body));
        } catch (Exception e) { return new GttStatusResult(false, "", "", safeMessage(e)); }
    }

    static Result cancelCashGtt(Context context, String smartOrderId) {
        if (smartOrderId == null || smartOrderId.isEmpty()) return new Result(true, false, 0, "No Univest GTT to cancel.");
        try {
            HttpResponse r = postEmpty(GTT_CANCEL_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return a;
                r = postEmpty(GTT_CANCEL_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), AppPrefs.getAccessToken(context), true);
            }
            if (r.code >= 200 && r.code < 300) return new Result(true, false, r.code, "Univest GTT cancelled • " + smartOrderId);
            // If broker says it is already completed/cancelled, cleanup can be treated as done.
            if (r.code == 400 || r.code == 404 || r.code == 409) return new Result(true, false, r.code, "Univest GTT already inactive/finished • " + smartOrderId);
            return new Result(false, false, r.code, "GTT cancel HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "GTT cancellation status unknown after timeout; verify Groww Smart Orders.");
        } catch (Exception e) { return new Result(false, true, 0, "GTT cancellation error: " + safeMessage(e)); }
    }

    static boolean shouldCancelCncSellOrder(String wantedSymbol, String orderSymbol, String product,
                                            String transactionType, String orderStatus, int remainingQuantity) {
        if (wantedSymbol == null || orderSymbol == null || !wantedSymbol.equalsIgnoreCase(orderSymbol)) return false;
        if (!"CNC".equalsIgnoreCase(product)) return false;
        if (!"SELL".equalsIgnoreCase(transactionType)) return false;
        if (remainingQuantity <= 0) return false;
        String st = orderStatus == null ? "" : orderStatus.trim().toUpperCase(Locale.US);
        return !("COMPLETED".equals(st) || "EXECUTED".equals(st) || "CANCELLED".equals(st)
                || "REJECTED".equals(st) || "FAILED".equals(st));
    }


    static Result checkForActiveCncBuyOrder(Context context, String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return new Result(false, false, 0, "Missing symbol for BUY-order reconciliation.");
        String wanted = symbol.trim().toUpperCase(Locale.US);
        try {
            for (int page = 0; page < 5; page++) {
                String endpoint = ORDER_LIST_URL + "?segment=CASH&page=" + page + "&page_size=100";
                HttpResponse r = get(endpoint, ensureToken(context), true);
                if (r.code == 401 || r.code == 403) {
                    AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return a;
                    r = get(endpoint, AppPrefs.getAccessToken(context), true);
                }
                if (r.code < 200 || r.code >= 300) return new Result(false, true, r.code, "Order-list reconciliation failed (HTTP " + r.code + "): " + shortText(r.body) + ". Entry blocked because broker pending-order state is unknown.");
                JSONObject j = new JSONObject(r.body); JSONObject payload = j.optJSONObject("payload");
                JSONArray arr = payload == null ? null : payload.optJSONArray("order_list");
                if (arr == null || arr.length() == 0) break;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject x = arr.optJSONObject(i); if (x == null) continue;
                    if (!wanted.equalsIgnoreCase(x.optString("trading_symbol", ""))) continue;
                    if (!"CNC".equalsIgnoreCase(x.optString("product", ""))) continue;
                    if (!"BUY".equalsIgnoreCase(x.optString("transaction_type", ""))) continue;
                    int remaining = x.optInt("remaining_quantity", Math.max(0, x.optInt("quantity", 0) - x.optInt("filled_quantity", 0)));
                    String st = x.optString("order_status", "").toUpperCase(Locale.US);
                    boolean terminal = "COMPLETED".equals(st) || "EXECUTED".equals(st) || "CANCELLED".equals(st) || "REJECTED".equals(st) || "FAILED".equals(st);
                    if (!terminal && remaining > 0) {
                        return new Result(true, false, 200, "Active CNC BUY already exists • order " + x.optString("groww_order_id", "") + " • status " + st + ".");
                    }
                }
                if (arr.length() < 100) break;
            }
            return new Result(false, false, 200, "No active CNC BUY order found for " + wanted + ".");
        } catch (Exception e) {
            return new Result(false, true, 0, "Unable to reconcile active CNC BUY orders: " + safeMessage(e));
        }
    }
    /**
     * Clears regular pending/open CASH/CNC SELL orders for a symbol before an automated Univest market exit.
     * This prevents a user's forgotten manual target order from reserving the same delivery shares.
     * The method re-lists after cancellation and will not report success while a conflicting sell remains.
     */
    static Result cancelOpenCncSellOrdersForSymbol(Context context, String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return new Result(false, false, 0, "Missing symbol for sell-order reconciliation.");
        String wanted = symbol.trim().toUpperCase(Locale.US);
        try {
            java.util.ArrayList<String> ids = listOpenCncSellOrderIds(context, wanted);
            if (ids.isEmpty()) return new Result(true, false, 200, "No conflicting open CNC SELL orders found.");

            boolean sawUnknown = false;
            for (String id : ids) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("segment", "CASH");
                    body.put("groww_order_id", id);
                    HttpResponse r = post(ORDER_CANCEL_URL, ensureToken(context), body.toString(), true);
                    if (r.code == 401 || r.code == 403) {
                        AppPrefs.clearAccessToken(context);
                        Result auth = authenticate(context);
                        if (!auth.success) return auth;
                        r = post(ORDER_CANCEL_URL, AppPrefs.getAccessToken(context), body.toString(), true);
                    }
                    if (!(r.code >= 200 && r.code < 300)) {
                        // Do not immediately assume failure: the order may have completed/cancelled during the race.
                        // A final re-list below is authoritative for whether a conflict still exists.
                    }
                } catch (SocketTimeoutException e) {
                    sawUnknown = true;
                }
            }

            java.util.ArrayList<String> remaining = listOpenCncSellOrderIds(context, wanted);
            if (!remaining.isEmpty()) {
                return new Result(false, sawUnknown, 0,
                        "Conflicting CNC SELL order(s) still active after cancellation attempt: " + remaining.size() + ".");
            }
            return new Result(true, sawUnknown, 200,
                    "Cleared " + ids.size() + " conflicting open CNC SELL order(s) before automated exit.");
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "Sell-order reconciliation timed out; no automated market sell was sent.");
        } catch (Exception e) {
            return new Result(false, true, 0, "Sell-order reconciliation failed: " + safeMessage(e));
        }
    }

    private static java.util.ArrayList<String> listOpenCncSellOrderIds(Context context, String wantedSymbol) throws Exception {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int page = 0; page < 5; page++) {
            String endpoint = ORDER_LIST_URL + "?segment=CASH&page=" + page + "&page_size=100";
            HttpResponse r = get(endpoint, ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result auth = authenticate(context);
                if (!auth.success) throw new IllegalStateException(auth.message);
                r = get(endpoint, AppPrefs.getAccessToken(context), true);
            }
            if (!(r.code >= 200 && r.code < 300)) {
                throw new IllegalStateException("Order list HTTP " + r.code + ": " + shortText(r.body));
            }
            JSONObject root = new JSONObject(r.body);
            JSONObject payload = root.optJSONObject("payload");
            JSONArray list = payload == null ? null : payload.optJSONArray("order_list");
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                int remaining = o.optInt("remaining_quantity",
                        Math.max(0, o.optInt("quantity", 0) - o.optInt("filled_quantity", 0)));
                if (shouldCancelCncSellOrder(wantedSymbol,
                        o.optString("trading_symbol", ""),
                        o.optString("product", ""),
                        o.optString("transaction_type", ""),
                        o.optString("order_status", ""),
                        remaining)) {
                    String id = o.optString("groww_order_id", "");
                    if (!id.isEmpty() && !out.contains(id)) out.add(id);
                }
            }
            if (list.length() < 100) break;
        }
        return out;
    }

    static PositionSnapshot getCncPosition(Context context, String symbol) {
        int positionQty = 0;
        double positionPrice = 0.0;
        int holdingQty = 0;
        double holdingPrice = 0.0;
        boolean positionOk = false;
        boolean holdingsOk = false;
        String positionMsg = "";
        String holdingsMsg = "";

        try {
            String endpoint = POSITION_SYMBOL_URL + "?trading_symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8.name()) + "&segment=CASH";
            HttpResponse r = get(endpoint, ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context);
                if (a.success) r = get(endpoint, AppPrefs.getAccessToken(context), true);
            }
            if (r.code >= 200 && r.code < 300) {
                positionOk = true;
                JSONObject j = new JSONObject(r.body); JSONObject payload = j.optJSONObject("payload");
                JSONArray arr = payload == null ? null : payload.optJSONArray("positions");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject x = arr.optJSONObject(i); if (x == null) continue;
                    if (!symbol.equalsIgnoreCase(x.optString("trading_symbol", symbol))) continue;
                    if (!"CNC".equalsIgnoreCase(x.optString("product", "CNC"))) continue;
                    positionQty = Math.max(positionQty, Math.max(0, x.optInt("quantity", 0)));
                    positionPrice = x.optDouble("net_price", positionPrice);
                }
                positionMsg = "positions OK";
            } else positionMsg = "positions HTTP " + r.code;
        } catch (Exception e) { positionMsg = "positions " + safeMessage(e); }

        try {
            HttpResponse r = get(HOLDINGS_URL, ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context);
                if (a.success) r = get(HOLDINGS_URL, AppPrefs.getAccessToken(context), true);
            }
            if (r.code >= 200 && r.code < 300) {
                holdingsOk = true;
                JSONObject j = new JSONObject(r.body); JSONObject payload = j.optJSONObject("payload");
                JSONArray arr = payload == null ? null : payload.optJSONArray("holdings");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject x = arr.optJSONObject(i); if (x == null) continue;
                    if (!symbol.equalsIgnoreCase(x.optString("trading_symbol", ""))) continue;
                    int total = Math.max(0, x.optInt("quantity", 0));
                    int settledPlusT1 = Math.max(0, x.optInt("demat_free_quantity", 0)) + Math.max(0, x.optInt("t1_quantity", 0));
                    holdingQty = Math.max(total, settledPlusT1);
                    holdingPrice = x.optDouble("average_price", 0.0);
                    break;
                }
                holdingsMsg = "holdings OK";
            } else holdingsMsg = "holdings HTTP " + r.code;
        } catch (Exception e) { holdingsMsg = "holdings " + safeMessage(e); }

        int qty = Math.max(positionQty, holdingQty);
        double price = holdingQty >= positionQty && holdingPrice > 0 ? holdingPrice : positionPrice;
        boolean ok = positionOk || holdingsOk;
        String msg = "CNC reconciliation • position=" + positionQty + " • holding=" + holdingQty + " • " + positionMsg + " • " + holdingsMsg;
        DiagnosticsStore.broker(context, "CNC_RECONCILIATION", symbol, ok, msg);
        return new PositionSnapshot(ok, ok ? qty : -1, price, msg);
    }

    /** Multyfi Intraday notification -> immediate CNC DELIVERY MARKET BUY, then broker-hosted GTT. */
    static MultyfiEntryResult placeMultyfiDeliveryBuyWithHalfPercentNetGtt(Context context, String symbol,
                                                                           int quantity, int savedBudget,
                                                                           String referenceId) {
        OrderSubmit entry = submitMarketOrder(context, symbol, quantity, "CNC", "BUY", referenceId);
        if (!entry.success) return new MultyfiEntryResult(false, false, entry.unknown, entry.orderId, "", 0, 0, 0, 0, 0, entry.message);
        if (entry.orderId.isEmpty()) return new MultyfiEntryResult(true, false, false, "", "", 0, 0, 0, 0, 0,
                "CNC MARKET BUY accepted, but Groww returned no order ID. +0.5% NET GTT not created; verify manually.");
        try {
            Fill fill = awaitExecution(context, entry.orderId, quantity);
            if (fill.quantity < 1 || !(fill.averagePrice > 0)) {
                return new MultyfiEntryResult(true, false, false, entry.orderId, "", 0, 0, 0, 0, 0,
                        "CNC MARKET BUY accepted, but fill price was not confirmed in time. +0.5% NET GTT not created.");
            }
            double tick = 0.05;
            try {
                InstrumentRepository.Instrument i = InstrumentRepository.resolve(InstrumentRepository.load(context), symbol);
                if (i != null && i.tickSize > 0) tick = i.tickSize;
            } catch (Exception ignored) {}
            double desiredNet = Math.max(0.0, savedBudget * 0.005);
            double target = DeliveryNetTarget.targetPriceForNetProfit(fill.averagePrice, fill.quantity, tick, desiredNet);
            double breakEven = DeliveryNetTarget.breakEvenPrice(fill.averagePrice, fill.quantity, tick);
            GttResult gtt = createGttDetailed(context, symbol, fill.quantity, "CNC", "SELL", "UP", target,
                    makeReference("M05", symbol));
            if (!gtt.success) {
                return new MultyfiEntryResult(true, false, gtt.unknown, entry.orderId, "", fill.quantity,
                        fill.averagePrice, target, breakEven, tick,
                        "BUY EXECUTED • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                                + " • +0.5% NET-on-budget GTT ₹" + money(target) + " NOT confirmed: " + gtt.message);
            }
            double est = DeliveryNetTarget.estimatedNetProfit(fill.averagePrice, fill.quantity, target);
            return new MultyfiEntryResult(true, true, false, entry.orderId, gtt.smartOrderId, fill.quantity,
                    fill.averagePrice, target, breakEven, tick,
                    "BUY EXECUTED • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            + " • +0.5% NET-on-budget GTT SELL ₹" + money(target)
                            + " • estimated net ₹" + money(est) + ".");
        } catch (SocketTimeoutException e) {
            return new MultyfiEntryResult(true, false, true, entry.orderId, "", 0, 0, 0, 0, 0,
                    "BUY accepted, but fill/GTT confirmation timed out. Verify Orders/Smart Orders; no blind retry.");
        } catch (Exception e) {
            return new MultyfiEntryResult(true, false, true, entry.orderId, "", 0, 0, 0, 0, 0,
                    "BUY accepted, but +0.5% NET GTT status is uncertain: " + safeMessage(e));
        }
    }

    static ExecutionResult placeCncMarketSell(Context context, String symbol, int quantity, String referenceId) {
        return placeUnivestCncMarketSell(context, symbol, quantity, referenceId);
    }

    static Result modifyCashGttSellTarget(Context context, String smartOrderId, int quantity, double target) {
        if (smartOrderId == null || smartOrderId.isEmpty()) return new Result(false, false, 0, "No GTT id to modify.");
        try {
            JSONObject order = new JSONObject();
            order.put("order_type", "LIMIT"); order.put("price", money(target)); order.put("transaction_type", "SELL");
            JSONObject body = new JSONObject();
            body.put("smart_order_type", "GTT"); body.put("segment", "CASH"); body.put("quantity", quantity);
            body.put("trigger_price", money(target)); body.put("trigger_direction", "UP"); body.put("order", order);
            HttpResponse r = put(SMART_MODIFY_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return a;
                r = put(SMART_MODIFY_URL + URLEncoder.encode(smartOrderId, StandardCharsets.UTF_8.name()), AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) return new Result(true, false, r.code, "GTT modified to ₹" + money(target));
            return new Result(false, false, r.code, "GTT modify rejected HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "GTT modify status unknown after timeout; verify Smart Orders before any retry.");
        } catch (Exception e) { return new Result(false, true, 0, "GTT modify error: " + safeMessage(e)); }
    }

    /** Multyfi official close -> MIS MARKET short, protected by same-day CASH/MIS OCO. */
    static ShortOcoResult placeMultyfiMisShortWithOco(Context context, String symbol, int budget, double tickSize, String referenceId) {
        try {
            double ltp = getLtp(context, symbol);
            if (!(ltp > 0)) return new ShortOcoResult(false, false, false, 0, 0, 0, 0, "", "No valid LTP for short sizing.");
            int qty = (int)Math.floor(budget / ltp);
            if (qty < 1) return new ShortOcoResult(false, false, false, 0, 0, 0, 0, "", "Saved Multyfi budget cannot short one share at current LTP.");
            OrderSubmit shortEntry = submitMarketOrder(context, symbol, qty, "MIS", "SELL", referenceId);
            if (!shortEntry.success) return new ShortOcoResult(false, false, shortEntry.unknown, qty, 0, 0, 0, "", shortEntry.message);
            if (shortEntry.orderId.isEmpty()) return new ShortOcoResult(true, false, false, qty, 0, 0, 0, "", "MIS short accepted but no order ID returned; OCO not created.");
            Fill fill = awaitExecution(context, shortEntry.orderId, qty);
            if (fill.quantity < 1 || !(fill.averagePrice > 0)) return new ShortOcoResult(true, false, false, qty, 0, 0, 0, "", "MIS short accepted but fill not confirmed; OCO not created.");
            double tick = tickSize > 0 ? tickSize : 0.05;
            double target = IntradayChargeTarget.targetPriceForNetProfit(fill.averagePrice, fill.quantity, tick, budget * 0.005);
            double stop = IntradayChargeTarget.stopTriggerForMaxLoss(fill.averagePrice, fill.quantity, tick, budget * 0.01);
            GttResult oco = createCashMisShortOco(context, symbol, fill.quantity, target, stop, makeReference("MSO", symbol));
            return new ShortOcoResult(true, oco.success, oco.unknown, fill.quantity, fill.averagePrice, target, stop,
                    oco.smartOrderId,
                    "MIS SHORT executed • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            + " • OCO BUY target ₹" + money(target) + " (+0.5% NET-on-budget)"
                            + " • stop trigger ₹" + money(stop) + " (1% budget-loss guard) • " + oco.message);
        } catch (SocketTimeoutException e) {
            return new ShortOcoResult(false, false, true, 0, 0, 0, 0, "", "Short/OCO status unknown after timeout. Check Groww before retrying.");
        } catch (Exception e) { return new ShortOcoResult(false, false, true, 0, 0, 0, 0, "", "Short/OCO error: " + safeMessage(e)); }
    }

    private static GttResult createCashMisShortOco(Context context, String symbol, int qty, double target, double stop, String referenceId) {
        try {
            JSONObject targetLeg = new JSONObject();
            targetLeg.put("trigger_price", money(target)); targetLeg.put("order_type", "LIMIT"); targetLeg.put("price", money(target));
            JSONObject stopLeg = new JSONObject();
            stopLeg.put("trigger_price", money(stop)); stopLeg.put("order_type", "SL_M"); stopLeg.put("price", JSONObject.NULL);
            JSONObject body = new JSONObject();
            body.put("reference_id", referenceId); body.put("smart_order_type", "OCO"); body.put("segment", "CASH");
            body.put("trading_symbol", symbol); body.put("quantity", qty); body.put("net_position_quantity", -qty);
            body.put("transaction_type", "BUY"); body.put("target", targetLeg); body.put("stop_loss", stopLeg);
            body.put("product_type", "MIS"); body.put("exchange", "NSE"); body.put("duration", "DAY");
            HttpResponse r = post(GTT_URL, ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return new GttResult(false, false, "", a.message);
                r = post(GTT_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String id=""; JSONObject j=new JSONObject(r.body); JSONObject pl=j.optJSONObject("payload"); if (pl!=null) id=pl.optString("smart_order_id","");
                return new GttResult(true, false, id, "MIS OCO active" + (id.isEmpty()?"":" • "+id));
            }
            return new GttResult(false, false, "", "MIS OCO rejected HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) { return new GttResult(false, true, "", "MIS OCO status unknown after timeout; no blind retry."); }
        catch (Exception e) { return new GttResult(false, true, "", "MIS OCO error: " + safeMessage(e)); }
    }

    static ManualResult executeManualLong(Context context, InstrumentRepository.Instrument instrument, int budget) {
        return executeManualTargetTrade(context, instrument, budget, true);
    }

    static ManualResult executeManualShort(Context context, InstrumentRepository.Instrument instrument, int budget) {
        return executeManualTargetTrade(context, instrument, budget, false);
    }

    private static ManualResult executeManualTargetTrade(Context context, InstrumentRepository.Instrument instrument, int budget, boolean isLong) {
        if (instrument == null) return new ManualResult(false, false, false, "Select a valid NSE stock first.");
        if (budget <= 0) return new ManualResult(false, false, false, "Budget is ₹0. Move the slider and SAVE BUDGET first.");
        if (!AppPrefs.isReadyForBuy(context)) return new ManualResult(false, false, false, "Groww connection is not ready. Confirm static IP and refresh/test authentication first.");
        if (isLong && !instrument.buyAllowed) return new ManualResult(false, false, false, instrument.symbol + " is currently marked buy_allowed=0 by the Groww instrument master.");
        if (!isLong && !instrument.sellAllowed) return new ManualResult(false, false, false, instrument.symbol + " is currently marked sell_allowed=0 by the Groww instrument master.");

        try {
            double ltp = getLtp(context, instrument.symbol);
            if (!(ltp > 0.0)) return new ManualResult(false, false, false, "Could not obtain a valid LTP for " + instrument.symbol + ". No order submitted.");
            int quantity = (int) Math.floor(budget / ltp);
            if (quantity < 1) return new ManualResult(false, false, false, instrument.symbol + " LTP ₹" + money(ltp) + " is above the selected ₹" + inr(budget) + " budget.");

            String product = isLong ? "CNC" : "MIS";
            String transaction = isLong ? "BUY" : "SELL";
            String entryRef = makeReference(isLong ? "LG" : "SH", instrument.symbol);
            OrderSubmit entry = submitMarketOrder(context, instrument.symbol, quantity, product, transaction, entryRef);
            if (!entry.success) {
                return new ManualResult(false, false, entry.unknown, (isLong ? "LONG" : "SHORT") + " entry not confirmed: " + entry.message);
            }
            if (entry.orderId.isEmpty()) {
                return new ManualResult(true, false, false, "ENTRY ACCEPTED for " + instrument.symbol + " qty " + quantity + ", but Groww returned no order ID. 1% target was NOT created; verify manually.");
            }

            Fill fill = awaitExecution(context, entry.orderId, quantity);
            if (!fill.confirmed || fill.quantity < 1 || !(fill.averagePrice > 0)) {
                return new ManualResult(true, false, false,
                        "ENTRY ACCEPTED • " + instrument.symbol + " • " + transaction + " " + quantity + " • order " + entry.orderId
                                + ". Execution price was not confirmed in time, so no target was created. Verify Groww manually.");
            }

            double rawTarget = isLong ? fill.averagePrice * 1.01 : fill.averagePrice * 0.99;
            double target = roundTarget(rawTarget, instrument.tickSize, isLong);
            Result targetResult;
            if (isLong) {
                targetResult = createGttTarget(context, instrument.symbol, fill.quantity, "CNC",
                        "SELL", "UP", target, makeReference("LGT", instrument.symbol));
            } else {
                // A short is intraday. Keep its cover as a DAY LIMIT BUY rather than a persistent GTT,
                // so an unfilled target cannot survive the MIS position into a later session.
                targetResult = submitShortCoverLimit(context, instrument.symbol, fill.quantity, target,
                        makeReference("SHT", instrument.symbol));
            }
            if (!targetResult.success) {
                return new ManualResult(true, false, targetResult.unknown,
                        "ENTRY EXECUTED • " + instrument.symbol + " • avg ₹" + money(fill.averagePrice)
                                + " • qty " + fill.quantity + ". 1% target ₹" + money(target) + " was NOT created: " + targetResult.message);
            }

            String side = isLong ? "LONG CNC BUY" : "SHORT MIS SELL";
            String exit = isLong ? "GTT SELL" : "DAY LIMIT BUY-to-cover";
            return new ManualResult(true, true, false,
                    side + " EXECUTED • " + instrument.symbol + " • qty " + fill.quantity + " • avg ₹" + money(fill.averagePrice)
                            + " • " + exit + " target ₹" + money(target) + " (1%).");
        } catch (SocketTimeoutException e) {
            return new ManualResult(false, false, true, "Network timeout before entry status was safely established. Check Groww before retrying to avoid a duplicate trade.");
        } catch (Exception e) {
            return new ManualResult(false, false, true, "Manual trade status uncertain after network/API error: " + safeMessage(e) + ". Check Groww before retrying.");
        }
    }

    private static double getLtp(Context context, String symbol) throws Exception {
        String token = ensureToken(context);
        String key = "NSE_" + symbol;
        String endpoint = LTP_URL + "?segment=CASH&exchange_symbols=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        HttpResponse r = get(endpoint, token, true);
        if (r.code == 401 || r.code == 403) {
            AppPrefs.clearAccessToken(context);
            Result a = authenticate(context);
            if (!a.success) throw new IllegalStateException(a.message);
            r = get(endpoint, AppPrefs.getAccessToken(context), true);
        }
        if (r.code < 200 || r.code >= 300) throw new IllegalStateException("LTP HTTP " + r.code + ": " + shortText(r.body));
        JSONObject json = new JSONObject(r.body);
        JSONObject payload = json.optJSONObject("payload");
        if (payload == null) return -1;
        return payload.optDouble(key, -1);
    }

    static CircuitOrderPlan circuitOrderPlan(QuoteSnapshot q, String transaction, double tickSize) {
        String side = transaction == null ? "" : transaction.trim().toUpperCase(Locale.US);
        if (q == null || !q.success || !(q.lastPrice > 0)) {
            return new CircuitOrderPlan(false, q == null ? 0 : q.lastPrice, 0,
                    q == null ? 0 : q.lowerCircuit, q == null ? 0 : q.upperCircuit,
                    "Quote unavailable.");
        }
        double tick = tickSize > 0 ? tickSize : 0.05;
        if ("BUY".equals(side) && q.upperCircuit > 0) {
            double reference = Math.max(q.lastPrice, q.offerPrice > 0 ? q.offerPrice : q.lastPrice);
            double aggressive = reference * (1.0 + OFFICIAL_MARKETABLE_LIMIT_BUFFER_PCT);
            double capped = Math.min(q.upperCircuit, aggressive);
            double limit = roundDownToTick(capped, tick);
            if (limit <= 0) return new CircuitOrderPlan(false, reference, 0, q.lowerCircuit, q.upperCircuit, "Invalid upper-circuit plan.");
            return new CircuitOrderPlan(true, reference, limit, q.lowerCircuit, q.upperCircuit, "Circuit-safe BUY limit.");
        }
        if ("SELL".equals(side) && q.lowerCircuit > 0) {
            double reference = Math.min(q.lastPrice, q.bidPrice > 0 ? q.bidPrice : q.lastPrice);
            double aggressive = reference * (1.0 - OFFICIAL_MARKETABLE_LIMIT_BUFFER_PCT);
            double floored = Math.max(q.lowerCircuit, aggressive);
            double limit = roundUpToTick(floored, tick);
            if (limit <= 0) return new CircuitOrderPlan(false, reference, 0, q.lowerCircuit, q.upperCircuit, "Invalid lower-circuit plan.");
            return new CircuitOrderPlan(true, reference, limit, q.lowerCircuit, q.upperCircuit, "Circuit-safe SELL limit.");
        }
        return new CircuitOrderPlan(false, q.lastPrice, 0, q.lowerCircuit, q.upperCircuit,
                "Circuit limits missing from live quote.");
    }

    private static double tickSizeFor(Context context, String symbol) {
        try {
            InstrumentRepository.Instrument i = InstrumentRepository.resolve(InstrumentRepository.load(context), symbol);
            if (i != null && i.tickSize > 0) return i.tickSize;
        } catch (Throwable ignored) {}
        return 0.05;
    }

    private static double roundDownToTick(double price, double tick) {
        double t = tick > 0 ? tick : 0.05;
        return Math.max(t, Math.floor((price + 1e-9) / t) * t);
    }

    private static double roundUpToTick(double price, double tick) {
        double t = tick > 0 ? tick : 0.05;
        return Math.max(t, Math.ceil((price - 1e-9) / t) * t);
    }

    static boolean isTerminalFailureStatus(String status) {
        if (status == null) return false;
        String s = status.toUpperCase(Locale.US);
        return s.contains("REJECT") || s.contains("FAILED") || s.contains("CANCELLED");
    }

    private static OrderSubmit submitLimitOrder(Context context, String symbol, int quantity, String product,
                                                String transaction, double limitPrice, String referenceId) {
        if (quantity <= 0) return new OrderSubmit(false, false, 0, "", "Quantity is zero; order not submitted.");
        if (!(limitPrice > 0)) return new OrderSubmit(false, false, 0, "", "Limit price is invalid; order not submitted.");
        try {
            String token = ensureToken(context);
            JSONObject body = new JSONObject();
            body.put("trading_symbol", symbol);
            body.put("quantity", quantity);
            body.put("validity", "DAY");
            body.put("exchange", "NSE");
            body.put("segment", "CASH");
            body.put("product", product);
            body.put("order_type", "LIMIT");
            body.put("price", money(limitPrice));
            body.put("transaction_type", transaction);
            body.put("order_reference_id", referenceId);

            HttpResponse r = post(ORDER_URL, token, body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result auth = authenticate(context);
                if (!auth.success) return new OrderSubmit(false, false, auth.httpCode, "", auth.message);
                r = post(ORDER_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String orderId = "";
                String remark = "Limit order accepted";
                try {
                    JSONObject json = new JSONObject(r.body);
                    JSONObject payload = json.optJSONObject("payload");
                    if (payload != null) {
                        orderId = payload.optString("groww_order_id", "");
                        remark = payload.optString("remark", remark);
                    }
                } catch (Exception ignored) {}
                String msg = remark + " • LIMIT ₹" + money(limitPrice) + (orderId.isEmpty() ? "" : " • " + orderId);
                DiagnosticsStore.broker(context, "ORDER_" + transaction + "_" + product + "_CIRCUIT_LIMIT", symbol, true, msg);
                return new OrderSubmit(true, false, r.code, orderId, msg);
            }
            String reject = transaction + " circuit-safe LIMIT rejected (HTTP " + r.code + "): " + shortText(r.body);
            DiagnosticsStore.broker(context, "ORDER_" + transaction + "_" + product + "_CIRCUIT_LIMIT", symbol, false, reject);
            return new OrderSubmit(false, false, r.code, "", reject);
        } catch (SocketTimeoutException e) {
            return new OrderSubmit(false, true, 0, "",
                    transaction + " circuit-safe LIMIT status unknown after timeout. No automatic retry was made.");
        } catch (Exception e) {
            return new OrderSubmit(false, true, 0, "",
                    transaction + " circuit-safe LIMIT status unknown after network error: " + safeMessage(e) + ".");
        }
    }

    private static OrderSubmit submitMarketOrder(Context context, String symbol, int quantity, String product, String transaction, String referenceId) {
        if (quantity <= 0) return new OrderSubmit(false, false, 0, "", "Quantity is zero; order not submitted.");
        try {
            String token = ensureToken(context);
            JSONObject body = new JSONObject();
            body.put("trading_symbol", symbol);
            body.put("quantity", quantity);
            body.put("validity", "DAY");
            body.put("exchange", "NSE");
            body.put("segment", "CASH");
            body.put("product", product);
            body.put("order_type", "MARKET");
            body.put("transaction_type", transaction);
            body.put("order_reference_id", referenceId);

            HttpResponse r = post(ORDER_URL, token, body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result auth = authenticate(context);
                if (!auth.success) return new OrderSubmit(false, false, auth.httpCode, "", auth.message);
                // Retry only after an explicit authentication rejection; the rejected request was not accepted as an order.
                r = post(ORDER_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String orderId = "";
                String remark = "Order accepted";
                try {
                    JSONObject json = new JSONObject(r.body);
                    JSONObject payload = json.optJSONObject("payload");
                    if (payload != null) {
                        orderId = payload.optString("groww_order_id", "");
                        remark = payload.optString("remark", remark);
                    }
                } catch (Exception ignored) {}
                String msg = remark + (orderId.isEmpty() ? "" : " • " + orderId);
                DiagnosticsStore.broker(context, "ORDER_" + transaction + "_" + product, symbol, true, msg);
                return new OrderSubmit(true, false, r.code, orderId, msg);
            }
            String reject = transaction + " rejected (HTTP " + r.code + "): " + shortText(r.body);
            DiagnosticsStore.broker(context, "ORDER_" + transaction + "_" + product, symbol, false, reject);
            return new OrderSubmit(false, false, r.code, "", reject);
        } catch (SocketTimeoutException e) {
            return new OrderSubmit(false, true, 0, "", transaction + " status unknown after network timeout. No automatic retry was made to avoid a duplicate order.");
        } catch (Exception e) {
            return new OrderSubmit(false, true, 0, "", transaction + " status unknown after network error: " + safeMessage(e) + ". No automatic retry was made.");
        }
    }

    private static String detailedOrderStatus(JSONObject p) {
        if (p == null) return "UNKNOWN";
        String status = p.optString("order_status", "");
        String reason = p.optString("rejection_reason", "");
        if (reason.isEmpty()) reason = p.optString("remark", "");
        if (reason.isEmpty()) reason = p.optString("status_message", "");
        if (reason.isEmpty()) reason = p.optString("message", "");
        if (reason.isEmpty()) return status;
        return status + " • " + reason;
    }

    private static Fill getExecutionOnce(Context context, String orderId) throws Exception {
        String endpoint = ORDER_DETAIL_URL + URLEncoder.encode(orderId, StandardCharsets.UTF_8.name()) + "?segment=CASH";
        HttpResponse r = get(endpoint, ensureToken(context), true);
        if (r.code == 401 || r.code == 403) {
            AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return new Fill(false, 0, 0, "AUTH_FAILED");
            r = get(endpoint, AppPrefs.getAccessToken(context), true);
        }
        if (r.code >= 200 && r.code < 300) {
            JSONObject json = new JSONObject(r.body); JSONObject p = json.optJSONObject("payload");
            if (p != null) {
                int filled = p.optInt("filled_quantity", 0); double avg = p.optDouble("average_fill_price", 0);
                String status = detailedOrderStatus(p);
                return new Fill(filled > 0 && avg > 0, filled, avg, status);
            }
        }
        return new Fill(false, 0, 0, "HTTP_" + r.code);
    }

    private static Fill awaitExecution(Context context, String orderId, int requestedQty) throws Exception {
        long deadline = System.currentTimeMillis() + 8000L;
        Fill best = new Fill(false, 0, 0, "UNKNOWN");
        while (System.currentTimeMillis() < deadline) {
            String endpoint = ORDER_DETAIL_URL + URLEncoder.encode(orderId, StandardCharsets.UTF_8.name()) + "?segment=CASH";
            HttpResponse r = get(endpoint, ensureToken(context), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result a = authenticate(context);
                if (!a.success) return best;
                continue;
            }
            if (r.code >= 200 && r.code < 300) {
                JSONObject json = new JSONObject(r.body);
                JSONObject p = json.optJSONObject("payload");
                if (p != null) {
                    int filled = p.optInt("filled_quantity", 0);
                    double avg = p.optDouble("average_fill_price", 0);
                    String status = detailedOrderStatus(p);
                    if (filled > 0 && avg > 0) best = new Fill(filled >= requestedQty, filled, avg, status);
                    if (filled >= requestedQty && avg > 0) return new Fill(true, filled, avg, status);
                    if (isTerminalFailureStatus(status)) return new Fill(false, filled, avg, status);
                }
            }
            Thread.sleep(175L);
        }
        return best;
    }

    private static Result submitShortCoverLimit(Context context, String symbol, int quantity, double target, String referenceId) {
        try {
            JSONObject body = new JSONObject();
            body.put("trading_symbol", symbol);
            body.put("quantity", quantity);
            body.put("validity", "DAY");
            body.put("exchange", "NSE");
            body.put("segment", "CASH");
            body.put("product", "MIS");
            body.put("order_type", "LIMIT");
            body.put("price", money(target));
            body.put("transaction_type", "BUY");
            body.put("order_reference_id", referenceId);

            HttpResponse r = post(ORDER_URL, ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result a = authenticate(context);
                if (!a.success) return a;
                r = post(ORDER_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String id = "";
                try {
                    JSONObject json = new JSONObject(r.body);
                    JSONObject payload = json.optJSONObject("payload");
                    if (payload != null) id = payload.optString("groww_order_id", "");
                } catch (Exception ignored) {}
                return new Result(true, false, r.code, "DAY LIMIT BUY-to-cover target active" + (id.isEmpty() ? "" : " • " + id));
            }
            return new Result(false, false, r.code, "Short cover target rejected (HTTP " + r.code + "): " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "Short cover target status unknown after timeout. Verify Orders in Groww; no automatic retry was made.");
        } catch (Exception e) {
            return new Result(false, true, 0, "Short cover target status unknown after network error: " + safeMessage(e) + ". Verify Groww before retrying.");
        }
    }

    private static GttResult createGttMarketDetailed(Context context, String symbol, int quantity, String product,
                                                     String transaction, String direction, double trigger, String referenceId) {
        try {
            JSONObject order = new JSONObject();
            order.put("order_type", "MARKET");
            order.put("price", JSONObject.NULL);
            order.put("transaction_type", transaction);
            JSONObject body = new JSONObject();
            body.put("reference_id", referenceId);
            body.put("smart_order_type", "GTT");
            body.put("segment", "CASH");
            body.put("trading_symbol", symbol);
            body.put("quantity", quantity);
            body.put("trigger_price", money(trigger));
            body.put("trigger_direction", direction);
            body.put("order", order);
            body.put("product_type", product);
            body.put("exchange", "NSE");
            body.put("duration", "DAY");
            HttpResponse r = post(GTT_URL, ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return new GttResult(false, false, "", a.message);
                r = post(GTT_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String id = ""; JSONObject jo = new JSONObject(r.body); JSONObject pl = jo.optJSONObject("payload");
                if (pl != null) id = pl.optString("smart_order_id", "");
                return new GttResult(true, false, id, "MARKET GTT active" + (id.isEmpty() ? "" : " • " + id));
            }
            return new GttResult(false, false, "", "MARKET GTT rejected HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new GttResult(false, true, "", "MARKET GTT status unknown after timeout. No blind retry.");
        } catch (Exception e) { return new GttResult(false, true, "", "MARKET GTT error: " + safeMessage(e)); }
    }

    private static GttResult createGttDetailed(Context context, String symbol, int quantity, String product,
                                               String transaction, String direction, double target, String referenceId) {
        try {
            JSONObject order = new JSONObject();
            order.put("order_type", "LIMIT"); order.put("price", money(target)); order.put("transaction_type", transaction);
            JSONObject body = new JSONObject();
            body.put("reference_id", referenceId); body.put("smart_order_type", "GTT"); body.put("segment", "CASH");
            body.put("trading_symbol", symbol); body.put("quantity", quantity); body.put("trigger_price", money(target));
            body.put("trigger_direction", direction); body.put("order", order); body.put("product_type", product);
            body.put("exchange", "NSE"); body.put("duration", "DAY");
            HttpResponse r = post(GTT_URL, ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context); Result a = authenticate(context); if (!a.success) return new GttResult(false, false, "", a.message);
                r = post(GTT_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String id = ""; JSONObject j = new JSONObject(r.body); JSONObject p = j.optJSONObject("payload");
                if (p != null) id = p.optString("smart_order_id", "");
                return new GttResult(true, false, id, "GTT active" + (id.isEmpty() ? "" : " • " + id));
            }
            return new GttResult(false, false, "", "GTT rejected HTTP " + r.code + ": " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new GttResult(false, true, "", "GTT status unknown after timeout. No blind retry.");
        } catch (Exception e) { return new GttResult(false, true, "", "GTT error: " + safeMessage(e)); }
    }

    private static Result createGttTarget(Context context, String symbol, int quantity, String product,
                                          String transaction, String direction, double target, String referenceId) {
        try {
            JSONObject order = new JSONObject();
            order.put("order_type", "LIMIT");
            order.put("price", money(target));
            order.put("transaction_type", transaction);

            JSONObject body = new JSONObject();
            body.put("reference_id", referenceId);
            body.put("smart_order_type", "GTT");
            body.put("segment", "CASH");
            body.put("trading_symbol", symbol);
            body.put("quantity", quantity);
            body.put("trigger_price", money(target));
            body.put("trigger_direction", direction);
            body.put("order", order);
            body.put("product_type", product);
            body.put("exchange", "NSE");
            body.put("duration", "DAY");

            HttpResponse r = post(GTT_URL, ensureToken(context), body.toString(), true);
            if (r.code == 401 || r.code == 403) {
                AppPrefs.clearAccessToken(context);
                Result a = authenticate(context);
                if (!a.success) return a;
                r = post(GTT_URL, AppPrefs.getAccessToken(context), body.toString(), true);
            }
            if (r.code >= 200 && r.code < 300) {
                String id = "";
                try {
                    JSONObject json = new JSONObject(r.body);
                    JSONObject p = json.optJSONObject("payload");
                    if (p != null) id = p.optString("smart_order_id", "");
                } catch (Exception ignored) {}
                return new Result(true, false, r.code, "GTT target active" + (id.isEmpty() ? "" : " • " + id));
            }
            return new Result(false, false, r.code, "GTT rejected (HTTP " + r.code + "): " + shortText(r.body));
        } catch (SocketTimeoutException e) {
            return new Result(false, true, 0, "GTT status unknown after timeout. Verify Smart Orders in Groww; no automatic retry was made.");
        } catch (Exception e) {
            return new Result(false, true, 0, "GTT status unknown after network error: " + safeMessage(e) + ". Verify Groww before retrying.");
        }
    }

    static long parseEpochSeconds(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 0L;
        if (raw instanceof Number) {
            long v = ((Number) raw).longValue();
            return v > 10_000_000_000L ? v / 1000L : v;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return 0L;
        try {
            long v = Long.parseLong(text);
            return v > 10_000_000_000L ? v / 1000L : v;
        } catch (NumberFormatException ignored) {}
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                f.setLenient(false);
                if (!pattern.contains("XXX")) f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
                Date d = f.parse(text);
                if (d != null) return d.getTime() / 1000L;
            } catch (ParseException ignored) {}
        }
        return 0L;
    }

    private static String ensureToken(Context context) throws Exception {
        String token = AppPrefs.getAccessToken(context);
        if (!token.isEmpty()) return token;
        Result a = authenticate(context);
        if (!a.success) throw new IllegalStateException(a.message);
        return AppPrefs.getAccessToken(context);
    }

    static double roundTarget(double raw, double tick, boolean isLong) {
        double t = tick > 0 ? tick : 0.05;
        double units = raw / t;
        double rounded = (isLong ? Math.ceil(units - 1e-9) : Math.floor(units + 1e-9)) * t;
        return Math.max(t, Math.round(rounded * 10000.0) / 10000.0);
    }

    private static String makeReference(String prefix, String symbol) {
        String base = prefix + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.US)
                + symbol.replaceAll("[^A-Z0-9]", "");
        if (base.length() > 20) base = base.substring(0, 20);
        while (base.length() < 8) base += "0";
        return base;
    }

    private static final class HttpResponse {
        final int code;
        final String body;
        HttpResponse(int code, String body) { this.code = code; this.body = body; }
    }

    private static HttpResponse get(String endpoint, String bearer, boolean apiVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (apiVersion) c.setRequestProperty("X-API-VERSION", "1.0");
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();
        return new HttpResponse(code, body);
    }

    private static HttpResponse post(String endpoint, String bearer, String json, boolean apiVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (apiVersion) c.setRequestProperty("X-API-VERSION", "1.0");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();
        return new HttpResponse(code, body);
    }

    private static HttpResponse put(String endpoint, String bearer, String json, boolean apiVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("PUT"); c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + bearer); if (apiVersion) c.setRequestProperty("X-API-VERSION", "1.0");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream); c.disconnect(); return new HttpResponse(code, body);
    }

    private static HttpResponse postEmpty(String endpoint, String bearer, boolean apiVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (apiVersion) c.setRequestProperty("X-API-VERSION", "1.0");
        c.setFixedLengthStreamingMode(0);
        try (OutputStream os = c.getOutputStream()) { os.flush(); }
        int code = c.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream); c.disconnect(); return new HttpResponse(code, body);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String shortText(String s) {
        if (s == null || s.isEmpty()) return "no response body";
        String clean = s.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 220 ? clean : clean.substring(0, 220) + "…";
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String money(double v) { return String.format(Locale.US, "%.2f", v); }
    private static String inr(int v) { return String.format(Locale.US, "%,d", v); }


    static boolean isValidBase32(String input) {
        if (input == null) return false;
        String s = input.toUpperCase(Locale.US).replace(" ", "").replace("-", "").replace("=", "");
        if (s.length() < 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= '2' && ch <= '7'))) return false;
        }
        return true;
    }

    static String generateTotp(String base32Secret, long nowMillis) throws GeneralSecurityException {
        byte[] key = decodeBase32(base32Secret);
        long counter = (nowMillis / 1000L) / 30L;
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] h = mac.doFinal(data);
        int offset = h[h.length - 1] & 0x0F;
        int binary = ((h[offset] & 0x7F) << 24)
                | ((h[offset + 1] & 0xFF) << 16)
                | ((h[offset + 2] & 0xFF) << 8)
                | (h[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format(Locale.US, "%06d", otp);
    }

    private static byte[] decodeBase32(String input) {
        String s = input.toUpperCase(Locale.US).replace(" ", "").replace("-", "").replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int val;
            if (ch >= 'A' && ch <= 'Z') val = ch - 'A';
            else if (ch >= '2' && ch <= '7') val = ch - '2' + 26;
            else continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
