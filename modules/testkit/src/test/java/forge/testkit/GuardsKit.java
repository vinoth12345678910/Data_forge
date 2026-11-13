package forge.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import forge.testkit.DryHalvesTest.R;
import forge.testkit.DryHalvesTest.W;
import forge.testkit.OwnershipKit.D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The public door of the P5.3 gate tests (package forge.guards) to the package-private P5.2 fixtures (OwnershipKit,
 * DryHalvesTest). Test code only. Setup is forge generate, plain SQL and forge cleanup --dry-run; nothing here ever
 * needs a successful forge cleanup --run, and nothing here deletes a row.
 */
public final class GuardsKit {

    private GuardsKit() {}

    public enum Db {
        PG(D.PG), MY(D.MY), SQ(D.SQ);

        final D d;

        Db(D d) {
            this.d = d;
        }
    }

    /** What Forge.env should be during these tests (the test login's password variable). */
    public static String env(String k) {
        return OwnershipKit.env(k);
    }

    /** The result of one forge command. */
    public record Res(String cmd, int exit, JsonNode json, String out, String err) {

        private R r() {
            return new R(cmd, exit, json, out, err);
        }

        public Set<String> codes() {
            return r().codes();
        }

        public Res expect(String step, int wantExit, String... codes) {
            r().expect(step, wantExit, codes);
            return this;
        }

        public JsonNode field(String step, String name) {
            return r().field(step, name);
        }

        public String describe() {
            return r().describe();
        }
    }

    /** One loaded database with parent and child tables (gp_parent, gp_child; the child's parent_id may be null) and its config. */
    public static final class Target {
        private final W w;
        private final String root;

        private Target(W w) {
            this.w = w;
            this.root = w.base;
        }

        /** Loaded by forge generate; backup_cmd is set to a command that only touches marker() (it must never run in P5.3). */
        public static Target pair(Db db, Path dir, int parents, int children) throws Exception {
            String[] ddl = DryHalvesTest.pair("gp", "", "bigint");
            Target t = new Target(DryHalvesTest.custom(db.d, dir, DryHalvesTest.rows("gp_parent", parents, "gp_child", children), ddl));
            t.backup("touch '" + t.marker() + "'");
            return t;
        }

        /** tables: name, rows, name, rows ... as the forge.yml tables block. */
        public static String rows(Object... tableRows) {
            return DryHalvesTest.rows(tableRows);
        }

        /** A target with its own tables and row counts (tables as DryHalvesTest.rows builds them); backup_cmd only touches marker(). */
        public static Target custom(Db db, Path dir, String tables, String... ddl) throws Exception {
            Target t = new Target(DryHalvesTest.custom(db.d, dir, tables, ddl));
            t.backup("touch '" + t.marker() + "'");
            return t;
        }

        /** A new plain connection to the target database (for a second session that holds locks). */
        public java.sql.Connection open() throws java.sql.SQLException {
            return w.t.open();
        }

        public Db db() {
            return w.d == D.PG ? Db.PG : w.d == D.MY ? Db.MY : Db.SQ;
        }

        public Path dir() {
            return w.dir;
        }

        /** Exists only if some command ran the configured backup command. */
        public Path marker() {
            return w.dir.resolve("backup-ran");
        }

        public Res dry(String... extra) {
            return res(w.dry(extra));
        }

        /** forge cleanup --run <extra> -c forge.yml --json */
        public Res run(String... extra) {
            String[] a = DryHalvesTest.concat(new String[] {"cleanup", "--run"}, extra);
            return res(DryHalvesTest.forge(DryHalvesTest.concat(a, new String[] {"-c", w.yml().toString(), "--json"})));
        }

        private static Res res(R r) {
            return new Res(r.cmd(), r.exit(), r.json(), r.out(), r.err());
        }

        /** Null removes backup_cmd from the config. */
        public void backup(String cmd) throws Exception {
            w.base = root.replace("  env: test\n", "  env: test\n"
                + (cmd == null ? "" : "  backup_cmd: " + OwnershipKit.OM.writeValueAsString(cmd) + "\n"));
        }

        public void env(String label) {
            w.env = label;
        }

        /** A whole top level block appended to forge.yml, for example "cleanup:\n  yes: true\n". */
        public void extraYaml(String block) {
            w.cleanup = block;
        }

        // ---------------------------------------------------------------- the database, by plain SQL

        /** The real database name, taken from the database itself (SQLite: the file name without folder). */
        public String dbName() throws Exception {
            return switch (w.d) {
                case PG -> q("select current_database()").get(0).get(0);
                case MY -> q("select database()").get(0).get(0);
                default -> Path.of(w.t.jUrl.substring("jdbc:sqlite:".length())).getFileName().toString();
            };
        }

        public List<List<String>> q(String sql) throws Exception {
            return w.q(sql);
        }

