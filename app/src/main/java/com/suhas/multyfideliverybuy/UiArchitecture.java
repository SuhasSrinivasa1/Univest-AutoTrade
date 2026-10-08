package com.suhas.multyfideliverybuy;

/**
 * Pure presentation contracts shared by the dashboard and local JVM tests.
 * No Android framework dependency belongs here.
 */
final class UiArchitecture {
    private UiArchitecture() {}

    static String[] primaryTabLabels() {
        return new String[]{"Execution", "Research", "Forecast", "Settings"};
    }

    static String humanCampaignPhase(String phase) {
        if ("ENTRY_PENDING".equals(phase)) return "Buying";
        if ("ACTIVE".equals(phase)) return "Holding";
        if ("WAIT_REENTRY".equals(phase) || "FLAT_WAIT_EXIT".equals(phase)) return "Waiting";
        if ("EXITING_PROFIT".equals(phase) || "EXITING_OFFICIAL".equals(phase)
                || "EXITING_STOP".equals(phase)) return "Selling";
        if ("EXITED".equals(phase)) return "Closed";
        if ("ERROR".equals(phase)) return "Attention";
        return "Active";
    }
}
