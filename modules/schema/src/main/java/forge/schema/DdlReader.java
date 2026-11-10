package forge.schema;

import forge.core.SchemaGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reads a DDL file by applying it to a scratch database on a real Postgres server and
 * reading that back with PgReader. So there is one reader, and the server itself decides
 * what the DDL means. The scratch database is created here and dropped here; nothing else
 * on the server is touched.
 */
public final class DdlReader {

    private DdlReader() {}

    public static SchemaGraph read(Path ddl, Db server) throws SQLException, IOException {
        return read(ddl, server, "public");
    }

    public static SchemaGraph read(Path ddl, Db server, String schema) throws SQLException, IOException {
        String sql = Files.readString(ddl);
        String name = "forge_scratch_" + Long.toHexString(System.nanoTime());
        try (Connection c = server.open(); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        try {
            Db scratch = server.withDatabase(name);
            try (Connection c = scratch.open(); Statement s = c.createStatement()) {
                s.setEscapeProcessing(false);
                s.execute(sql);
            } catch (SQLException e) {
                throw new SQLException("DDL file " + ddl + " did not apply: " + e.getMessage(), e.getSQLState(), e);
            }
            return PgReader.read(scratch, schema);
        } finally {
            try (Connection c = server.open(); Statement s = c.createStatement()) {
                s.execute("drop database if exists " + name + " with (force)");
            }
        }
    }
}
