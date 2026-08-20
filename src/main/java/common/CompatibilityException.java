package common;

import java.io.IOException;

/** Valid provider metadata was returned, but no release matches this server environment. */
final class CompatibilityException extends IOException {
    CompatibilityException(String message) {
        super(message);
    }
}
