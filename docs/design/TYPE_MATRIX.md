# TYPE_MATRIX (register B2) — no decision required; one policy point

**Probe results:** the register says unknown types are read as text with a warning. The lab shows otherwise: `forge generate: Table kinds: column b has type bit, which cannot be generated yet.` (lab `pg_types`, one table with about 30 types). It stops at the first unsupported column, so only `bit` is proven unsupported; the others (jsonb, json, tsvector, money, inet, cidr, macaddr, hstore, citext, ltree, int4range, daterange, tstzrange, bit varying, arrays, enum, domain, composite, bytea, interval, time/timetz, point, xml) are unseen. SQLite accepted every declared type as text, so it says nothing about Postgres (G3, G5).

**Plan (no language change):**
1. Make the lab report every unsupported column, not the first (a probe that loops over one-column tables, one table per type). This turns B2 from Unknown into a measured matrix on Postgres 16.
2. For each type choose one outcome: **generate** (cheap, safe values: jsonb `{}`, inet from a documented range, range types from a valid pair, arrays of the element type, enums from their values, domains from the base type plus its check, bytea random bytes), **skip with config** (a column marked `skip: true` is left to its default or NULL when nullable), or **refuse with a message naming the column and the config line to add**.
3. A not-null column of an unsupported type with no default must refuse (never invent).

**Policy point:** whether `skip` is a new config key (yours). The current `columns:` settings may already hold it; check at plan time.

**Dialects:** the matrix is Postgres. MySQL (json, enum, set, year, bit, spatial) and SQLite (declared type is advisory) get their own short matrices after Postgres, using the same probe.

**Recommendation:** do step 1 first (about half a day, no product change), then decide each type from the measured list.

**Decision for you:** only the `skip` key, if it does not exist yet.

## Measured (step 1 done)
`scripts/lab-types.sh` -> [../TYPE_MATRIX_RESULTS.md](../TYPE_MATRIX_RESULTS.md): 44 types, 21 PASS, 23 FAIL, identical for not-null and nullable columns. The cheapest first win needs no language change: leave a NULLABLE unsupported column NULL instead of refusing (23 types become usable on nullable columns). Making not-null columns of `citext`, `time`, `interval`, `money`, ranges, `point`, `xml` generate is a generator change (yours to approve).
