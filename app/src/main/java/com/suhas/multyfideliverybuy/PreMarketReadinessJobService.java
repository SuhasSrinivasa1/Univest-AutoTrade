package com.suhas.multyfideliverybuy;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class PreMarketReadinessJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                String phase = params.getExtras() == null ? "" : params.getExtras().getString("phase", "");
                PreMarketReadiness.run(getApplicationContext(), phase);
            } finally {
                jobFinished(params, false);
                PreMarketReadinessScheduler.scheduleNext(getApplicationContext(), params.getJobId());
            }
        }, "premarket-readiness").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}
