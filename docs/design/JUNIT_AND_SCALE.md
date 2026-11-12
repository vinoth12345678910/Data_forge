# JUnit hook and scale test (Phase 4, decision D4: PROPOSAL, not approved)

Status: written in Phase 4 Step 0 for the user to approve, change or reject. Nothing is built from it until approved.

## Part 1: the JUnit 5 extension (module `junit`)

### What a test looks like

```java
@ForgeDb(schema = "golden/events-merch/schema.sql", config = "golden/events-merch/phase3.forge.yml",
         seed = 42, scenario = "sale_rush")
class OrdersTest {

    @Test
    void paidShareFollowsTheScenario(ForgeHandle db) throws Exception {
        try (var c = db.connection(); var s = c.createStatement();
             var r = s.executeQuery("select count(*) from orders where status = 'paid'")) {
            r.next();
            assertTrue(r.getInt(1) > 100);
        }
    }
}
```

| Piece | Meaning |
|---|---|
| `@ForgeDb` (class or method) | `schema` (a DDL file on the classpath or path), `config` (a `forge.yml`, optional: zero-config inference if absent), `seed` (overrides the config's), `scenario` (a name from the config, default `default`), `edge` (boolean), `dialect` (Phase 4: `POSTGRES` only) |
| `ForgeExtension` | `BeforeAllCallback`, `AfterAllCallback`, `ParameterResolver`; registered by the annotation (`@ExtendWith`) |
| `ForgeHandle` parameter | `connection()`, `dataSource()`, `url()`, `name()` (the database name) |
| Starting a database | one **shared Testcontainers Postgres container per JVM**, started lazily by the first class that asks, stopped when the JVM ends (Testcontainers' own reaper); the image tag lives in one constant |
| Per class | the extension creates **its own database** in that container (`forge_<8 hex of a counter-and-class-name hash>`), loads the schema, generates with the seed and scenario, loads through the product loader (same `PgLoader` as the CLI), and hands the handle to the tests |
| After the class | the extension drops **that database only** (see D4a) |
| Parallel | classes run in parallel (JUnit `junit.jupiter.execution.parallel.enabled`); every class has its own database; the shared container is thread-safe because only `create database` and `drop database` of the extension's own names touch it |
| Same seed, same data | generation is the product's deterministic one; the test compares a hash of a table across two JVM runs |
| Network | none, except Testcontainers talking to the local Docker API and pulling an image |

Dependencies: `junit` depends on `core`, `schema`, `gen`, `rules`, `load`; Testcontainers and JUnit are `api` for test users. **`gen` and `rules` still do not depend on each other**; the extension is a consumer of both, like `cli`.

### What the extension never does

Touch a database it did not create (it records its own names in a per-JVM set and refuses to drop anything else), delete rows, read the user's `forge.yml` for writing, or contact any host. MySQL and SQLite are out of scope for the hook in Phase 4 (SQLite needs no container; a later phase may add it).

## Part 2: the scale test (P4.6)

### What "1,000,000 rows" means: four shapes

| # | Shape | Rows | Why it is in |
|---|---|---|---|
| S1 | one simple table | 1,000,000 | the Phase 2 shape (loader memory gate) |
| S2 | 1,000,000 parents, 3,000,000 children | 4,000,000 | the Phase 2 shape, parents with children |
| S3 | **events-merch with rules and state machines on**, `orders` at 1,000,000 (children by layout, items, payments, registrations scaled to fit) | about 4,000,000 | realistic: rules, pre-passes and layouts under memory pressure |
| S4 | **tied composite foreign keys**: ecommerce `shipment_items`, `shipments` and `order_items` tied by `(id, order_id)`, 1,000,000 at the largest | about 3,000,000 | the memory gap Phase 2 and 3 left: `RowGen` reads each tied parent table into an index once |

### Limits

| | Limit | Source |
|---|---|---|
| Heap of the generator and loader | **`-Xmx256m`** in a child JVM (Postgres runs in its container, so only the generator, the COPY stream and the JDBC driver are under the limit) | Phase 2 gate (`LoaderMemoryContainerTest`), unchanged |
| Peak heap actually used | reported by the child (memory pool peaks); asserted to be at most **85% of `-Xmx`** (a margin so a near-miss fails before an OutOfMemory) | new |
| Time | S1 at most 60 s, S2 at most 180 s, S3 at most 300 s, S4 at most 300 s | generous on purpose: Phase 2 measured 2.9 s and 25 s on this machine; the limit catches a 10x slowdown, not noise; the real numbers go in `docs/PERF.md` |
| Databases | **Postgres: S1 to S4. MySQL and SQLite: S1 only** (batched `INSERT`, much slower than `COPY`; limit 600 s) | decision D4b |

### How the test proves it can fail

1. **Mutation:** the same child JVM with a deliberately lowered `-Xmx` (for example `-Xmx48m`) must make the test fail. The test runs both and asserts the lowered run fails.
2. **Row counts and structure:** every shape ends with `count(*)` equal to the configured rows and the structure check clean, so a run that does less work cannot pass on speed.
3. **Determinism at scale:** the child streams the generator's output through SHA-256 without holding it, twice with the same seed (equal) and once with another (different). One streaming pass is under the same `-Xmx`.

### How time is recorded per release

`scripts/perf.sh` runs the scale test and appends one row per shape to `docs/PERF.md`: git tag or commit, date, machine (CPU model, RAM), JDK, database image, shape, rows, seconds, peak heap MB, rows per second, and the Phase 2 number for the same shape where one exists. The normal gate does **not** write to `PERF.md` (it only asserts limits); a release runs `scripts/perf.sh`. The Phase 2 and Phase 3 numbers are copied in as the first rows so a later slowdown shows.

### Tied composite foreign keys (S4)

`RowGen` reads each tied parent table **once** and groups it by the shared values (a `TreeMap` of `long[]` per key). Memory grows with the parent rows. Three honest options:

1. **Measure first, then choose.** Run S4 at 100k, 300k and 1,000,000 parents under `-Xmx256m` and record peak heap. If it passes with margin the gap is **closed for that shape** and the doc says so; if not, go to 2 or 3.
2. **Compact the index.** Replace the per-group `long[]` objects with one sorted `long[]` of parent row indexes plus an offsets `int[]` (8 bytes per parent row instead of about 40). It changes no output (a test pins S4 bytes before and after). Likely enough for 1,000,000 parents.
3. **Report it as not proven bounded.** The test records the measured heap and fails only if the code or docs *claim* a bound that the measurement does not support.

## Decision D4 (for you)

**D4a. How does the extension "clean up its own database"?** (This is the one place Phase 4 brushes against "no delete or cleanup code": `DROP DATABASE` of a database the extension itself just created.)
1. **Drop only the database this extension created in this JVM (its recorded name), nothing else, enforced by a name set; scope-guard reviews it as test infrastructure, not row cleanup (recommended).** The container stays up for the next class; the database does not pile up.
2. **Never drop; rely on the container ending with the JVM.** No delete statement exists at all. Databases accumulate for a long test run, and a very long suite could exhaust the container.
3. **One container per class.** No drop needed, strong isolation; each class pays a container start (seconds) and Docker load rises. A good fallback if you want zero `DROP`.

**D4b. Scale shapes and databases.**
1. **S1 to S4 on Postgres; S1 on MySQL and SQLite (recommended).** Covers parents with children, rules and tied keys where the loader is fastest, and shows the other two dialects scale.
2. **S1 and S2 on Postgres only.** The Phase 2 coverage plus the PERF record; fastest; leaves rules and tied keys unproven at scale.
3. **All four shapes on all three databases.** Complete, but MySQL and SQLite loads at 4,000,000 rows take a very long time and add little proof.

**D4c. Tied composite foreign key memory.**
1. **Measure, then compact the index only if the measurement requires it (recommended).** Honest, smallest change; the output bytes are pinned so compaction cannot change data.
2. **Compact the index regardless.** Closes the gap by design; touches `RowGen` (your generator design) even if unnecessary.
3. **Do not touch `RowGen`; report "not proven bounded".** Zero risk to the generator design, leaves a known gap in the 1M promise.

## Carry-over notes for this doc

`keep` tables stay refused by `RowGen`. Postgres 17: one informational run of S1 and S2 on the 17 image (the pinned constant becomes overridable); not a gate.

## APPROVED by the user (Phase 4 reply) with changes

**D4a approved with limits.** (1) If the extension started the container, teardown only **stops the container**; no delete SQL is run. (2) In shared-container mode (a container the test run supplied), `DROP DATABASE` is allowed **only for a name the extension generated** (fixed prefix `forge_` plus a UUID) and recorded in memory in that JVM; any other name is refused. (3) The extension does not use or touch the `cleanup` module or any manifest code. **The user will run safety-reviewer on that diff in a fresh session.**
**D4b approved:** shapes S1 to S4, including S2 (1,000,000 parents with 3,000,000 children) and S4 (a tied composite foreign key shape); MySQL and SQLite run S1.
**D4c approved:** measure first; if the tied-key memory is not bounded and not fixed, the report says "not proven bounded".
