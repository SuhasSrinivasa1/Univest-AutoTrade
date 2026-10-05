package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class NetworkCheck {
    private static final String IPIFY_URL = "https://api4.ipify.org?format=json";
    private static final String AWS_URL = "https://checkip.amazonaws.com";

    static final class Result {
        final boolean detected;
        final boolean match;
        final String detectedIp;
        final String expectedIp;
        final String message;

        Result(boolean detected, boolean match, String detectedIp, String expectedIp, String message) {
            this.detected = detected;
            this.match = match;
            this.detectedIp = detectedIp;
            this.expectedIp = expectedIp;
            this.message = message;
        }
    }

    private NetworkCheck() {}

    static Result detectAndCompare(Context context) {
        String expected = normalizeIp(AppPrefs.getExpectedStaticIp(context));
        try {
            String detected = normalizeIp(fetchIp());
            if (!looksLikeIp(detected)) {
                AppPrefs.setStaticIpCheck(context, detected, false);
                return new Result(false, false, detected, expected, "Public IP detection returned an invalid address.");
            }
            boolean match = !expected.isEmpty() && detected.equalsIgnoreCase(expected);
            AppPrefs.setStaticIpCheck(context, detected, match);
            if (expected.isEmpty()) {
                return new Result(true, false, detected, expected,
                        "Detected public IP " + detected + ". Enter the Groww-whitelisted static IP, save, then test again.");
            }
            if (!looksLikeIp(expected)) {
                return new Result(true, false, detected, expected,
                        "Expected static IP is invalid. Detected public IP: " + detected + ".");
            }
            if (!match) {
                return new Result(true, false, detected, expected,
                        "STATIC IP MISMATCH — expected " + expected + ", detected " + detected + ".");
            }
            return new Result(true, true, detected, expected, "Static IP MATCH — " + detected + ".");
        } catch (Exception e) {
            AppPrefs.setStaticIpCheck(context, "", false);
            return new Result(false, false, "", expected, "Static IP detection failed: " + safeMessage(e));
        }
    }

    private static String fetchIp() throws Exception {
        Exception first = null;
        try {
            String body = get(IPIFY_URL);
            JSONObject json = new JSONObject(body);
            String ip = json.optString("ip", "");
            if (!ip.isEmpty()) return ip;
        } catch (Exception e) {
            first = e;
        }
        try {
            String ip = get(AWS_URL).trim();
            if (!ip.isEmpty()) return ip;
        } catch (Exception e) {
            if (first == null) first = e;
        }
        if (first != null) throw first;
        throw new IllegalStateException("No public IP response");
    }

    private static String get(String endpoint) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(3500);
        c.setReadTimeout(5000);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return body;
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

    static String normalizeIp(String input) {
        String s = input == null ? "" : input.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) s = s.substring(1, s.length() - 1);
        return s.toLowerCase(Locale.US);
    }

    static boolean looksLikeIp(String input) {
        String s = normalizeIp(input);
        if (s.isEmpty()) return false;
        if (s.contains(":")) return s.matches("[0-9a-f:%.]+") && !s.contains(" ");
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false;
            int v;
            try { v = Integer.parseInt(part); } catch (Exception e) { return false; }
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
