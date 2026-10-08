package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class DurableOfficialSignalQueueTest {
    @Test public void sameConcreteNotificationProducesStableQueueId() {
        String a = DurableOfficialSignalQueue.eventIdFor(
                "EXIT", "BIRLACABLE", "Book profit\nStock: BIRLACABLE", 1791171000000L);
        String b = DurableOfficialSignalQueue.eventIdFor(
                "EXIT", "BIRLACABLE", "Book profit\nStock: BIRLACABLE", 1791171000000L);
        assertEquals(a, b);
    }

    @Test public void laterGenuineNotificationRemainsDistinct() {
        String first = DurableOfficialSignalQueue.eventIdFor(
                "ENTRY", "AEGISLOG", "New Advisory Pick\nStock: AEGISLOG\nDuration: 1-3 months", 1791163560000L);
        String later = DurableOfficialSignalQueue.eventIdFor(
                "ENTRY", "AEGISLOG", "New Advisory Pick\nStock: AEGISLOG\nDuration: 1-3 months", 1791163565000L);
        assertNotEquals(first, later);
    }

    @Test public void eventTypeIsPartOfIdentity() {
        String entry = DurableOfficialSignalQueue.eventIdFor(
                "ENTRY", "ABC", "same body", 1791163560000L);
        String exit = DurableOfficialSignalQueue.eventIdFor(
                "EXIT", "ABC", "same body", 1791163560000L);
        assertNotEquals(entry, exit);
    }
}
