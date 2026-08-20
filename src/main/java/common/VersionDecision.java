package common;

/** Result of applying an update version policy. */
public final class VersionDecision {
    public final boolean allowed;
    public final String reason;
    public final LooseVersion local;
    public final LooseVersion remote;

    VersionDecision(boolean allowed, String reason, LooseVersion local, LooseVersion remote) {
        this.allowed = allowed;
        this.reason = reason;
        this.local = local;
        this.remote = remote;
    }
}
