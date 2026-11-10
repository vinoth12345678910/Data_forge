# Seeded random (approved, user-owned design)

Status: approved by the user on 2026-11-10 (Phase 1 Step 0). P1.4 implements exactly this. Any change after approval goes back to the user first.

## The idea in four sentences

1. Every value is a pure function of five inputs: `seed`, `table`, `column`, `row`, `purpose`. Nothing else.
2. A slow, strong hash (SHA-256) runs once per column to make a 64-bit **column key**.
3. A fast mixer (SplitMix64) turns `column key + row` into the random bits for that one cell.
4. Unique values are not "generate and retry": the row number is pushed through a shuffle that never repeats (a keyed permutation), so two rows cannot collide.

There is no generator object that is advanced from row to row. To get row 900,000 you do not need rows 0 to 899,999.

## The five inputs

| Input | What it is |
|---|---|
| `seed` | The signed 64-bit `seed` from `forge.yml`. |
| `table` | The table name exactly as the database stores it (with the schema prefix when it is not the default schema). |
| `column` | The column name exactly as stored. |
| `row` | The row index inside its table: 0, 1, 2, ... |
| `purpose` | A short fixed word saying what the bits are for: `value`, `null`, `perm`, `fk`. Each purpose is its own independent stream. |

## Step 1: the column key (once per column and purpose)

```
bytes = "forge/1" 00  seed (8 bytes, big-endian)  00  utf8(table)  00  utf8(column)  00  utf8(purpose)
key   = first 8 bytes of SHA-256(bytes), read as a big-endian 64-bit integer
```

- The `00` byte separators stop `("ab","c")` and `("a","bc")` from hashing the same.
- `"forge/1"` is the algorithm version. If the design ever changes, the tag changes, and old data stays reproducible with the old version.
- SHA-256 comes from the JDK (`MessageDigest`). It is a published standard: same bytes in, same bytes out, on every JVM and machine. It is used here for mixing quality, not for security.
- Cost: one hash per column per purpose, a few hundred in a run. Never per row.

## Step 2: the cell (per row)

SplitMix64 is the mixer. It is ten lines, written in our own code (not `java.util.Random`, not `SplittableRandom`), so the JDK cannot change it under us.

```
GAMMA = 0x9E3779B97F4A7C15

mix(z):
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)

cellStart(key, row) = mix(key + (row + 1) * GAMMA)
draw number i of the cell (i = 1, 2, 3, ...) = mix(cellStart + i * GAMMA)
```

- A value that needs one random number (an int in a range, a pick from a list) uses draw 1. A value that needs several (a text of twelve letters) uses draws 1, 2, 3, ... of the same cell.
- `mix` is a one-to-one function on 64-bit numbers. So inside one column, two different rows always start from different numbers.
- All arithmetic is Java `long` arithmetic, which wraps around on overflow in a way the Java language fixes. No floating point is involved.

## Turning bits into values

| Need | How |
|---|---|
| Integer in `[0, n)` | The high 64 bits of the 128-bit product `draw * n` (`Math.unsignedMultiplyHigh`). No loop. The bias is below `n / 2^64`, which is far below anything measurable. |
| Pick from a list | Integer in `[0, size)`, then index into the list. The list has a fixed order (see "Data files"). |
| Yes or no at a rate | Rates are held as whole parts per 10,000. Yes when integer in `[0, 10000)` is below the rate. No floating point. |
| Decimal and money | Generated as an integer count of the smallest unit, then the decimal point is placed. Money in minor units is just an integer. |
| Date and timestamp | An integer number of seconds (or days) before the `clock` in `forge.yml`, within the allowed range. Never the wall clock, never the default time zone. |
| Text | Words picked from a bundled list, one draw per word. |

Where a real number cannot be avoided (later phases: Zipf, Pareto), only `StrictMath` is allowed, because `Math` may give different last digits on different CPUs.

## NULLs and optional values

- A nullable column has a NULL rate (default 10%, set per column with `null_rate`).
- The NULL decision uses the `null` purpose: its own column key, its own stream.
- Because it is a separate stream, changing the NULL rate changes which rows are NULL, but a row that stays non-NULL keeps exactly the same value.
- The same yes-or-no mechanism serves any later "optional" choice.

## Unique values without retry loops

A retry loop ("generate, check a set, try again") makes row N depend on every row before it. That breaks stable rows, breaks streaming, and needs memory for the set. It is not used.

Instead: **shuffle the row number, then encode it.**

