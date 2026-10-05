package com.suhas.multyfideliverybuy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses authoritative Multyfi close/end notifications; it never parses new-entry notifications. */
final class MultyfiExitParser {
    static final class Signal {
        final String symbol;
        final String rawText;
        Signal(String symbol, String rawText) { this.symbol = symbol; this.rawText = rawText; }
    }

    private static final Pattern SYMBOL = Pattern.compile(
            "(?i)(?:Stock\\s*Name|Trading\\s*Symbol|Symbol|Stock)\\s*[:\\-]\\s*([A-Z0-9&._-]{2,24})");

    private MultyfiExitParser() {}

    static Signal parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("released") || lower.contains("new trade call") || lower.contains("new call released")) return null;
        if (!containsAny(lower,
                "closing early", "close early", "closed early", "book profit", "book profits",
                "profit booked", "protect profit", "exit all", "exit trade", "exit stock",
                "close trade", "close position", "close stock", "trade closed", "call closed",
                "strategy closed", "end call", "trade ended", "closing the trade", "sell now", "sell immediately", "sell call", "sell stock")) return null;
        Matcher m = SYMBOL.matcher(text);
        if (!m.find()) return null;
        String symbol = m.group(1).toUpperCase(Locale.US).replaceAll("[^A-Z0-9&._-]", "");
        return symbol.length() < 2 ? null : new Signal(symbol, text);
    }

    private static boolean containsAny(String text, String... xs) {
        for (String x : xs) if (text.contains(x)) return true;
        return false;
    }
}
