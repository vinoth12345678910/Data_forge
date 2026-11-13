package forge.load;

/** A load that was refused or failed. Nothing was loaded: the message says what to do. code is a stable name for --json, or null. */
public final class LoadError extends RuntimeException {
    private final String code;

    public LoadError(String message) {
        this(null, message);
    }

    public LoadError(String code, String message) {
        super(message);
        this.code = code;
    }

    public LoadError(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public String code() {
        return code;
    }
}
