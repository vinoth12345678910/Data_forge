package forge.load;

/** A load that was refused or failed. Nothing was loaded: the message says what to do. */
public final class LoadError extends RuntimeException {

    public LoadError(String message) {
        super(message);
    }

    public LoadError(String message, Throwable cause) {
        super(message, cause);
    }
}