1. Pick a fixed index space of size `N` for the column. `N` comes from the column's type and constraints, never from the row count: for example `2^40` for a free text column, the size of the check range for a checked integer, the number of enum values for an enum.
2. `p = permute(row)` where `permute` is a keyed one-to-one shuffle of `[0, N)`. It is a 4-round Feistel network keyed by the `perm` column key, with cycle-walking when `N` is not a power of two (if the result lands outside `[0, N)`, shuffle it again; this ends quickly and depends only on that one row).
3. Encode `p` into the value with a one-to-one encoding. For an integer column the value is `min + p`. For an email it is a name plus `p` written in base 36, for example `asha.rao.k3f9@example.test`.

Different rows give different `p` (the shuffle is one-to-one), and different `p` give different values (the encoding is one-to-one). So uniqueness is guaranteed by construction, for any row in any order, with no memory.

Rules that follow from this:

- **Primary keys.** Integer keys are `row + 1`. UUID keys are the shuffled row number spread over the 122 free bits of a version-4 UUID: guaranteed unique, not just "very unlikely to collide".
- **A unique constraint over several columns** is satisfied by making one of its columns unique on its own (the "carrier"): if one column never repeats, the group never repeats. The carrier is the first column of the constraint, in constraint order, that is not a foreign key and has a big enough index space.
- **A unique constraint made only of foreign keys** (a junction table, or `unique (user_id, event_id)`): the pair itself is enumerated. `p = permute(row)` over `[0, A * B)`, then `first = p / B`, `second = p % B`, where `A` and `B` are the parents' row counts. Needs `rows <= A * B`.
- **Partial and expression unique indexes** (`where deleted_at is null`, `lower(email)`) are treated as plain unique on the columns they mention, and text carriers are generated in lower case. This is stricter than the index asks, so it always satisfies it.
- **Not enough room** (`rows > N`, or `rows > A * B`): this is found by the planner before any row is generated, and fails with a message naming the table, the columns, the row count asked for and the room available.

## Foreign keys

- A child row picks a parent **row index** with the `fk` purpose: integer in `[0, parentRows)`. The foreign-key value is then computed from that parent row index the same way the parent computes its own key. Nothing is looked up, so the parent does not have to be generated first.
- **Self-reference:** row `r` picks a parent in `[0, r)`, an earlier row. Row 0 gets NULL. If the column is NOT NULL, row 0 points to itself; if a check forbids that too, the planner fails with a clear message.
- A composite foreign key picks one parent row and copies all of its key columns.

## Why the output cannot depend on ...

| ... this | Because |
|---|---|
| Table order in `forge.yml` | A value depends on table and column NAMES, not positions. Output files are written in a fixed order (tables by plan order with name as tie-break, rows by index). |
| Thread count | There is no shared generator state to race on. Any thread computing cell (table, column, row) gets the same bits. |
| Batch size | A row is computed from its index alone. Batches are only a way to group rows for writing. |
| How many rows are generated | Row 7 is computed the same way whether 10 or 10 million rows exist. The unique index space `N` is fixed by the column, not by the row count. (One exception, see "Limits".) |
| Other tables being added or removed | Other tables have other names, so other column keys. |
| JVM, GC, JIT flags, operating system, CPU | Only SHA-256 and wrapping 64-bit integer arithmetic are used. Both are fixed by a standard. No `hashCode`, no `HashMap` order, no default locale, charset or time zone, no wall clock. |

Banned in generation code (checked by the `determinism-auditor`): `java.util.Random`, `Math.random`, `ThreadLocalRandom`, `SplittableRandom`, `HashMap`/`HashSet` iteration, parallel streams, `Instant.now` and friends, default `Locale`/`ZoneId`/`Charset`, `String.format` without `Locale.ROOT`, `Math` transcendental functions.

## Data files

Name, city and word lists are part of the function: a pick is "index into the list". So:

- Each list has a fixed line order and is loaded in file order.
- Lists are append-only. Adding lines at the end changes some picks (the list got longer); reordering or deleting lines is not allowed without a new data version.
- A test pins each list's SHA-256, so an accidental edit fails the build.

## Limits and failure modes (stated honestly)

