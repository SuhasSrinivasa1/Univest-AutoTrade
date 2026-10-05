package com.suhas.multyfideliverybuy;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class ResearchMonitorJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                ResearchOrchestrator.tick(getApplicationContext());
            } catch (Throwable t) {
                DiagnosticsStore.error(getApplicationContext(), "RESEARCH_MONITOR_JOB_FAILED", "",
                        "Research orchestrator monitor failed.", t);
            } finally {
                jobFinished(params, false);
            }
        }, "research-live-monitor").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
