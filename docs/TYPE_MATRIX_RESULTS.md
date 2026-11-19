# Postgres type matrix (probe)

Written by `scripts/lab-types.sh` (2026-11-19), Postgres 16, zero-config `forge init` + `forge generate`, one table per type, `v <type> not null` (the strict case) and `v <type>` (nullable). Probe, not a gate.

| Type | not null | nullable | Exact message (not null case) |
|---|---|---|---|
| `jsonb`  |  PASS | PASS | - |
| `json`  |  PASS | PASS | - |
| `tsvector`  |  PASS | PASS | - |
| `tsquery`  |  FAIL | FAIL | forge generate: Table t: column v has type tsquery, which cannot be generated yet. |
| `money`  |  FAIL | FAIL | forge generate: Table t: column v has type money, which cannot be generated yet. |
| `inet`  |  PASS | PASS | - |
| `cidr`  |  PASS | PASS | - |
| `macaddr`  |  FAIL | FAIL | forge generate: Table t: column v has type macaddr, which cannot be generated yet. |
| `hstore`  |  FAIL | FAIL | forge generate: Table t: column v has type hstore, which cannot be generated yet. |
| `citext`  |  FAIL | FAIL | forge generate: Table t: column v has type citext, which cannot be generated yet. |
| `ltree`  |  FAIL | FAIL | forge generate: Table t: column v has type ltree, which cannot be generated yet. |
| `int4range`  |  FAIL | FAIL | forge generate: Table t: column v has type int4range, which cannot be generated yet. |
| `int8range`  |  FAIL | FAIL | forge generate: Table t: column v has type int8range, which cannot be generated yet. |
| `numrange`  |  FAIL | FAIL | forge generate: Table t: column v has type numrange, which cannot be generated yet. |
| `daterange`  |  FAIL | FAIL | forge generate: Table t: column v has type daterange, which cannot be generated yet. |
| `tstzrange`  |  FAIL | FAIL | forge generate: Table t: column v has type tstzrange, which cannot be generated yet. |
| `bit(8)`  |  FAIL | FAIL | forge generate: Table t: column v has type bit, which cannot be generated yet. |
| `bit varying(16)`  |  FAIL | FAIL | forge generate: Table t: column v has type varbit, which cannot be generated yet. |
| `int[]`  |  PASS | PASS | - |
| `text[]`  |  PASS | PASS | - |
| `uuid[]`  |  PASS | PASS | - |
| `mood`  |  PASS | PASS | - |
| `mood[]`  |  PASS | PASS | - |
| `posint`  |  PASS | PASS | - |
| `pair`  |  FAIL | FAIL | forge generate: Table t: column v has type pair, which cannot be generated yet. |
| `uuid`  |  PASS | PASS | - |
| `bytea`  |  PASS | PASS | - |
| `interval`  |  FAIL | FAIL | forge generate: Table t: column v has type interval, which cannot be generated yet. |
| `time`  |  FAIL | FAIL | forge generate: Table t: column v has type time, which cannot be generated yet. |
| `timetz`  |  FAIL | FAIL | forge generate: Table t: column v has type timetz, which cannot be generated yet. |
| `point`  |  FAIL | FAIL | forge generate: Table t: column v has type point, which cannot be generated yet. |
| `box`  |  FAIL | FAIL | forge generate: Table t: column v has type box, which cannot be generated yet. |
| `xml`  |  FAIL | FAIL | forge generate: Table t: column v has type xml, which cannot be generated yet. |
| `numeric(12,2)`  |  PASS | PASS | - |
| `float8`  |  PASS | PASS | - |
| `real`  |  PASS | PASS | - |
| `smallint`  |  PASS | PASS | - |
| `date`  |  PASS | PASS | - |
| `boolean`  |  PASS | PASS | - |
| `char(3)`  |  PASS | PASS | - |
| `varchar(20)`  |  PASS | PASS | - |
| `oid`  |  FAIL | FAIL | forge generate: Table t: column v has type oid, which cannot be generated yet. |
| `pg_lsn`  |  FAIL | FAIL | forge generate: Table t: column v has type pg_lsn, which cannot be generated yet. |
| `txid_snapshot`  |  FAIL | FAIL | forge generate: Table t: column v has type txid_snapshot, which cannot be generated yet. |
