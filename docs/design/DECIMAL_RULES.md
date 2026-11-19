# DECIMAL_RULES (register A3) — decision needed

**Probe results:** rules over `numeric(12,2)` COMPILE. Generation refuses: `Rule total: orders.total must be a generated whole-number column to be derived.` and `Rule pr: items.price is a decimal or special type; generation reads whole numbers, text, true/false and times only.` A numeric column with no rule generates and loads. Pagila and the goldens pass zero-config (money is bigint paise in the goldens, so they never hit this).

**Options**
1. Scaled integers: treat `numeric(p,s)` as an integer of value*10^s inside generation; derive, check and limit use exact integer math; write back with the scale. Exact, deterministic, no float anywhere.
2. Java `BigDecimal` in the generator. Exact, but slower at millions of rows and heavier code.
3. Keep refusing, but say so earlier (at `init`, naming the rule to avoid).

**Trade-offs:** 1 fits the generator's whole-number core with the least new code; rules like `price * qty` need care with scale (result scale = sum of scales) and rounding, which is a language decision (yours): round half-even or refuse a rule that would need rounding. 2 is simpler to read, slower. 3 does not solve it.

**Dialects:** Postgres `numeric` and MySQL `DECIMAL` are exact. SQLite has no decimal type (numeric affinity stores floats/ints), so verify on SQLite compares with a tolerance or on `cast(x*100 as integer)`; say so in the docs. The verify SQL must cast to the column scale in each dialect.

**Recommendation:** option 1; rounding rule = refuse any rule whose result scale exceeds the target column's scale (no silent rounding); SQLite verify via integer scaling. Other shapes in A3 (derived text/time, nullable-fk aggregates) one by one afterwards.

**Decision for you:** option 1, and the no-silent-rounding rule?

## Decision (owner, 2026-11-19) and what was built
Scaled/exact arithmetic; any rule that needs rounding is refused (message names the rule and what is needed); decimal rules refused on SQLite (verified: it stores numeric as a float). **Next item, not built:** an explicit `round(expr, n)` with one pinned mode, half away from zero (`RoundingMode.HALF_UP` on BigDecimal; `round()` in Postgres and MySQL are half away from zero for numeric; SQLite not applicable). Rounding is never implicit. Also open: decimal literals (G6).
