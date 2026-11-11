package forge.load;

/** The manifest cannot be made or read. The message says what to do. */
public final class ManifestError extends RuntimeException {

    public ManifestError(String message) {
        super(message);
    }
}
