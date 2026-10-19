package com.suhas.multyfideliverybuy;

import java.util.Locale;

/** Reliability only: Formula selection weights and trading rules are unchanged. */
final class FixedFormulaNetworkBackoff {
    static final long MINUTE = 60_000L;
    private FixedFormulaNetworkBackoff() {}

    static String category(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable e = error; e != null && text.length() < 1000; e = e.getCause()) {
            text.append(e.getClass().getSimpleName()).append(" ")
                    .append(e.getMessage()).append(" ");
        }
        String msg = text.toString().toLowerCase(Locale.US);
        if (msg.contains("429") || msg.contains("rate limit")) return "RATE_LIMIT";
        if (msg.contains("unknownhost") || msg.contains("unable to resolve host")
                || msg.contains("no address associated with hostname"))
            return "DNS_UNAVAILABLE";
        if (msg.contains("sockettimeout") || msg.contains("timed out")
                || msg.contains("connectexception") || msg.contains("connection refused"))
            return "NETWORK_UNAVAILABLE";
        return "";
    }

    static long cooldownMillis(String category, int successiveOutages) {
        if (category == null || category.isEmpty()) return 0;
        long base = "RATE_LIMIT".equals(category) ? 10 * MINUTE : 5 * MINUTE;
        int multiplier = 1 << Math.min(3, Math.max(0, successiveOutages - 1));
        return Math.min(45 * MINUTE, base * multiplier);
    }
}
