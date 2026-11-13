package forge.testkit;

import forge.junit.ForgeDb;
import forge.junit.ForgeHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Review fix 10 (F1): changes forge.junit.url while the class is running, as a build or another test might. The
 * database must still be dropped on the server that created it. Runs only when the gate sets forge.junit.gate.
 */
@EnabledIfSystemProperty(named = "forge.junit.gate", matches = "true")
@ForgeDb(schema = "${golden.dir}/events-merch/schema.sql", config = "${golden.dir}/events-merch/phase3.forge.yml", seed = 7)
class ForgeJunitSampleE {

    static volatile String name;

    @Test
    void changesTheServerPropertyMidClass(ForgeHandle db) {
        name = db.name();
        System.setProperty("forge.junit.url", "jdbc:postgresql://127.0.0.1:1/postgres");
    }
}
