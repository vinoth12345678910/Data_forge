package forge.safety;

import java.nio.file.Path;

/**
 * Test-owned fixtures of groups C and D: purpose-built DDL per dialect (composite key, cascades, triggers, keep table) and the
 * trigger builders. Nothing here imports a product class. Group L (L1) reuses the trigger builders:
 *
 * <pre>
 *   BlockersFx.triggerFx(d, dir, 40)          items (40 rows) + audit + real tables, loaded by generate, backup_cmd "true"
 *   BlockersFx.killRealRowTrigger(d, 20L)     AFTER DELETE on tr_item: deletes the real row tr_real.id = 1 when item 20 is deleted
 *                                             (null key: on every delete). Needs cleanup.allow_triggers: [tr_item].
 *   BlockersFx.dropTrigger(d, "tr_kill_real") removes it again (L1: "the failure is removed")
 *   BlockersFx.auditTrigger(d)                AFTER DELETE on tr_item: writes one tr_audit row per deleted item
 * </pre>
 */
final class BlockersFx {

    private BlockersFx() {}

    /** A complete generator config body: version, seed, clock and "table, rows" pairs in the given order. */
    static String cfg(Object... tableRows) {
        StringBuilder b = new StringBuilder("version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n");
        for (int i = 0; i < tableRows.length; i += 2) {
            b.append("  ").append(tableRows[i]).append(":\n    rows: ").append(tableRows[i + 1]).append('\n');
        }
        return b.toString();
    }

    /** A new target with the given DDL and config, backup_cmd "true". The caller runs generate. */
    static Fx target(Dialect d, Path dir, String config, String... ddl) throws Exception {
        Fx fx = SafetyBase.fx(d, dir);
        fx.custom = config;
        fx.backupCmd = "true";
        fx.ddl(ddl);
        return fx;
    }

    // ------------------------------------------------------------------ C2: composite foreign key

    /** grp has the composite primary key (team_id, tenant_id); grp_member references the pair. */
    static final String[] TIED = {
        "create table grp (team_id bigint not null, tenant_id bigint not null, name varchar(100) not null, primary key (team_id, tenant_id))",
        "create table grp_member (id bigint primary key, team_id bigint not null, tenant_id bigint not null, note varchar(100) not null,"
            + " constraint gm_pair_fk foreign key (team_id, tenant_id) references grp (team_id, tenant_id))"};

    static Fx tied(Dialect d, Path dir) throws Exception {
        Fx fx = target(d, dir, cfg("grp", 10, "grp_member", 30), TIED);
        fx.generate();
        return fx;
    }

    // ------------------------------------------------------------------ C4, C5: cascades

    /** cp_parent and cp_child with ON DELETE CASCADE. */
    static final String[] CASCADE = pair("cp", "on delete cascade", "bigint not null");
    /** sn_parent and sn_child with ON DELETE SET NULL (nullable column). */
    static final String[] SET_NULL = pair("sn", "on delete set null", "bigint");
    /** sd_parent and sd_child with ON DELETE SET DEFAULT (default 1). MySQL (InnoDB) rejects this DDL; see mySetDefaultRejected. */
    static final String[] SET_DEFAULT = pair("sd", "on delete set default", "bigint not null default 1");

    private static String[] pair(String p, String action, String colType) {
        return new String[] {
            "create table " + p + "_parent (id bigint primary key, name varchar(100) not null)",
            "create table " + p + "_child (id bigint primary key, parent_id " + colType + ", note varchar(100) not null,"
                + " constraint " + p + "_child_fk foreign key (parent_id) references " + p + "_parent (id) " + action + ")"};
    }

    /** A loaded parent/child pair (parent 20 rows, child 40 rows) whose prefix is cp, sn or sd. */
    static Fx pairLoaded(Dialect d, Path dir, String prefix, String[] ddl) throws Exception {
        Fx fx = target(d, dir, cfg(prefix + "_parent", 20, prefix + "_child", 40), ddl);
        fx.generate();
        return fx;
    }

    // ------------------------------------------------------------------ C6, C7: triggers

