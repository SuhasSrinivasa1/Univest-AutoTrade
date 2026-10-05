package com.suhas.multyfideliverybuy;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class ResearchJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params){
        new Thread(() -> {
            try{ResearchOrchestrator.tick(getApplicationContext());}
            catch(Throwable t){DiagnosticsStore.error(getApplicationContext(),"RESEARCH_JOB_FAILED","","Scheduled Research Lab job failed.",t);}
            finally{jobFinished(params,false);ResearchScheduler.scheduleNext(getApplicationContext());}
        },"univest-research-nightly").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}
}
