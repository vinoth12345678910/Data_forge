package forge.testkit;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.PgLoader;
import forge.schema.Db;
import forge.schema.PgReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Run in a child JVM (-Xmx256m) by LoaderMemoryContainerTest: reads the schema, generates and loads
 * through the product loader, prints the row counts, the time and the peak heap, exits 0 on success.
 * Arguments: jdbc-url user password manifest-dir table=rows ...
 */
public final class LoaderMain {

    public static void main(String[] args) throws Exception {
        Db db = new Db(args[0], args[1], args[2]);
        SchemaGraph g = PgReader.read(db, "public");
        Map<String, Config.TableConf> t = new TreeMap<>();
        for (int i = 4; i < args.length; i++) {
            String[] kv = args[i].split("=");
            t.put(kv[0], new Config.TableConf(Integer.parseInt(kv[1]), false, new TreeMap<>()));
        }
        Config conf = new Config(1, 42, "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", t);
        long t0 = System.nanoTime();
        try (Connection c = db.open()) {
            PgLoader.Result r = PgLoader.load(c, g, conf, RowGen.of(g, conf), "memory test", "test", Path.of(args[3]));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long peak = 0;
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    peak += p.getPeakUsage().getUsed();
                }
            }
            System.out.println("OK rows=" + r.rows() + " ms=" + ms + " peakHeapMB=" + peak / (1024 * 1024)
                + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        }
    }
}
