package forge.core;

import java.util.List;

/** A foreign key. deferred means initially deferred. refTable equals the own table for a self-reference. */
public record Fk(
    String name, List<String> columns, String refTable, List<String> refColumns, boolean deferrable, boolean deferred) {}
