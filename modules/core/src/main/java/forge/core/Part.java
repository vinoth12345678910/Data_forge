package forge.core;

/** One partition. bound is the raw bound text; from and to are set for a single-column range partition. */
public record Part(String name, String bound, String from, String to) {}
