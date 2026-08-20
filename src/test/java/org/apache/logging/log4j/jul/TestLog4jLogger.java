package org.apache.logging.log4j.jul;

import java.util.logging.Handler;
import java.util.logging.Logger;

/** Test double whose package matches Log4j's JUL adapter hierarchy. */
public final class TestLog4jLogger extends Logger {

    private int addCalls;
    private int removeCalls;
    private int handlerReadCalls;

    public TestLog4jLogger() {
        super("log4j-jul-test", null);
    }

    @Override
    public void addHandler(Handler handler) throws SecurityException {
        addCalls++;
    }

    @Override
    public void removeHandler(Handler handler) throws SecurityException {
        removeCalls++;
    }

    @Override
    public Handler[] getHandlers() {
        handlerReadCalls++;
        return new Handler[0];
    }

    public int getAddCalls() {
        return addCalls;
    }

    public int getRemoveCalls() {
        return removeCalls;
    }

    public int getHandlerReadCalls() {
        return handlerReadCalls;
    }
}
