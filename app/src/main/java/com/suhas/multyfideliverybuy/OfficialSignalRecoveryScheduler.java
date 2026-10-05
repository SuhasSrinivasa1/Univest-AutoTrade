package com.suhas.multyfideliverybuy;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class OfficialSignalRecoveryScheduler {
    static final int JOB_ID = 23032;

    private OfficialSignalRecoveryScheduler() {}

    static void scheduleNow(Context context) {
        scheduleAfter(context, 0L);
    }

    static void scheduleAfter(Context context, long delayMs) {
        Context c = context.getApplicationContext();
        try {
            JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, OfficialSignalRecoveryJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(Math.max(0L, delayMs))
                    .setOverrideDeadline(Math.max(30_000L, delayMs + 30_000L));
            int result;
            try {
                result = js.schedule(b.setPersisted(true).build());
            } catch (Throwable persistedFailure) {
                result = JobScheduler.RESULT_FAILURE;
            }
            if (result != JobScheduler.RESULT_SUCCESS) {
                js.cancel(JOB_ID);
                JobInfo fallback = new JobInfo.Builder(JOB_ID,
                        new ComponentName(c, OfficialSignalRecoveryJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(Math.max(0L, delayMs))
                        .setOverrideDeadline(Math.max(30_000L, delayMs + 30_000L))
                        .build();
                result = js.schedule(fallback);
            }
            if (result == JobScheduler.RESULT_SUCCESS) {
                if (AppPrefs.claimRecent(c, "official_recovery_job_scheduled", 5L * 60L * 1000L))
                    DiagnosticsStore.runtime(c, "OFFICIAL_SIGNAL_RECOVERY_SCHEDULED", "",
                            "Durable official-signal recovery job scheduled.");
            } else {
                DiagnosticsStore.error(c, "OFFICIAL_SIGNAL_RECOVERY_SCHEDULE_FAILED", "",
                        "Android rejected durable official-signal recovery job.", null);
            }
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "OFFICIAL_SIGNAL_RECOVERY_SCHEDULE_FAILED", "",
                    "Unable to schedule durable official-signal recovery.", t);
        }
    }
}