| Situation | What happens |
|---|---|
| Rename a table or column | Its values all change. By design: the name is part of the key. |
| Change the seed | Everything changes. |
| Change a parent's row count | The child's foreign-key picks change (the pick is in `[0, parentRows)`), and so do pair enumerations for junction tables. The child's other columns do not change. This is the one place where "row N is stable when rows are added" does not hold, and it holds only across tables: adding rows to a table never changes that table's own earlier rows, including self-references. |
| Change a column's type or constraints | Its index space or range changes, so its values change. |
| Edit a data file | Picks from that list can change. Guarded by the pinned checksum test. |
| Unique column with too little room | Planner error before generation, naming table, column, rows wanted and room available. |
| A check constraint the engine cannot read (only the raw text is known) | The engine cannot promise to satisfy it. `forge init` lists every such check as a warning. Generation still runs; the database decides. No retry-until-valid loop is added. |
| Two columns compared by a check (`starts_at < ends_at`) | The later column is generated as the earlier one plus a positive offset from its own stream. This is the only cross-column dependency, and it stays inside one row. |
| Two different seeds giving the same data | Would need a SHA-256 collision on the first 8 bytes for every column at once. Not a practical concern. |
| Statistical quality | SplitMix64 passes the standard test batteries and is used here as a counter-based mixer. It is not for security and nothing here is secret. |
| A different JVM vendor or CPU family | Covered by design (integer arithmetic only). The P1.4 gate tests different GC and JIT flags on this machine; a second vendor or CPU is NOT tested locally and will be said so in the report. |

## What P1.4 builds from this

| Class (module `gen`) | Job |
|---|---|
| `Keys` | SHA-256 column key from seed, table, column, purpose. |
| `Mix` | SplitMix64 `mix`, `cellStart`, `draw`. |
| `Cell` | The draws of one cell: `nextLong`, `below(n)`, `chance(partsPer10000)`. |
| `Perm` | Feistel shuffle of `[0, N)` with cycle-walking. |
| `Uniq` | Carrier choice and one-to-one encodings (int, base-36 text, UUID, pair). |

## The alternatives, briefly

| Option | Why not chosen |
|---|---|
| SHA-256 for every cell | Simplest to explain and strongest, but about 20 million hashes for a 1M-row, 20-column run. Kept as the fallback if the user prefers simplicity over speed. |
| `java.util.Random` seeded per cell | Its algorithm is fixed by the JDK spec, but nearby seeds give visibly related first outputs. |
| A hash library (xxHash, Murmur3) | Fast, but a dependency or more code to explain, for no gain over SHA-256-once plus SplitMix64. |
| One generator advanced row by row | Row N would depend on all rows before it: no stable rows, no free choice of batch or thread. |

## Additions made in P1.6 (not part of the approved design: for the user to review)

Zero-config on real schemas needed five things the approved design did not cover. Each is pure: a value is still a function of (seed, table, column, row, purpose) and the schema, never of other rows.

1. **Redraws inside one row, for check constraints.** A check the parser cannot turn into simple facts (an equality, `num_nonnulls(a, b) = 1`, `(status = 'x') = (done_at IS NULL)`) is evaluated on the row's own values (class `Eval`, SQL three-valued logic: unknown passes, as in Postgres). If it is false, the row is drawn again with draw attempt 1, 2, ... (attempt n mixes n into the column, null and null-pick keys; attempt 0 is the plain key, so a row that passes first time is exactly what it was before). Up to 5000 attempts, then a `GenError` naming the check. The result depends on that row alone. A check using something `Eval` does not know is a `GenError` at the start, naming it. A column fixed by `col = expression` (not reading `col`) is computed from the others, not redrawn.
2. **Foreign keys that share a column** (composite keys such as `(tenant_id, team_id)` and `(tenant_id, member_id)`): their parent rows are drawn together until the parents agree on the shared column (attempt-salted, one key per foreign key, up to 20,000 attempts). A key whose own columns are all nullable may be NULL, which ties nothing; a self-reference picks an earlier row, row 0 has none.
3. **Unique keys across foreign-key columns.** If the foreign keys inside the key cover it exactly (and share nothing), their parent rows are enumerated as one shuffled number, as designed. Otherwise one foreign key that touches the key through parent columns that identify one parent row each is shuffled over its parent rows on its own: it never repeats, so the key never repeats (needs parent rows at least the child rows). A generated column in a key is carried by the columns it is made from (the database checks it on load); an expression other than `lower(column)` is ignored, which is stricter.
4. **Range partitions.** The partition key is drawn inside the union of the partitions (a gap or overlap is a `GenError`).
5. **A bounded cache** of resolved rows and picks (400,000 entries, then cleared). It only saves time.

**Known gap (the P1.6 gate is red for two goldens because of it).** A unique key across two foreign keys that also share a column (golden `ecommerce.shipment_items (shipment_id, order_item_id)`, `saas-multitenant.team_members (team_id, member_id)`): the carrier foreign key is fixed by the shuffle, and the other must agree on the shared column, but with independent random parents some carrier rows have no matching row. Options, for the user: (a) deal out parent references evenly (every parent gets about the same number of children) instead of independently; (b) build the list of valid combinations once by scanning the parents, shuffle that, and have `forge init` lower `rows` to what fits; (c) accept these two shapes as reported, not generated.
