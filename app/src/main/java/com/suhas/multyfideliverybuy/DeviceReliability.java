package com.suhas.multyfideliverybuy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * OEM reliability helpers. Trading rules are intentionally not present here.
 * This class only helps users keep the notification listener/background jobs alive on restrictive OEM firmware.
 */
final class DeviceReliability {
    private DeviceReliability() {}

    static boolean isVivoFamily() {
        return isVivoManufacturer(Build.MANUFACTURER) || isVivoManufacturer(Build.BRAND);
    }

    static boolean isVivoManufacturer(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return v.contains("vivo") || v.contains("iqoo");
    }

    static String deviceLabel() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String label = (manufacturer + " " + model).trim();
        return label.isEmpty() ? "Android device" : label;
    }

    static boolean isIgnoringBatteryOptimizations(Context c) {
        try {
            PowerManager pm = (PowerManager)c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean requestBatteryOptimizationExemption(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return true;
        } catch (Throwable ignored) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return true;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    static boolean openVivoBackgroundSettings(Activity a) {
        String[][] candidates = new String[][] {
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.iqoo.secure", "com.iqoo.secure.MainActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"}
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (a.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    a.startActivity(i);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return openAppDetails(a);
    }

    static boolean openAppDetails(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
