package forge.gen;

/** The data cannot be generated as asked. The message names the table and columns. */
public class GenError extends RuntimeException {
    public GenError(String message) {
        super(message);
    }
}
