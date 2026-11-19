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
Scaled/exact arithmetic; any rule that needs rounding is refused (message names the rule and what is needed); decimal rules refused on SQLite (verified: it stores numeric as a float). **Built since: `round(expr, n)` (branch `a3-round`).** Explicit only, never implicit. One pinned mode: half away from zero (`RoundingMode.HALF_UP` on `BigDecimal` in the generator; `round(numeric, n)` in Postgres and `ROUND(DECIMAL, n)` in MySQL do the same on exact numbers). `n` is an integer literal from 0 to 30 (30 is MySQL's DECIMAL limit); a column or calculation as `n`, a negative `n`, text and float arguments are refused with a message. The result has exactly `n` decimal places, so `round(sum(qty * price), 1)` fits a `numeric(12,1)` column and a derive whose rounded scale is still wider than the column is refused as before. Compiled SQL: Postgres `round((x)::numeric, n)` (the cast, because Postgres has no `round(bigint, int)` and rounds a `double` half to even); MySQL `round(x, n)`. SQLite refuses any rule that uses `round` (its `round()` returns a binary float, even for a whole number). Float columns are refused because Postgres and MySQL round a float differently. Not built: negative `n`, decimal `/` and `%`, decimal literals (G6), round inside SQL check constraints.

### Edge values, measured (`RoundEdgeContainerTest`, Postgres 16.15 and MySQL 8.4.3)
Plain SQL as the compiled rule writes it, compared numerically with an independent `BigDecimal` HALF_UP and with a hand-written expected column. The same values with every n (0, 1, 2, 5, 18, 30) also pass the verify pass as rules on both databases, and 2,000 generated rows per database (about 10% exact x.5 cases of both signs) pass with a mutation caught.

| case | x | n | expected | postgres 16 | mysql 8.4 |
|---|---|---|---|---|---|
| x.5 up | 2.5 | 0 | 3 | 3 | 3 |
| x.5 up, odd | 1.5 | 0 | 2 | 2 | 2 |
| x.5 near zero | 0.5 | 0 | 1 | 1 | 1 |
| negative x.5 | -2.5 | 0 | -3 | -3 | -3 |
| negative x.5, odd | -1.5 | 0 | -2 | -2 | -2 |
| negative near zero | -0.5 | 0 | -1 | -1 | -1 |
| just below half | 0.4999 | 0 | 0 | 0 | 0 |
| just below half, negative | -0.4999 | 0 | 0 | 0 | 0 |
| float trap | 1.005 | 2 | 1.01 | 1.01 | 1.01 |
| float trap, negative | -1.005 | 2 | -1.01 | -1.01 | -1.01 |
| scale 0 identity | 7 | 0 | 7 | 7 | 7 |
| scale 0 on a decimal | 12.00 | 0 | 12 | 12 | 12 |
| n above the scale | 1.2 | 5 | 1.20000 | 1.20000 | **1.2** |
| large scale, up | 0.1234567890123456785 | 18 | 0.123456789012345679 | 0.123456789012345679 | 0.123456789012345679 |
| large scale, down (negative) | -0.1234567890123456785 | 18 | -0.123456789012345679 | -0.123456789012345679 | -0.123456789012345679 |
| large scale, exact | 1.234567890123456789 | 18 | 1.234567890123456789 | 1.234567890123456789 | 1.234567890123456789 |
| n = 30 | 1.000000000000000000000000000005 | 30 | 1.000000000000000000000000000005 | same | same |
| NULL | NULL | 2 | NULL | NULL | NULL |

Every row agrees numerically. One difference in representation, not value: when `n` is above the argument's scale, Postgres pads (`1.20000`) and MySQL does not (`1.2`). The tests compare with `compareTo`, and a stored derived column always has the column's scale, so nothing is affected.
