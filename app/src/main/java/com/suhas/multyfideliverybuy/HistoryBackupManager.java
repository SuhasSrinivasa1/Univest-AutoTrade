package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * User-owned portable history archive. The selected SAF document lives outside app-private storage
 * and therefore can survive uninstall/new signing certificates. Android revokes the URI grant on
 * uninstall, so a fresh install must select the same ZIP once to restore/reconnect it.
 *
 * Groww credentials, TOTP secrets and access tokens are deliberately never included.
 */
final class HistoryBackupManager {
    private static final AtomicBoolean BACKUP_RUNNING = new AtomicBoolean(false);
    private static final long AUTO_BACKUP_MIN_INTERVAL_MS = 5L * 60L * 1000L;

    private HistoryBackupManager() {}

    static void rememberUri(Context c, Uri uri) {
        if (uri == null) return;
        AppPrefs.setHistoryBackupUri(c, uri.toString());
    }

    static boolean isConnected(Context c) {
        String s = AppPrefs.getHistoryBackupUri(c);
        return s != null && !s.trim().isEmpty();
    }

    static String statusText(Context c) {
        if (!isConnected(c)) {
            return "Not connected. Create a portable backup now; after an uninstall/new-signature install, select the same ZIP once to restore history.";
        }
        long t = AppPrefs.getHistoryBackupTime(c);
        String status = AppPrefs.getHistoryBackupStatus(c);
        return (status == null || status.isEmpty() ? "Portable history backup connected." : status)
                + (t > 0 ? " • last write " + new java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US)
                .format(new java.util.Date(t)) : "");
    }

    static void exportToUri(Context c, Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("Missing backup destination.");
        UnivestHistoryDb.ensureInitialized(c);
        OutputStream raw = c.getContentResolver().openOutputStream(uri, "wt");
        if (raw == null) throw new IllegalStateException("Cannot open history backup destination.");
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            JSONObject manifest = new JSONObject();
            manifest.put("format", "UNIVEST_PORTABLE_HISTORY");
            manifest.put("schemaVersion", 3);
            manifest.put("appVersion", "2.9.5");
            manifest.put("createdAt", System.currentTimeMillis());
            manifest.put("containsCredentials", false);
            manifest.put("researchIncludes", "frozen generic playbooks, adaptive stock memory, matched controls, 40-point signal profiles, raw candles, rolling benchmark inputs, forecast history");
            addText(zip, "manifest.json", manifest.toString(2));
            addText(zip, "history/event-ledger.json", UnivestHistoryDb.exportJson(c).toString());

            JSONArray states = new JSONArray();
            for (UnivestStateStore.State s : UnivestStateStore.all(c)) states.put(s.toJson());
            addText(zip, "state/univest-states.json", states.toString());
            addText(zip, "state/univest-averaging-registry.json", UnivestAveragingRegistry.exportJson(c).toString());
            addText(zip, "settings/non-secret.json", PortableSettings.exportJson(c).toString(2));

            File research = new File(c.getFilesDir(), "research_lab");
            addDirectory(zip, research, "research_lab/");
            File diagnostics = new File(c.getFilesDir(), "univest_diagnostics");
            addDirectory(zip, diagnostics, "diagnostics/");
        }
        rememberUri(c, uri);
        AppPrefs.setHistoryBackupState(c, System.currentTimeMillis(),
                "Portable history backup OK • " + UnivestHistoryDb.count(c) + " ledger events");
    }

    static int importFromUri(Context c, Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("Missing backup file.");
        JSONObject history = null;
        JSONArray states = null;
        JSONArray averagingRegistry = null;
        JSONObject portableSettings = null;
        InputStream raw = c.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IllegalStateException("Cannot open selected history backup.");
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName() == null ? "" : e.getName();
                if (e.isDirectory() || unsafe(name)) { zip.closeEntry(); continue; }
                if ("history/event-ledger.json".equals(name)) {
                    history = new JSONObject(readText(zip));
                } else if ("state/univest-states.json".equals(name)) {
                    states = new JSONArray(readText(zip));
                } else if ("state/univest-averaging-registry.json".equals(name)) {
                    averagingRegistry = new JSONArray(readText(zip));
                } else if ("settings/non-secret.json".equals(name)) {
                    portableSettings = new JSONObject(readText(zip));
                } else if (name.startsWith("research_lab/")) {
                    restoreFile(c, zip, name.substring("research_lab/".length()),
                            new File(c.getFilesDir(), "research_lab"));
                } else if (name.startsWith("diagnostics/")) {
                    restoreFile(c, zip, name.substring("diagnostics/".length()),
                            new File(c.getFilesDir(), "univest_diagnostics"));
                }
                zip.closeEntry();
            }
        }

        UnivestHistoryDb.ensureInitialized(c);
        int imported = history == null ? 0 : UnivestHistoryDb.importJson(c, history);
        if (states != null) {
            for (int i = 0; i < states.length(); i++) {
                JSONObject j = states.optJSONObject(i);
                if (j == null) continue;
                UnivestStateStore.State s = UnivestStateStore.State.fromJson(j);
                if (s.symbol != null && !s.symbol.trim().isEmpty()) UnivestStateStore.put(c, s);
            }
        }
        if (averagingRegistry != null) UnivestAveragingRegistry.importJson(c, averagingRegistry);
        if (portableSettings != null) PortableSettings.importJson(c, portableSettings);
        // Applies to both new schema-3 backups and older compatible backups: restore must never resume trading.
        AppPrefs.setArmed(c, false);
        AppPrefs.setUnivestEnabled(c, false);
        AppPrefs.setResearchAutoTradeEnabled(c, false);
        AppPrefs.clearAccessToken(c);
        rememberUri(c, uri);
        AppPrefs.setHistoryBackupState(c, System.currentTimeMillis(),
                "History/settings restored safely DISARMED • " + imported + " new ledger events • total " + UnivestHistoryDb.count(c));
        return imported;
    }

    static void scheduleAutoBackup(Context c) {
        if (!isConnected(c)) return;
        long last = AppPrefs.getHistoryBackupTime(c);
        if (last > 0 && System.currentTimeMillis() - last < AUTO_BACKUP_MIN_INTERVAL_MS) return;
        if (!BACKUP_RUNNING.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String s = AppPrefs.getHistoryBackupUri(app);
                if (s != null && !s.isEmpty()) exportToUri(app, Uri.parse(s));
            } catch (Throwable t) {
                AppPrefs.setHistoryBackupState(app, System.currentTimeMillis(),
                        "Portable history auto-backup failed: " + safe(t));
            } finally {
                BACKUP_RUNNING.set(false);
            }
        }, "univest-history-backup").start();
    }

    static void forceAutoBackup(Context c) {
        if (!isConnected(c) || !BACKUP_RUNNING.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                exportToUri(app, Uri.parse(AppPrefs.getHistoryBackupUri(app)));
            } catch (Throwable t) {
                AppPrefs.setHistoryBackupState(app, System.currentTimeMillis(),
                        "Portable history backup failed: " + safe(t));
            } finally {
                BACKUP_RUNNING.set(false);
            }
        }, "univest-history-backup-force").start();
    }

    private static void addDirectory(ZipOutputStream zip, File dir, String prefix) throws Exception {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (f.isDirectory()) addDirectory(zip, f, prefix + f.getName() + "/");
            else if (f.isFile()) {
                zip.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) zip.write(b, 0, n);
                }
                zip.closeEntry();
            }
        }
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void restoreFile(Context c, ZipInputStream zip, String relative, File root) throws Exception {
        if (relative == null || relative.isEmpty() || unsafe(relative)) return;
        File out = new File(root, relative);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] b = new byte[8192]; int n;
            while ((n = zip.read(b)) > 0) fos.write(b, 0, n);
            fos.getFD().sync();
        }
    }

    private static String readText(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = zip.read(b)) > 0) out.write(b, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static boolean unsafe(String name) {
        return name.contains("..") || name.startsWith("/") || name.startsWith("\\");
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
