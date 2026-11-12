package forge.testkit;

import forge.core.Config;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.JdbcLoader;
import forge.load.PgLoader;
import forge.rules.RuleSet;
import forge.schema.Db;
import forge.schema.JdbcReader;
import forge.schema.PgReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;

/**
 * Run in a child JVM with a heap limit by ScaleContainerTest. Arguments: mode (load|hash) dialect shape scale seed
 * jdbc-url user password work-dir golden-dir. load: generate and load through the product loader. hash: stream every
 * generated cell through SHA-256 without holding the output (determinism at scale). Prints one line: OK or HASH, with
 * rows, milliseconds and the peak heap in MB.
 */
public final class ScaleMain {

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        String dialect = a[1];
        String shape = a[2];
        int scale = Integer.parseInt(a[3]);
        long seed = Long.parseLong(a[4]);
        Db db = new Db(a[5], a[6].isEmpty() ? null : a[6], a[7]);
        SchemaGraph g;
        if (dialect.equals("postgres")) {
            g = PgReader.read(db, "public");
        } else {
            try (Connection c = db.open()) {
                g = JdbcReader.read(c);
            }
        }
        Config conf = ScaleShapes.config(shape, scale, seed, Path.of(a[9]));
        List<Rule> rules = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
        Live live = new Live();
        live.start();
        long t0 = System.nanoTime();
        RowGen gen = RowGen.of(g, conf, rules);
        if (mode.equals("hash")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long rows = 0;
            for (String table : gen.plan().order()) {
                md.update(("@" + table + "\n").getBytes(StandardCharsets.UTF_8));
                for (long r = 0; r < gen.rows(table); r++) {
                    for (String cell : gen.row(table, r)) {
                        md.update((cell == null ? "\\N" : cell).getBytes(StandardCharsets.UTF_8));
                        md.update((byte) '\t');
                    }
                    md.update((byte) '\n');
                    rows++;
                }
            }
            System.out.println("HASH " + HexFormat.of().formatHex(md.digest()) + " rows=" + rows + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " peakHeapMB=" + peak() + " liveMB=" + live.finish() + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
            return;
        }
        long rows = 0;
        try (Connection c = db.open()) {
            var r = dialect.equals("postgres") ? PgLoader.load(c, g, conf, gen, "scale", "test", Path.of(a[8]))
                : JdbcLoader.load(c, g, conf, gen, "scale", "test", Path.of(a[8]));
            for (long n : r.rows().values()) {
                rows += n;
            }
        }
        System.out.println("OK rows=" + rows + " ms=" + (System.nanoTime() - t0) / 1_000_000 + " peakHeapMB=" + peak()
            + " liveMB=" + live.finish() + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    /**
     * The real peak of the live heap. peakHeapMB (the sum of each memory pool's own peak, as Phase 2 reported) counts
     * garbage and adds peaks that never happened together, so it can exceed -Xmx. This samples every 500 ms after a
     * forced GC, so what it reads is what the program was still holding.
     */
    private static final class Live extends Thread {
        private volatile boolean running = true;
        private long max;

        Live() {
            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                sample();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private synchronized void sample() {
            System.gc();
            Runtime r = Runtime.getRuntime();
            max = Math.max(max, (r.totalMemory() - r.freeMemory()) / (1024 * 1024));
        }

        long finish() {
            running = false;
            sample();
            return max;
        }
    }

    private static long peak() {
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                peak += p.getPeakUsage().getUsed();
            }
        }
        return peak / (1024 * 1024);
    }
}