    /** tr_item: the manifest table with the trigger; tr_audit: written by the audit trigger; tr_real: where a real row lives. */
    static String[] triggerSchema(Dialect d) {
        String auditId = switch (d) {
            case PG -> "bigserial primary key";
            case MY -> "bigint auto_increment primary key";
            case SQ -> "integer primary key autoincrement";
        };
        return new String[] {
            "create table tr_item (id bigint primary key, name varchar(100) not null)",
            "create table tr_audit (id " + auditId + ", item_id bigint not null, note varchar(50) not null)",
            "create table tr_real (id bigint primary key, note varchar(100) not null)"};
    }

    /** tr_item loaded with this many rows; tr_audit and tr_real are in the schema with 0 generated rows. No trigger yet. */
    static Fx triggerFx(Dialect d, Path dir, int items) throws Exception {
        Fx fx = target(d, dir, cfg("tr_item", items, "tr_audit", 0, "tr_real", 0), triggerSchema(d));
        fx.generate();
        return fx;
    }

    /** AFTER DELETE on tr_item: insert one tr_audit row (item_id, 'deleted') per deleted row. Trigger name tr_audit_del. */
    static String[] auditTrigger(Dialect d) {
        return switch (d) {
            case PG -> new String[] {
                "create function tr_audit_fn() returns trigger language plpgsql as $$ begin"
                    + " insert into tr_audit (item_id, note) values (old.id, 'deleted'); return old; end $$",
                "create trigger tr_audit_del after delete on tr_item for each row execute function tr_audit_fn()"};
            case MY -> new String[] {
                "create trigger tr_audit_del after delete on tr_item for each row"
                    + " insert into tr_audit (item_id, note) values (old.id, 'deleted')"};
            case SQ -> new String[] {
                "create trigger tr_audit_del after delete on tr_item for each row begin"
                    + " insert into tr_audit (item_id, note) values (old.id, 'deleted'); end"};
        };
    }

    /**
     * AFTER DELETE on tr_item: delete the real row tr_real.id = 1, when item onKey is deleted (onKey null: on every delete).
     * Trigger name tr_kill_real. With no tr_real row 1 it deletes nothing (harmless).
     */
    static String[] killRealRowTrigger(Dialect d, Long onKey) {
        String when = onKey == null ? "" : "old.id = " + onKey;
        return switch (d) {
            case PG -> new String[] {
                "create function tr_kill_fn() returns trigger language plpgsql as $$ begin"
                    + (onKey == null ? "" : " if " + when + " then") + " delete from tr_real where id = 1;"
                    + (onKey == null ? "" : " end if;") + " return old; end $$",
                "create trigger tr_kill_real after delete on tr_item for each row execute function tr_kill_fn()"};
            case MY -> new String[] {
                "create trigger tr_kill_real after delete on tr_item for each row begin"
                    + (onKey == null ? "" : " if " + when + " then") + " delete from tr_real where id = 1;"
                    + (onKey == null ? "" : " end if;") + " end"};
            case SQ -> new String[] {
                "create trigger tr_kill_real after delete on tr_item for each row" + (onKey == null ? "" : " when " + when)
                    + " begin delete from tr_real where id = 1; end"};
        };
    }

    /** Drops a trigger made above (PG also drops its function). */
    static String[] dropTrigger(Dialect d, String name) {
        return switch (d) {
            case PG -> new String[] {"drop trigger " + name + " on tr_item", "drop function " + (name.equals("tr_kill_real") ? "tr_kill_fn" : "tr_audit_fn") + "()"};
            case MY, SQ -> new String[] {"drop trigger " + name};
        };
    }

    // ------------------------------------------------------------------ D1: keep tables

    /** countries (reference) and customers, which point at it. */
    static final String[] KEEP = {
        "create table countries (id bigint primary key, name varchar(100) not null)",
        "create table customers (id bigint primary key, name varchar(100) not null, country_id bigint not null,"
            + " constraint customers_country_fk foreign key (country_id) references countries (id))"};

    /** The real country rows 1..5, inserted by the test as a person would. */
    static void realCountries(Fx fx) {
        for (int i = 1; i <= 5; i++) {
            fx.insert("countries", "id", i, "name", "Real Country " + i);
        }
    }
}