        public long one(String sql) throws Exception {
            return w.t.one(sql);
        }

        public void sql(String... stmts) throws Exception {
            w.t.script(stmts);
        }

        /** The real name of a _dataforge table (logical: runs, dry_runs, audit). */
        public String mt(String logical) {
            return w.t.mt(logical);
        }

        public String state() throws Exception {
            return q("select state from " + mt("runs")).get(0).get(0);
        }

        public long dryRows() throws Exception {
            return w.t.manifestRows("dry_runs");
        }

        public long auditRows() throws Exception {
            return w.t.manifestRows("audit");
        }

        /** Every row of every user table as sorted text. */
        public String userSnap() throws Exception {
            return w.snap();
        }

        /** Every _dataforge table (with or without the audit table) as sorted text. */
        public String metaSnap(boolean withAudit) throws Exception {
            String names = switch (w.d) {
                case PG -> "select table_schema || '.' || table_name from information_schema.tables where table_schema = '_dataforge'";
                case MY -> "select table_name from information_schema.tables where table_schema = database() and table_name like '\\_dataforge%'";
                default -> "select name from sqlite_master where type = 'table' and name like '\\_dataforge%' escape '\\'";
            };
            List<String> tables = new ArrayList<>();
            for (List<String> r : w.q(names)) {
                String n = r.get(0);
                if (withAudit || !(n.endsWith(".audit") || n.equals("_dataforge_audit"))) {
                    tables.add(n);
                }
            }
            Collections.sort(tables);
            StringBuilder sb = new StringBuilder();
            for (String n : tables) {
                List<String> rows = new ArrayList<>();
                for (List<String> r : w.q("select * from " + n)) {
                    rows.add(String.join("|", r.stream().map(v -> v == null ? "\\N" : v).toList()));
                }
                Collections.sort(rows);
                sb.append(n).append('\n').append(String.join("\n", rows)).append('\n');
            }
            return sb.toString();
        }

        /** The manifest folder: file names, sizes and a content hash. */
        public String manifestFiles() throws Exception {
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> s = Files.list(w.mdir())) {
                for (Path p : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                    byte[] b = Files.readAllBytes(p);
                    sb.append(p.getFileName()).append(' ').append(b.length).append(' ').append(java.util.Arrays.hashCode(b)).append('\n');
                }
            }
            return sb.toString();
        }

        // ---------------------------------------------------------------- audit log

        /** The lines of .forge/audit/audit.jsonl, parsed. */
        public List<JsonNode> auditFile() throws Exception {
            Path f = w.dir.resolve(".forge").resolve("audit").resolve("audit.jsonl");
            List<JsonNode> out = new ArrayList<>();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    out.add(OwnershipKit.OM.readTree(line));
                }
            }
            return out;
        }

        /** The entry texts of the audit table, oldest first. */
        public List<String> auditTable() throws Exception {
            List<String> out = new ArrayList<>();
            for (List<String> r : q("select entry from " + mt("audit") + " order by id")) {
                out.add(r.get(0));
            }
            return out;
        }

        /** The audit file lines as text. */
        public List<String> auditFileLines() throws Exception {
            Path f = w.dir.resolve(".forge").resolve("audit").resolve("audit.jsonl");
            return Files.exists(f) ? Files.readAllLines(f) : List.of();
        }

        /** .forge becomes a plain file, so the audit folder cannot be made (works for any user, root too). */
        public void breakAuditFile() throws IOException {
            Path f = w.dir.resolve(".forge");
            if (Files.isDirectory(f)) {
                try (Stream<Path> s = Files.walk(f)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            Files.writeString(f, "not a folder\n");
        }

        /** The audit table is replaced by one the insert cannot use (one column only). */
        public void breakAuditTable() throws Exception {
            sql("drop table " + mt("audit"), "create table " + mt("audit") + " (id integer)");
        }

        // ---------------------------------------------------------------- the dry_runs rows

        /** Makes every dry run this many hours older (negative: in the future), by a SQL edit of the metadata row. */
        public void ageDryRuns(int hours) throws Exception {
            String t = mt("dry_runs");
            switch (w.d) {
                case PG -> sql("update " + t + " set made_at = made_at - interval '" + hours + " hours'");
                case MY -> sql("update " + t + " set made_at = date_sub(made_at, interval " + hours + " hour)");
                default -> sql("update " + t + " set made_at = strftime('%Y-%m-%dT%H:%M:%SZ', substr(made_at, 1, 19), '"
                    + String.format("%+d hours", -hours) + "')");
            }
        }

        /** Every dry run now belongs to another manifest. */
        public void dryRunsToOtherManifest() throws Exception {
            sql("update " + mt("dry_runs") + " set run_id = '00000000-0000-4000-8000-000000000001'");
        }
    }
}
