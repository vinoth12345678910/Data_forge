package forge.junit;

import java.sql.Connection;
import java.sql.SQLException;

/** The database one test class owns. */
public interface ForgeHandle {

    /** The database name (forge_ plus 32 hex digits). */
    String name();

    /** A JDBC url for it. */
    String url();

    String user();

    String password();

    /** A new connection to it; the caller closes it. */
    Connection connection() throws SQLException;
}
