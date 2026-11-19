# CHECK_CONSTRAINTS (register A4) — no decision required (parser only); one policy point

**Probe results** (`CheckProbeTest`, lab): understood: IN lists, ranges, column-to-column compare, `char_length`. Kept raw (generator ignores them): `num_nonnulls(a,b) = 1`, arithmetic (`price * qty > 0`), `CASE`, regex (`~`), `OR` across columns. `init` reports them ("Check not fully understood"): events-merch 2, ecommerce 6, saas-multitenant 9, Pagila 0. Zero-config generate still PASSED on all of them, so the generator did not break these constraints on that data; that is luck of the default values, not a guarantee.

**Options**
1. Widen `CheckParser` fact by fact (num_nonnulls = exactly-one, simple arithmetic compare, `OR` of null tests, CASE with simple branches). Each new fact gets a generator use and a test.
2. Translate every unparsed check into a generated `check:` rule with the same SQL, verified in the database (reuses the rule engine; no new facts).
3. Fail closed: refuse `generate` when a check is not understood, unless `check_policy: skip` in config.

**Trade-offs:** 1 is the register's plan and gives valid-by-construction data. 2 gives a verify safety net cheaply but cannot make the generator satisfy it. 3 is safest but breaks zero-config on today's goldens (a rule you set: golden schemas stay as they are).

**Dialects:** `CheckParser` reads Postgres text; MySQL/SQLite check text differs (not probed). Regex differs per dialect (`~`, `REGEXP`, none in SQLite), so regex stays raw.

**Recommendation:** 1 for num_nonnulls, arithmetic compare and OR-of-null-tests (the three most common); keep the rest raw but print the `check:` rule to add (the register's fail-closed line), no new refusal. Any config key for a policy is a config change: yours.

**Decision for you:** only whether to add a policy key; otherwise proceed with 1.
