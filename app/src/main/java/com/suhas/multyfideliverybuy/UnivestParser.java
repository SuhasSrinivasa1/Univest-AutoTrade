package com.suhas.multyfideliverybuy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnivestParser {
    enum Type { ENTRY, REENTRY, EXIT }

    static final class Signal {
        final Type type;
        final String symbol;
        final String rawText;
        Signal(Type type, String symbol, String rawText) {
            this.type = type;
            this.symbol = symbol == null ? "" : symbol.trim();
            this.rawText = rawText == null ? "" : rawText;
        }
    }

    private static final Pattern[] STOCK_PATTERNS = new Pattern[] {
            Pattern.compile("(?im)^\\s*(?:stock(?:\\s+name)?|symbol|scrip|script|company)\\s*[:\\-]\\s*([^\\n\\r•|]{2,80})"),
            Pattern.compile("(?im)\\b(?:stock(?:\\s+name)?|symbol|scrip|script)\\s*[:\\-]\\s*([A-Z][A-Z0-9&.\\-]{1,30})\\b")
    };
    private static final Pattern DURATION_MONTHS = Pattern.compile(
            "(?im)\\b(?:duration|holding(?:\\s+period)?|time\\s*horizon)\\s*[:\\-]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:(?:-|–|—|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*months?\\b");

    private UnivestParser() {}

    static Signal parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.US);

        // Strictly equity-delivery ideas only. Univest also publishes derivatives/commodity content.
        if (containsAny(lower,
                "option pick", "options", "future pick", "futures", "commodity", "commodities",
                "mcx", "currency", "forex", "f&o", "fno", "call option", "put option",
                "nifty", "sensex", "bank nifty", "index update", "market outlook",
                "webinar", "offer", "discount", "subscribe", "promotion", "promotional")) {
            return null;
        }

        String stock = extractStock(text);
        if (stock.isEmpty()) return null;

        boolean exit = containsAny(lower,
                "equity profit of the day", "book profit for the day", "book profits for the day",
                "equity profit booked", "profit booked", "book profit", "book profits",
                "close position", "close stock", "exit stock", "exit recommendation",
                "recommendation closed", "advisory closed", "sell recommendation", "exit now");
        if (exit) return new Signal(Type.EXIT, stock, text);

        boolean backInRange = containsAny(lower,
                "back in entry range", "back in buying range", "back in buy range",
                "equity back in buying range", "equity back in buy range",
                "ideal buy price back", "back at ideal buy price", "back in ideal buy range",
                "stock back in ideal range", "stock back in ideal buy range",
                "back in ideal range", "back to ideal range", "budget back");
        if (backInRange) return new Signal(Type.REENTRY, stock, text);

        boolean entry = containsAny(lower,
                "new advisory pick", "new equity pick", "new stock pick", "equity pick",
                "stock recommendation", "new recommendation", "equity recommendation",
                "new investment idea", "new stock recommendation");
        if (entry) {
            if (!isEligibleEntryDuration(text)) return null;
            return new Signal(Type.ENTRY, stock, text);
        }

        return null;
    }

    private static String extractStock(String text) {
        for (Pattern p : STOCK_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) return cleanStock(m.group(1));
        }
        // Fallback for compact notifications whose title is just "SYMBOL Update".
        String[] lines = text.split("[\\r\\n]+");
        for (String line : lines) {
            String s = line == null ? "" : line.trim();
            Matcher m = Pattern.compile("^([A-Z][A-Z0-9&.\\-]{1,24})\\s+(?:update|recommendation|alert)\\b", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) return cleanStock(m.group(1));
        }
        return "";
    }

    private static String cleanStock(String input) {
        String s = input == null ? "" : input.trim();
        s = s.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9&.()'\\- ]+$", "").trim();
        s = s.replaceAll("(?i)\\s+(?:duration|target|potential|entry|buy|sell)\\b.*$", "").trim();
        if (s.toUpperCase(Locale.US).endsWith("-BE") && s.length() > 3) s = s.substring(0, s.length() - 3);
        return s;
    }

    static boolean isEligibleEntryDuration(String text) {
        Matcher duration = DURATION_MONTHS.matcher(text == null ? "" : text);
        if (!duration.find()) return false;
        try {
            double start = Double.parseDouble(duration.group(1));
            double end = duration.group(2) == null ? start : Double.parseDouble(duration.group(2));
            return start > 0.0 && end >= start && end <= 3.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }
}
