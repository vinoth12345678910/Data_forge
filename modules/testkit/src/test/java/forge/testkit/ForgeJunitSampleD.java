package forge.testkit;

import forge.junit.ForgeDb;
import forge.junit.ForgeHandle;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Review fix 10 (F4): records the application_name its connections get, so the gate can see whether a query parameter
 * of forge.junit.url (?ApplicationName=...) is kept in the per-class url. Runs only when the gate sets forge.junit.gate.
 */
@EnabledIfSystemProperty(named = "forge.junit.gate", matches = "true")
@ForgeDb(schema = "${golden.dir}/events-merch/schema.sql", config = "${golden.dir}/events-merch/phase3.forge.yml", seed = 7)
class ForgeJunitSampleD {

    static volatile String app;
    static volatile String url;

    @Test
    void recordsTheApplicationName(ForgeHandle db) throws Exception {
        url = db.url();
        try (Connection c = db.connection(); Statement s = c.createStatement(); var r = s.executeQuery("show application_name")) {
            r.next();
            app = r.getString(1);
        }
    }
}
