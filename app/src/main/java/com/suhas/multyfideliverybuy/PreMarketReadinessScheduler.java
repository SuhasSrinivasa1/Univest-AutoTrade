package com.suhas.multyfideliverybuy;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

final class PreMarketReadinessScheduler {
    static final int JOB_WARMUP = 23033;
    static final int JOB_FINAL = 23034;
    private static final long DEADLINE_SLACK_MS = 10L * 60L * 1000L;

    private PreMarketReadinessScheduler() {}

    static void ensureScheduled(Context context) {
        scheduleIfMissing(context, JOB_WARMUP, 8, 25, "WARMUP");
        scheduleIfMissing(context, JOB_FINAL, 8, 55, "FINAL");
    }

    static void scheduleNext(Context context, int jobId) {
        if (jobId == JOB_WARMUP) schedule(context, JOB_WARMUP, 8, 25, "WARMUP");
        else schedule(context, JOB_FINAL, 8, 55, "FINAL");
    }

    private static void scheduleIfMissing(Context context, int id, int hour, int minute, String phase) {
        try {
            JobScheduler js = (JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                List<JobInfo> jobs = js.getAllPendingJobs();
                if (jobs != null) for (JobInfo j : jobs) if (j != null && j.getId() == id) return;
            }
        } catch (Throwable ignored) {}
        schedule(context, id, hour, minute, phase);
    }

    private static void schedule(Context context, int id, int hour, int minute, String phase) {
        Context c = context.getApplicationContext();
        JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        long at = nextAt(hour, minute);
        long delay = Math.max(60_000L, at - System.currentTimeMillis());
        PersistableBundle extras = new PersistableBundle();
        extras.putString("phase", phase);
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, PreMarketReadinessJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + DEADLINE_SLACK_MS)
                .setExtras(extras);
        try {
            b.setPersisted(true);
            if (js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS) {
                DiagnosticsStore.runtime(c, "PREMARKET_SCHEDULED", "",
                        phase + " target scheduled for " + at + " (08:25/08:55 IST sequence).");
                return;
            }
        } catch (Throwable ignored) {}
        try {
            js.cancel(id);
            JobInfo fallback = new JobInfo.Builder(id, new ComponentName(c, PreMarketReadinessJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(delay)
                    .setOverrideDeadline(delay + DEADLINE_SLACK_MS)
                    .setExtras(extras).build();
            js.schedule(fallback);
        } catch (Throwable ignored) {}
    }

    private static long nextAt(int hour, int minute) {
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        Calendar now = Calendar.getInstance(tz);
        Calendar run = (Calendar)now.clone();
        run.set(Calendar.HOUR_OF_DAY, hour);
        run.set(Calendar.MINUTE, minute);
        run.set(Calendar.SECOND, 0);
        run.set(Calendar.MILLISECOND, 0);
        if (!run.after(now)) run.add(Calendar.DAY_OF_MONTH, 1);
        while (!NseTradingCalendar.isTradingDay(run.getTimeInMillis())) run.add(Calendar.DAY_OF_MONTH, 1);
        return run.getTimeInMillis();
    }
}
