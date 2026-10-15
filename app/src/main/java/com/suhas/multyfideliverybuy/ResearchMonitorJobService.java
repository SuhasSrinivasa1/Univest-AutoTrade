package com.suhas.multyfideliverybuy;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class ResearchMonitorJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                ResearchOrchestrator.tick(getApplicationContext());
                // Independent Formula V1 monitor; never alters Forecast research or execution.
                try { FormulaEngine.refresh(getApplicationContext(), false); }
                catch (Throwable formulaError) {
                    DiagnosticsStore.error(getApplicationContext(), "FORMULA_MONITOR_FAILED", "",
                            "Formula monitor failed without affecting Forecast.", formulaError);
                }
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
