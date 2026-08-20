package common;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class RollbackMonitor extends Handler {

    private static final Object ATTACH_LOCK = new Object();
    private static final String LOG4J_JUL_PACKAGE = "org.apache.logging.log4j.jul.";

    private final Logger sourceLogger;
    private final Logger pluginLogger;
    private final String platform;
    private final AtomicBoolean attached = new AtomicBoolean(false);

    private RollbackMonitor(Logger sourceLogger, Logger pluginLogger, String platform) {
        this.sourceLogger = sourceLogger;
        this.pluginLogger = pluginLogger;
        this.platform = platform;
        setLevel(Level.ALL);
    }

    public static RollbackMonitor attach(Logger sourceLogger, Logger pluginLogger, String platform) {
        if (sourceLogger == null || pluginLogger == null) {
            return null;
        }

        // Velocity installs Log4j's JUL bridge as the active LogManager. Its Logger
        // implementation deliberately ignores Handler mutations and emits a warning for
        // every addHandler/removeHandler call. Detect that adapter before touching it.
        if (!supportsHandlerMutation(sourceLogger)) {
            return null;
        }

        synchronized (ATTACH_LOCK) {
            try {
                // Reloading the plugin must never leave multiple monitors on the server
                // logger, even if a previous platform lifecycle did not retain its handle.
                for (Handler handler : sourceLogger.getHandlers()) {
                    if (handler instanceof RollbackMonitor) {
                        ((RollbackMonitor) handler).detach();
                    }
                }

                RollbackMonitor monitor = new RollbackMonitor(sourceLogger, pluginLogger, platform);
                sourceLogger.addHandler(monitor);

                // A custom JUL implementation may silently ignore handler mutations. Only
                // retain a detachable monitor after confirming that it was installed.
                for (Handler handler : sourceLogger.getHandlers()) {
                    if (handler == monitor) {
                        monitor.attached.set(true);
                        return monitor;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Rollback monitoring is a safety feature; an incompatible logging backend
                // must not prevent the plugin itself from loading or reloading.
            }
            return null;
        }
    }

    public void detach() {
        if (!attached.compareAndSet(true, false)) {
            return;
        }
        try {
            sourceLogger.removeHandler(this);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    static boolean supportsHandlerMutation(Logger sourceLogger) {
        if (sourceLogger == null) {
            return false;
        }
        Class<?> type = sourceLogger.getClass();
        while (type != null) {
            String name = type.getName();
            if (name.startsWith(LOG4J_JUL_PACKAGE)) {
                return false;
            }
            type = type.getSuperclass();
        }
        return true;
    }

    @Override
    public void publish(LogRecord record) {
        if (!UpdateOptions.rollbackEnabled) return;
        if (record == null) return;
        if (!isLoggable(record)) return;

        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.toLowerCase(Locale.ROOT).contains("autoupdateplugins")) {
            return;
        }
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message != null && params != null && params.length > 0) {
            try {
                message = MessageFormat.format(message, params);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (message != null && !message.isEmpty()) {
            RollbackManager.handleLogLine(pluginLogger, platform, message);
        }
        if (record.getThrown() != null) {
            RollbackManager.handleThrowable(pluginLogger, platform, record.getThrown());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        detach();
    }
}
