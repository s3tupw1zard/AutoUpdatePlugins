package common;

import java.nio.file.Path;

/** Detailed result of a payload comparison or install. */
public final class TransferOutcome {
    public enum Status {
        APPLIED,
        AVAILABLE,
        BLOCKED,
        UNCHANGED,
        FAILED
    }

    public final Status status;
    public final String pluginName;
    public final Path targetPath;
    public final Path livePath;
    public final JarMetadata before;
    public final JarMetadata after;
    public final String reason;

    public TransferOutcome(Status status, String pluginName, Path targetPath, Path livePath,
                           JarMetadata before, JarMetadata after, String reason) {
        this.status = status == null ? Status.FAILED : status;
        this.pluginName = pluginName;
        this.targetPath = targetPath;
        this.livePath = livePath;
        this.before = before;
        this.after = after;
        this.reason = reason;
    }

    public boolean handled() {
        return status != Status.FAILED;
    }
}
