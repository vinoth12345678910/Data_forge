# UUID_RULES (register A1) — decision needed

**Probe results** (`LimitsProbeTest`, lab `uuid_app`): rules already COMPILE on uuid columns for `=`, `<>`, `is not null`, walking a uuid foreign key, and `limit`/`derive` aggregates grouped by a uuid foreign key. Refused: ordering (`>`), and comparing with a text literal; the message omits the type name (`of another type cannot be compared with >`, G1). Zero-config init, generate and verify PASS on `uuid_app` (uuid keys, Postgres 16/17/18). SQLite: uuid pk/fk plus a `limit` PASSED. **Not reproduced:** the Supabase failure. No Postgres run of a rule over a uuid column exists yet, so the real gap is unknown.

**Options**
1. Opaque key (register's proposal): kind `UUID` in `RuleCheck.kindOf`: `=`, `<>`, `is null`, group, count, follow foreign keys; no ordering, no arithmetic, no literals. Matches what compiles today, so mostly a named type, a clearer error and a three-dialect test.
2. Also allow a uuid literal (`'...'`): needed for rules like `where owner = '0000...'`; adds a literal syntax and a cast per dialect.
3. Do nothing until a Postgres probe shows a real failure.

**Trade-offs:** 1 is small and safe; 2 widens the rule language (yours); 3 risks building for a problem that may already be fixed.

**Dialects:** Postgres native `uuid`; MySQL has no uuid type (CHAR(36) or BINARY(16): equality works on both, but a rule must not mix them); SQLite stores text. Equality and grouping are identical in all three; only ordering and literals differ, which is why they stay out.

**Recommendation:** first run the Postgres matrix (rule over uuid column: check, derive, limit, verify) to find the real failure, then option 1 with the type name in the error. Option 2 only if a pack rule needs it.

**Decision for you:** 1, 1 plus literals, or probe first?
