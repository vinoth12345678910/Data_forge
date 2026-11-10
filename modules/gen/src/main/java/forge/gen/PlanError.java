package forge.gen;

/** The schema or the row counts cannot be planned. The message names the tables and columns. */
public class PlanError extends RuntimeException {
    public PlanError(String message) {
        super(message);
    }
}
