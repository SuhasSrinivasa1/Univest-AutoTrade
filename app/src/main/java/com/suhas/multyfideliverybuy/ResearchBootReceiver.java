package com.suhas.multyfideliverybuy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ResearchBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        ResearchScheduler.ensureScheduled(c);
        ResearchMonitorScheduler.ensureScheduled(c);
        PreMarketReadinessScheduler.ensureScheduled(c);
        UnivestHistoryDb.ensureInitialized(c);
        DurableOfficialSignalQueue.recoverPending(c);
        OfficialSignalRecoveryScheduler.scheduleNow(c);
    }
}
