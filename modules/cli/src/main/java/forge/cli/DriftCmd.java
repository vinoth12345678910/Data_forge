package forge.cli;

import forge.core.ConfigError;
import forge.core.Drift;
import forge.core.Drift.Change;
import forge.core.Drift.Severity;
import forge.core.Envelope.Entry;
import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.JdbcReader;
import forge.schema.PgReader;
import forge.schema.SnapshotFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * forge drift: has the schema moved since the spec was written, in a way that matters? Read-only on the database.
 * It never writes forge.yml. The only file it may write is forge.schema.json (with --accept) and a suggestion file
 * that is not forge.yml (with --suggest-file).
 */
@Command(name = "drift", description = "Check the schema against the spec (read-only; never writes forge.yml).")
final class DriftCmd implements Callable<Integer> {

    static final String SNAPSHOT = "forge.schema.json";

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml") Path config;
    @Option(names = "--against", description = "Compare with another database (a url) or a snapshot file (.json) instead of forge.schema.json") String against;
    @Option(names = "--accept", description = "Write forge.schema.json from the live schema (the only file drift writes besides --suggest-file)") boolean accept;
    @Option(names = "--fail-on", defaultValue = "error", description = "error (default), warning, info or none: the lowest severity that makes the exit code 1") String failOn;
    @Option(names = "--suggest-file", description = "Write the suggested spec lines to this file (never forge.yml)") Path suggestFile;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            return run(e);
        } catch (ConfigError | SQLException | java.io.IOException | IllegalArgumentException ex) {
            e.fail("drift", ex);
            return e.done("drift", 2);
        }
    }

    private static int rank(Severity s) {
        return switch (s) {
            case ERROR -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }

    private int run(Emit e) throws SQLException, java.io.IOException {
        int threshold = switch (failOn) {
            case "error" -> 3;
            case "warning", "warn" -> 2;
            case "info" -> 1;
            case "none" -> 4;
            default -> throw new ConfigError("--fail-on must be error, warning, info or none, not " + failOn + ".");
        };
        if (suggestFile != null && Files.exists(suggestFile) && Files.isSameFile(suggestFile, config)
            || suggestFile != null && suggestFile.toAbsolutePath().normalize().equals(config.toAbsolutePath().normalize())) {
            throw new ConfigError("--suggest-file may not be forge.yml: drift never writes your spec.");
        }
        Live live = new Live(config);
        live.openGraph();
        e.typeWarnings(live.typeWarnings);
        Path snap = config.toAbsolutePath().normalize().getParent().resolve(SNAPSHOT);
        if (accept) {
            Files.writeString(snap, SnapshotFile.write(live.graph));
            e.out.println("Wrote " + snap + " from the live schema (" + live.graph.tables().size() + " tables). forge.yml was not touched.");
            e.data.put("accepted", true);
            e.data.put("snapshot", snap.toString());
            return e.done("drift", 0);
        }
        SchemaGraph base = baseline(live, snap);
        List<Change> changes = new ArrayList<>(Drift.diff(base, live.graph, live.conf));
        Set<String> added = new LinkedHashSet<>();
        boolean hasError = false;
        for (Change c : changes) {
            if (c.kind().equals("table_added")) {
                added.add(c.table());
            }
            hasError |= c.severity() == Severity.ERROR;
        }
        if (!hasError) {
            String bad = SpecCheck.problem(live.conf, live.graph, added);
            if (bad != null) {
                changes.add(new Change("spec_invalid", Severity.ERROR, "", null, "the spec no longer works against this schema", bad, List.of()));
            }
        }
        int[] counts = new int[4];
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        boolean fail = false;
        for (Change c : changes) {
            counts[rank(c.severity())]++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            m.put("severity", c.severity().name().toLowerCase(java.util.Locale.ROOT));
            m.put("table", c.table());
            if (c.column() != null) {
                m.put("column", c.column());
            }
            m.put("detail", c.detail());
            m.put("evidence", c.evidence());
            m.put("suggest", c.suggest());
            list.add(m);
            suggestions.addAll(c.suggest());
            boolean trips = rank(c.severity()) >= threshold;
            fail |= trips;
            Entry entry = new Entry("drift_" + c.kind(), c.detail() + ": " + c.evidence(), null, c.table().isEmpty() ? null : c.table(), c.column(), null, null);
            (trips ? e.errors : e.warnings).add(entry);
            e.out.println(c.severity().name() + "  " + c.detail());
            e.out.println("       " + c.evidence());
            for (String s : c.suggest()) {
                e.out.println("       suggest  " + s);
            }
        }
        e.out.println("drift: " + changes.size() + " change(s)  error " + counts[3] + "  warning " + counts[2] + "  info " + counts[1]
            + "  (fail on " + failOn + ")");
        if (suggestFile != null) {
            Files.writeString(suggestFile, String.join("\n", suggestions) + (suggestions.isEmpty() ? "" : "\n"));
        }
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("error", counts[3]);
        c.put("warning", counts[2]);
        c.put("info", counts[1]);
        e.data.put("changes", list);
        e.data.put("counts", c);
        e.data.put("fail_on", failOn);
        e.data.put("baseline", against != null ? against : snap.toString());
        return e.done("drift", fail ? 1 : 0);
    }

    private SchemaGraph baseline(Live live, Path snap) throws SQLException, java.io.IOException {
        if (against != null && against.endsWith(".json")) {
            return SnapshotFile.read(Files.readString(Path.of(against)));
        }
        if (against != null) {
            return live.read(against);
        }
        if (!Files.exists(snap)) {
            throw new ConfigError("no baseline: " + snap + " does not exist. Run forge init (it writes it) or forge drift --accept to take the current schema as the baseline.");
        }
        return SnapshotFile.read(Files.readString(snap));
    }
}
