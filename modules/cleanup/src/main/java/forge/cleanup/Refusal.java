package forge.cleanup;

/** A refusal: the command stops, nothing is changed, and the code and message go in errors[]. */
record Refusal(String code, String message) {}
