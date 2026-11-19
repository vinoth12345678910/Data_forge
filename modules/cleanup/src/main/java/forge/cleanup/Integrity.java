package forge.cleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Config;
import forge.load.Manifest;
import forge.load.ManifestFile;
import forge.schema.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * The manifest checks that come before any row is looked at (ROW_OWNERSHIP.md, GUARDS.md), in this order: file present,
 * file parses, file hash equals the stored hash, format 2, the database copy rebuilds to the same hash, the schema is
 * the one that was loaded. Nothing is written to the manifest folder or the database: the rebuild is made in a scratch
 * folder that is removed at once, and the file is never rebuilt.
 */
final class Integrity {

    record Checked(Manifest manifest, Refusal refusal) {}

    private static final ObjectMapper OM = new ObjectMapper();

    private Integrity() {}

    static Checked check(Ctx x, String runId, String state) throws SQLException, IOException {
        Path dir = x.manifestDir();
        Path json = dir.resolve(runId + ".json");
        if (!Files.isRegularFile(json)) {
            return no("manifest_file_missing", "The manifest file " + json + " is missing; the database copy is intact. Rebuild it first."
                + " Nothing was deleted.");
        }
        byte[] text = Files.readAllBytes(json);
        JsonNode root;
        try {
            root = OM.readTree(text);
        } catch (IOException e) {
            return no("manifest_corrupt", "The manifest of run " + runId + " cannot be read (" + one(e.getMessage())
                + "). Nothing was deleted.");
        }
        if (root == null || !root.isObject() || !root.has("format")) {
            return no("manifest_corrupt", "The manifest of run " + runId + " cannot be read (not a manifest). Nothing was deleted.");
        }
        String stored = Sql.one(x.ro(), x.d() == forge.core.Dialect.POSTGRES
            ? "select file_sha256 from _dataforge.runs where run_id = ?::uuid" : "select file_sha256 from "
            + Meta.t(x.d(), "runs") + " where run_id = ?", runId);
        String fileSha = sha(text, dir.resolve(runId + ".keys"), dir.resolve(runId + ".fps"));
        if (!fileSha.equals(stored)) {
            return no("manifest_tampered", "The manifest file and the database copy of run " + runId + " differ (hash "
                + fileSha + " against " + stored + "). Nothing was deleted.");
        }
        if (root.path("format").asInt() != 2 || state == null) {
            return no("manifest_old_format", "manifest " + runId + " has no row fingerprints (format 1). Data Forge will not delete"
                + " rows it cannot prove unchanged. Load again into an empty database, or clean this one by hand.");
        }
        Manifest m = MetaRead.read(x.ro(), x.d(), runId);
        String rebuilt = rebuild(m);
        if (!rebuilt.equals(stored)) {
            return no("manifest_tampered", "The manifest file and the database copy of run " + runId + " differ (hash "
                + rebuilt + " against " + stored + "). Nothing was deleted.");
        }
        String live = hex(Snapshot.yaml(x.g().withoutRelations()).getBytes(StandardCharsets.UTF_8));
        if (!live.equals(m.schemaSha())) {
            return no("schema_changed", "The schema changed since run " + runId + " was loaded (hash differs). Nothing was deleted.");
        }
        return new Checked(m, null);
    }

    /** The hash the database copy would have as files, made in a scratch folder that is deleted again. */
    private static String rebuild(Manifest m) throws IOException {
        Path scratch = Files.createTempDirectory("forge-check-");
        try {
            ManifestFile.Staged st = ManifestFile.stage(scratch, m);
            String sha = st.sha256();
            st.discard();
            return sha;
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof SQLException se) {
                throw new IOException("database read failed: " + se.getMessage(), se);
            }
            return "unreadable";
        } finally {
            try (var files = Files.list(scratch)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(scratch);
        }
    }

    private static Checked no(String code, String message) {
        return new Checked(null, new Refusal(code, message));
    }

    private static String one(String s) {
        return s == null ? "unreadable" : s.replace('\n', ' ');
    }

    private static String sha(byte[] json, Path keys, Path fps) throws IOException {
        MessageDigest d = digest();
        d.update(json);
        for (Path p : new Path[] {keys, fps}) {
            if (Files.isRegularFile(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        d.update(buf, 0, n);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(d.digest());
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the table is a keep table in today's config. */
    static boolean keep(Config conf, String table) {
        Config.TableConf tc = conf.tables().get(table);
        return tc != null && tc.keep();
    }
}
