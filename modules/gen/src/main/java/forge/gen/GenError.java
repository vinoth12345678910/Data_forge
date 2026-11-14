package forge.gen;

/** The data cannot be generated as asked. The message names the table and columns. code is a stable name for --json, or null. */
public class GenError extends RuntimeException {
    private final String code;

    public GenError(String message) {
        this(null, message);
    }

    public GenError(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
