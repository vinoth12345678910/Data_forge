package forge.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** A database to connect to. url is a JDBC url (jdbc:postgresql://host:port/database). */
public record Db(String url, String user, String password) {

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** The same server, another database. */
    public Db withDatabase(String name) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        int query = url.indexOf('?');
        String tail = query < 0 ? "" : url.substring(query);
        return new Db(url.substring(0, slash + 1) + name + tail, user, password);
    }
}
