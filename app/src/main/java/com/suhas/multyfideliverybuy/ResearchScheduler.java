package com.suhas.multyfideliverybuy;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// v2.3.3 scheduler fallback: persisted first, OEM-compatible non-persisted retry.
final class ResearchScheduler {
    static final int JOB_ID = 23030;
    private static final long DEADLINE_SLACK_MS = 60L * 60L * 1000L;

    private ResearchScheduler() {}

    static void ensureScheduled(Context context) {
        Context c = context.getApplicationContext();
        try {
            JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                fail(c, "NO_JOB_SCHEDULER", "Android JobScheduler service is unavailable.", null);
                return;
            }
            List<JobInfo> jobs = js.getAllPendingJobs();
            if (jobs != null) {
                for (JobInfo j : jobs) {
                    if (j != null && j.getId() == JOB_ID) {
                        long next = AppPrefs.getResearchNextScheduledAt(c);
                        if (next <= 0L) next = nextRunAt();
                        String method = AppPrefs.getResearchScheduleMethod(c);
                        if (method == null || method.isEmpty() || "NOT_SCHEDULED".equals(method))
                            method = j.isPersisted() ? "JOB_PERSISTED" : "JOB_NON_PERSISTED";
                        AppPrefs.setResearchScheduleState(c, method, next, "");
                        return;
                    }
                }
            }
            scheduleNext(c);
        } catch (Throwable t) {
            fail(c, "ENSURE_FAILED", "Unable to inspect Research Lab jobs.", t);
            scheduleNonPersistedFallback(c);
        }
    }

    static boolean scheduleNext(Context context) {
        Context c = context.getApplicationContext();
        long runAt = nextRunAt();
        long delay = Math.max(60_000L, runAt - System.currentTimeMillis());

        JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            fail(c, "NO_JOB_SCHEDULER", "Android JobScheduler service is unavailable.", null);
            return false;
        }

        // First attempt: persisted job. Some OEM builds reject persisted registration even when
        // RECEIVE_BOOT_COMPLETED is declared; if so, transparently fall back to non-persisted.
        try {
            int result = js.schedule(build(c, delay, true));
            if (result == JobScheduler.RESULT_SUCCESS) {
                success(c, "JOB_PERSISTED", runAt, "Persisted Research Lab job scheduled.");
                return true;
            }
            DiagnosticsStore.runtime(c, "RESEARCH_SCHEDULE_PERSISTED_REJECTED", "",
                    "Android returned RESULT_FAILURE for persisted Research Lab job; trying non-persisted fallback.");
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_SCHEDULE_PERSISTED_FAILED", "",
                    "Persisted Research Lab schedule failed; trying non-persisted fallback.", t);
        }

        return scheduleNonPersisted(c, js, runAt, delay);
    }

    static boolean scheduleNonPersistedFallback(Context context) {
        Context c = context.getApplicationContext();
        long runAt = nextRunAt();
        long delay = Math.max(60_000L, runAt - System.currentTimeMillis());
        JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            fail(c, "NO_JOB_SCHEDULER", "Android JobScheduler service is unavailable.", null);
            return false;
        }
        return scheduleNonPersisted(c, js, runAt, delay);
    }

    private static boolean scheduleNonPersisted(Context c, JobScheduler js, long runAt, long delay) {
        try {
            js.cancel(JOB_ID);
            int result = js.schedule(build(c, delay, false));
            if (result == JobScheduler.RESULT_SUCCESS) {
                success(c, "JOB_NON_PERSISTED", runAt,
                        "Research Lab scheduled with non-persisted OEM-compatible fallback.");
                return true;
            }
            fail(c, "RESULT_FAILURE", "Android rejected both persisted and non-persisted Research Lab jobs.", null);
            return false;
        } catch (Throwable t) {
            fail(c, "NON_PERSISTED_FAILED", "Android rejected the non-persisted Research Lab fallback.", t);
            return false;
        }
    }

    private static JobInfo build(Context c, long delay, boolean persisted) {
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, ResearchJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + DEADLINE_SLACK_MS);
        if (persisted) b.setPersisted(true);
        return b.build();
    }

    private static void success(Context c, String method, long runAt, String detail) {
        AppPrefs.setResearchScheduleState(c, method, runAt, "");
        DiagnosticsStore.runtime(c, "RESEARCH_SCHEDULED", "",
                detail + " Next target " + format(runAt) + " IST.");
    }

    private static void fail(Context c, String code, String message, Throwable t) {
        String detail = message;
        if (t != null) {
            String m = t.getMessage();
            detail += " " + t.getClass().getSimpleName() + (m == null || m.trim().isEmpty() ? "" : ": " + m);
        }
        AppPrefs.setResearchScheduleState(c, "FAILED_" + code, 0L, detail);
        DiagnosticsStore.error(c, "RESEARCH_SCHEDULE_FAILED", "", detail, t);
    }

    static String statusText(Context c) {
        String method = AppPrefs.getResearchScheduleMethod(c);
        long next = AppPrefs.getResearchNextScheduledAt(c);
        String error = AppPrefs.getResearchScheduleError(c);
        StringBuilder b = new StringBuilder();
        if (method != null && method.startsWith("JOB_")) {
            b.append("ACTIVE • ");
            b.append("JOB_PERSISTED".equals(method) ? "persistent job" : "OEM-compatible fallback job");
            if (next > 0L) b.append("\nNext scan: ").append(format(next)).append(" IST");
        } else {
            b.append("NOT ACTIVE");
            if (error != null && !error.trim().isEmpty()) b.append("\n").append(error);
        }
        return b.toString();
    }

    private static long nextRunAt() {
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        Calendar now = Calendar.getInstance(tz);
        Calendar run = (Calendar)now.clone();
        run.set(Calendar.HOUR_OF_DAY, 17);
        run.set(Calendar.MINUTE, 30);
        run.set(Calendar.SECOND, 0);
        run.set(Calendar.MILLISECOND, 0);
        if (!run.after(now)) run.add(Calendar.DAY_OF_MONTH, 1);
        while (!NseTradingCalendar.isTradingDay(run.getTimeInMillis())) {
            run.add(Calendar.DAY_OF_MONTH, 1);
        }
        return run.getTimeInMillis();
    }

    private static String format(long when) {
        SimpleDateFormat f = new SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date(when));
    }
}
