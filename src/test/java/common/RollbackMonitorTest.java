package common;

import org.apache.logging.log4j.jul.TestLog4jLogger;
import org.junit.Test;

import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RollbackMonitorTest {

    @Test
    public void rejectsLog4jJulAdapterWithoutMutatingHandlers() {
        TestLog4jLogger source = new TestLog4jLogger();

        RollbackMonitor monitor = RollbackMonitor.attach(
                source,
                Logger.getLogger("aup-test-plugin"),
                "velocity"
        );

        assertNull(monitor);
        assertFalse(RollbackMonitor.supportsHandlerMutation(source));
        assertEquals(0, source.getAddCalls());
        assertEquals(0, source.getRemoveCalls());
        assertEquals(0, source.getHandlerReadCalls());
    }

    @Test
    public void reloadReplacesExistingMonitorAndDetachIsIdempotent() {
        Logger source = Logger.getLogger("aup-rollback-monitor-test-" + System.nanoTime());
        source.setUseParentHandlers(false);
        clearHandlers(source);

        RollbackMonitor first = null;
        RollbackMonitor second = null;
        try {
            first = RollbackMonitor.attach(source, source, "paper");
            assertNotNull(first);
            assertEquals(1, rollbackMonitorCount(source));

            second = RollbackMonitor.attach(source, source, "paper");
            assertNotNull(second);
            assertNotSame(first, second);
            assertEquals(1, rollbackMonitorCount(source));

            first.detach();
            first.detach();
            assertEquals(1, rollbackMonitorCount(source));

            second.detach();
            second.detach();
            assertEquals(0, rollbackMonitorCount(source));
        } finally {
            if (first != null) first.detach();
            if (second != null) second.detach();
            clearHandlers(source);
        }

        assertTrue(RollbackMonitor.supportsHandlerMutation(source));
    }

    private static int rollbackMonitorCount(Logger logger) {
        int count = 0;
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof RollbackMonitor) {
                count++;
            }
        }
        return count;
    }

    private static void clearHandlers(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }
    }
}
