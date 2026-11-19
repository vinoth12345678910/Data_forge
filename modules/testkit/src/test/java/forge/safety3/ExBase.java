package forge.safety3;

import forge.cli.Forge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of the B1 tests (foreign keys into a schema that is not read, the Supabase auth.users pattern): one Postgres 16 for
 * the class (a missing Docker fails loudly, it never skips). Postgres 16 only: cross-schema foreign keys are tested there.
 * "The app" owns auth.users: the tests insert its rows themselves and pass the ids in forge.yml under cleanup.keep.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
abstract class ExBase {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @BeforeAll
    static void envOn() {
        Forge.env = k -> Rx.PW_VAR.equals(k) ? "pw" : System.getenv(k);
    }

    @AfterAll
    static void envOff() {
        Forge.env = System::getenv;
    }

    static final List<String> IDS = List.of(
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555");
    /** An auth.users row that exists but is never listed in cleanup.keep. */
    static final String UNLISTED = "99999999-9999-4999-8999-999999999999";
    /** A well-formed id that is in no table. */
    static final String MISSING = "88888888-8888-4888-8888-888888888888";

    static final String[] SUPABASE = {
        "create schema auth",
        "create table auth.users (id uuid primary key default gen_random_uuid(), email text)",
        "create table public.profiles (id uuid primary key references auth.users(id), display_name text not null)",
        "create table public.posts (id bigint generated always as identity primary key, author uuid not null references public.profiles(id), body text not null)"};
    static final String[] BIGINT = {
        "create schema auth2",
        "create table auth2.accounts (id bigint primary key, name text)",
        "create table public.notes (id bigint generated always as identity primary key, account bigint not null references auth2.accounts(id))"};

    /** cleanup.keep YAML (the body after "cleanup:") listing these ids (already quoted when text) for a table. */
    static String keep(String table, List<String> ids) {
        return "  keep:\n    - { table: " + table + ", ids: [" + String.join(", ", ids) + "] }\n";
    }

    static List<String> quoted(List<String> ids) {
        return ids.stream().map(i -> "\"" + i + "\"").toList();
    }

    /** The app's database: auth.users holds IDS (and UNLISTED); forge.yml gives profiles 5 rows, posts 10 and lists the ids. Nothing generated yet. */
    static Rx supabase(Path dir, boolean listIds) throws Exception {
        Rx rx = new Rx(dir, PG.getHost(), PG.getMappedPort(5432), SUPABASE);
        for (String id : IDS) {
            rx.sql("insert into auth.users (id, email) values ('" + id + "', 'u" + id.charAt(0) + "@app.test')");
        }
        rx.sql("insert into auth.users (id, email) values ('" + UNLISTED + "', 'real@app.test')");
        rx.tablesYaml = "\n  profiles:\n    rows: 5\n  posts:\n    rows: 10\n";
        rx.cleanupYaml = listIds ? keep("auth.users", quoted(IDS)) : "";
        return rx;
    }

    /** auth2.accounts has the ids 101..105 (plus 900); forge.yml gives notes 12 rows and lists 101..105. */
    static Rx bigint(Path dir) throws Exception {
        Rx rx = new Rx(dir, PG.getHost(), PG.getMappedPort(5432), BIGINT);
        rx.sql("insert into auth2.accounts (id, name) select g, 'a' || g from generate_series(101, 105) g");
        rx.sql("insert into auth2.accounts (id, name) values (900, 'unlisted')");
        rx.tablesYaml = "\n  notes:\n    rows: 12\n";
        rx.cleanupYaml = keep("auth2.accounts", List.of("101", "102", "103", "104", "105"));
        return rx;
    }

    /** Every table, view and schema outside the system ones and _dataforge, as text, sorted. */
    static List<String> objects(Rx rx) {
        Stream<String> s = Stream.concat(
            rx.query("select table_schema || '.' || table_name || ':' || table_type from information_schema.tables "
                + "where table_schema not in ('pg_catalog', 'information_schema', '_dataforge') order by 1").stream().map(r -> r.get(0)),
            rx.query("select 'schema ' || schema_name from information_schema.schemata where schema_name not in "
                + "('pg_catalog', 'information_schema', 'pg_toast', '_dataforge') and schema_name not like 'pg_temp%' and schema_name not like 'pg_toast_temp%' order by 1")
                .stream().map(r -> r.get(0)));
        return s.sorted().toList();
    }

    static List<String> manifestFiles(Rx rx) throws IOException {
        Path d = rx.dir.resolve(".forge").resolve("manifests");
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> f = Files.list(d)) {
            return f.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** True when no run was recorded in the database (no _dataforge schema, or an empty runs table). */
    static boolean noRunRow(Rx rx) {
        if (rx.scalar("select count(*) from information_schema.schemata where schema_name = '_dataforge'").equals("0")) {
            return true;
        }
        return rx.scalar("select count(*) from _dataforge.runs").equals("0");
    }
}
