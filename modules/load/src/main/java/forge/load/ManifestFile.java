package forge.load;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;

/** The local copy: <run_id>.json and, when a table lists keys, <run_id>.keys. Written as .part, renamed after commit. */
public final class ManifestFile {

    private ManifestFile() {}

    /** Files written under .part names; publish renames them, discard deletes them. */
    public record Staged(Path dir, String runId, String sha256, boolean hasKeys, boolean hasFps) {
        public void publish() throws IOException {
            Files.move(dir.resolve(runId + ".json.part"), dir.resolve(runId + ".json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (hasKeys) {
                Files.move(dir.resolve(runId + ".keys.part"), dir.resolve(runId + ".keys"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasFps) {
                Files.move(dir.resolve(runId + ".fps.part"), dir.resolve(runId + ".fps"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        public void discard() throws IOException {
            Files.deleteIfExists(dir.resolve(runId + ".json.part"));
            Files.deleteIfExists(dir.resolve(runId + ".keys.part"));
            Files.deleteIfExists(dir.resolve(runId + ".fps.part"));
        }
    }

    public static Staged stage(Path dir, Manifest m) throws IOException {
        Files.createDirectories(dir);
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("format", m.format());
        root.put("run_id", m.runId());
        root.put("seed", m.seed());
        root.put("env", m.env());
        root.put("tool_version", m.toolVersion());
        root.put("config_sha256", m.configSha());
        root.put("schema_sha256", m.schemaSha());
        root.put("clock", m.clock());
        root.put("loaded_at", m.loadedAt());
        if (m.format() >= 2) {
            ArrayNode keep = root.putArray("keep");
            for (Manifest.Keep k : m.keep()) {
                keep.add(om.readTree(k.spec()));
            }
            ObjectNode pre = root.putObject("pre");
            for (Map.Entry<String, Long> e : new TreeMap<>(m.pre()).entrySet()) {
                pre.put(e.getKey(), e.getValue());
            }
        }
        boolean hasFps = m.format() >= 2;
        Path fpsPart = dir.resolve(m.runId() + ".fps.part");
        boolean hasKeys = m.tables().stream().anyMatch(t -> t.form().equals("list"));
        Path keysPart = dir.resolve(m.runId() + ".keys.part");
        ArrayNode tables = root.putArray("tables");
        try (BufferedWriter kw = hasKeys ? Files.newBufferedWriter(keysPart, StandardCharsets.UTF_8) : null;
            BufferedWriter fw = hasFps ? Files.newBufferedWriter(fpsPart, StandardCharsets.UTF_8) : null) {
            for (Manifest.Tbl t : m.tables()) {
                ObjectNode n = tables.addObject();
                n.put("name", t.name());
                n.put("load_order", t.loadOrder());
                n.put("rows", t.rows());
                n.put("rows_before", t.rowsBefore());
                ArrayNode key = n.putArray("key");
                t.key().forEach(key::add);
                if (t.form().equals("ranges")) {
                    ArrayNode rs = n.putArray("ranges");
                    for (long[] r : t.ranges()) {
                        rs.addArray().add(r[0]).add(r[1]);
                    }
                } else {
                    n.put("keys_file", m.runId() + ".keys");
                    n.put("keys", t.rows());
                    t.keys().each(k -> {
                        try {
                            kw.write(t.name() + "\t" + k + "\n");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                }
                if (hasFps) {
                    n.put("fp_chunks", t.fpChunks());
                    t.fps().each((chunk, rows, fps) -> {
                        try {
                            fw.write(t.name() + "\t" + chunk + "\t" + rows + "\t" + HexFormat.of().formatHex(fps) + "\n");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                }
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        ArrayNode breaks = root.putArray("breaks");
        for (Manifest.Break b : m.breaks()) {
            breaks.addObject().put("table", b.table()).put("fk", b.fk()).put("how", b.how());
        }
        ArrayNode seqs = root.putArray("sequences");
        for (Manifest.Seq s : m.sequences()) {
            ObjectNode n = seqs.addObject();
            n.put("table", s.table()).put("column", s.column()).put("seq", s.name());
            n.putArray("before").add(s.beforeLast()).add(s.beforeCalled());
            if (s.afterLast() == null) {
                n.putNull("after");
            } else {
                n.putArray("after").add(s.afterLast()).add(s.afterCalled());
            }
        }
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
        Path jsonPart = dir.resolve(m.runId() + ".json.part");
        Files.write(jsonPart, (om.writer(pp).writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8));
        MessageDigest d = Sha.digest();
        feed(d, jsonPart);
        if (hasKeys) {
            feed(d, keysPart);
        } else {
            Files.deleteIfExists(keysPart);
        }
        if (hasFps) {
            feed(d, fpsPart);
        }
        return new Staged(dir, m.runId(), HexFormat.of().formatHex(d.digest()), hasKeys, hasFps);
    }

    private static void feed(MessageDigest d, Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
        }
    }

    /** Rewrites the local copy from the database copy; fails if its hash differs from the recorded one. */
    public static String rebuild(Connection c, Path dir, String runId) throws SQLException, IOException {
        Manifest m = ManifestDb.read(c, runId);
        Staged st = stage(dir, m);
        String want = ManifestDb.fileSha(c, runId);
        if (!st.sha256().equals(want)) {
            st.discard();
            throw new ManifestError("the manifest rebuilt from the database for run " + runId
                + " does not match the hash recorded when it was loaded; the database copy was changed.");
        }
        st.publish();
        return st.sha256();
    }
}
