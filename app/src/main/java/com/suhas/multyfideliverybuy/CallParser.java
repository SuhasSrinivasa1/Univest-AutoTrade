package com.suhas.multyfideliverybuy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CallParser {
    enum Category { INTRADAY, SWING, MULTIBAGGER }

    static final class Call {
        final Category category;
        final String symbol;
        final double referencePrice;
        final String rawText;

        Call(Category category, String symbol, double referencePrice, String rawText) {
            this.category = category;
            this.symbol = symbol;
            this.referencePrice = referencePrice;
            this.rawText = rawText;
        }
    }

    private static final Pattern SYMBOL = Pattern.compile(
            "(?i)(?:Stock\\s*Name|Trading\\s*Symbol|Symbol|Stock)\\s*[:\\-]\\s*([A-Z0-9&._-]{2,24})");

    private static final Pattern RANGE = Pattern.compile(
            "(?i)(?:Entry\\s*Range|Buy\\s*Range|Entry\\s*Price\\s*Range|Buy\\s*Price\\s*Range)\\s*[:\\-]?\\s*₹?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:-|–|—|to)\\s*₹?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    private static final Pattern SINGLE_PRICE = Pattern.compile(
            "(?i)(?:Entry\\s*Price|Entry|Buy\\s*Price|Buy\\s*At|CMP|Recommended\\s*Price)\\s*[:\\-]\\s*₹?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    private CallParser() {}

    static Call parse(String combinedText) {
        if (combinedText == null) return null;
        String text = combinedText.trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.US);

        // A new BUY is only allowed from an explicit newly released call.
        // This deliberately ignores later update/exit/profit/stop notifications.
        if (!(lower.contains("released") || lower.contains("new trade call") || lower.contains("new call released"))) {
            return null;
        }

        Category category;
        if (lower.contains("multi bagger") || lower.contains("multibagger")) {
            category = Category.MULTIBAGGER;
        } else if (lower.contains("swing")) {
            category = Category.SWING;
        } else if (lower.contains("intraday")) {
            category = Category.INTRADAY;
        } else {
            return null;
        }

        Matcher sm = SYMBOL.matcher(text);
        if (!sm.find()) return null;
        String symbol = sm.group(1).toUpperCase(Locale.US).replaceAll("[^A-Z0-9&._-]", "");
        if (symbol.length() < 2) return null;

        double price = parseReferencePrice(text);
        if (!(price > 0.0)) return null;
        return new Call(category, symbol, price, text);
    }

    private static double parseReferencePrice(String text) {
        Matcher rm = RANGE.matcher(text);
        if (rm.find()) {
            double a = toDouble(rm.group(1));
            double b = toDouble(rm.group(2));
            if (a > 0 && b > 0) return Math.max(a, b);
        }
        Matcher pm = SINGLE_PRICE.matcher(text);
        if (pm.find()) return toDouble(pm.group(1));
        return -1.0;
    }

    private static double toDouble(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (Exception e) { return -1.0; }
    }
}
