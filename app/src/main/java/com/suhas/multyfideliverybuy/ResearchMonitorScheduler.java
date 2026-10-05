package com.suhas.multyfideliverybuy;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

final class ResearchMonitorScheduler {
    static final int JOB_ID = 23031;
    private static final long PERIOD_MS = 15L * 60L * 1000L;

    private ResearchMonitorScheduler() {}

    static void ensureScheduled(Context context) {
        Context c = context.getApplicationContext();
        try {
            JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return;

            int persisted = js.schedule(build(c, true));
            if (persisted == JobScheduler.RESULT_SUCCESS) {
                DiagnosticsStore.runtime(c, "RESEARCH_MONITOR_SCHEDULED", "",
                        "15-minute Research orchestrator monitor scheduled as persisted job.");
                return;
            }

            js.cancel(JOB_ID);
            int fallback = js.schedule(build(c, false));
            if (fallback == JobScheduler.RESULT_SUCCESS) {
                DiagnosticsStore.runtime(c, "RESEARCH_MONITOR_SCHEDULED_FALLBACK", "",
                        "15-minute Research orchestrator monitor scheduled with non-persisted OEM fallback.");
                return;
            }
            DiagnosticsStore.error(c, "RESEARCH_MONITOR_SCHEDULE_FAILED", "",
                    "Android rejected persisted and non-persisted Research monitor jobs.", null);
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_MONITOR_SCHEDULE_FAILED", "",
                    "Unable to schedule Research orchestrator monitor.", t);
        }
    }

    static boolean isActive(Context context) {
        try {
            JobScheduler js = (JobScheduler)context.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
            return js != null && Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JobInfo build(Context c, boolean persisted) {
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, ResearchMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS);
        if (persisted) b.setPersisted(true);
        return b.build();
    }
}
