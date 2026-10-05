package com.suhas.multyfideliverybuy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PerSymbolSerialExecutorTest {
    @Test public void sameSymbolTasksStayInSubmissionOrder() throws Exception {
        PerSymbolSerialExecutor x = new PerSymbolSerialExecutor(4);
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(3);

        x.execute("AEGISLOG", () -> { seen.add(1); sleep(30); done.countDown(); });
        x.execute("AEGISLOG", () -> { seen.add(2); done.countDown(); });
        x.execute("AEGISLOG", () -> { seen.add(3); done.countDown(); });

        assertTrue(done.await(2, TimeUnit.SECONDS));
        x.shutdown();
        assertEquals(java.util.Arrays.asList(1, 2, 3), seen);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
