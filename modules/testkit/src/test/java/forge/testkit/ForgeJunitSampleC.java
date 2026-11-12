package forge.testkit;

import forge.junit.ForgeDb;
import forge.junit.ForgeHandle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exactly class A except for the seed: the only difference in the data can come from the seed. */
@Tag("containers")
@ForgeDb(schema = "${golden.dir}/events-merch/schema.sql", config = "${golden.dir}/events-merch/phase3.forge.yml", seed = 7, scenario = "sale_rush")
class ForgeJunitSampleC {

    @Test
    void sameAsAExceptTheSeed(ForgeHandle db) throws Exception {
        SampleRecord.note("C", db);
    }
}
