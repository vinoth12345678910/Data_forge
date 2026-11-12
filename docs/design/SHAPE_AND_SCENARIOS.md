# Shape, scenarios and edge cases (Phase 3, decision 3)

**TAKEN** (user delegated; open to change). All of it stays a pure function of (seed, table, column, row, purpose); only integer arithmetic. This amends the seeded-random promise by adding **two more purposes** (`shape`, `edge`) and nothing else; Phase 1 and 2 streams are untouched, so rules-off, shape-off data stays byte-identical.

## Skewed distributions

`gen: int` and `money_minor`, and the foreign-key pick, gain `dist: zipf | pareto | uniform` (default `uniform`, today's behaviour).
- **Zipf(s)** over `n` ranks, `s` in `1..3` written as a whole-number percent (`s: 120` = 1.2). The cell takes one draw `u` in `[0, 2^32)` and walks a precomputed cumulative table of integer weights `floor(2^32 / rank^s)` per column (`StrictMath.pow`, rounded once, table built at start, `n <= 100000`; bigger `n` is bucketed). Pure.
- **Pareto(alpha, min)**: inverse CDF `min / u^(1/alpha)` evaluated with `StrictMath.pow` on `u = (draw + 1) / 2^53`, then rounded to a long and clamped to the column range.
- For a foreign key, rank 1 is the parent that the Phase 1 deal would give first (a popular customer). Deal stays when `dist` is absent.
- Tolerance in tests: stated per test (a fixed seed and sample size; the share of the top rank within 3 points of the theoretical share; Pareto median within 5%).

## Correlations

`correlate: { country: [currency, phone_code] }` on a table lists columns driven by one **leader** column. The follower is a function of the leader value and a fixed bundled table (`country -> currency, phone code, tz`; append-only, checksum pinned like the name lists). Followers never draw their own value, so they cannot contradict the leader. Verified by a query on the same table in a test (not a rule kind; the bundled table is the source of truth).

## Seasonality

`season: { column: placed_at, by: month, weights: [8,7,8,9,10,12,9,8,9,11,14,20] }` (or `by: weekday`, `by: hour`). The timestamp is drawn by picking a bucket with the weights (integer cut over one draw) and then uniformly inside the bucket, all in UTC epoch seconds, never the default zone. Verified in the gate by binning counts.

## Scenarios

```yaml
scenarios:
  default: {}
  black_friday:
    extends: default
    rows: { orders: 5x }                 # multiplier or number
    mix: { orders.status: { paid: 60, pending: 25, cancelled: 15 } }   # mix sliders override a machine's mix
    burst: { column: orders.placed_at, from: "2025-11-28T00:00:00Z", to: "2025-11-29T00:00:00Z", share: 40 }
```
- `extends` chains merge keys, the child wins; a cycle is an error.
- `mix` sliders replace the weights of a machine, nothing else; they must name a table with a machine.
- `burst`: `share` percent of the rows of the column land in the window (dealt by row quota, so exact), the rest follow the normal shape.
- `forge generate -s <name>` picks one; with no `-s`, `default`. The scenario is part of the generation input: same (seed, scenario) is byte-identical.

## Edge-case mode

`forge generate --edge` (or `edge: true` in a scenario). Per column, a fixed share (default 2%) of rows take an **edge kind**, dealt by row quota from the `edge` stream: NULL (nullable columns only), max length (text columns at the declared length), unicode (combining marks, RTL, emoji, in valid UTF-8, within the length), case-only duplicate (the value of an earlier row with the case flipped, only on columns whose uniqueness is case-sensitive or none), DST (a timestamp one hour either side of a DST transition in a zone such as Asia/Kolkata-free UTC offsets: given as UTC instants of the real transition, so the loader sees them as ordinary values), leap day (Feb 29 of a leap year), injection strings (`'; drop table x;--`, kept inert because every load path binds or quotes). Edge values must still satisfy every check, unique and foreign key; a kind that cannot fit a column is skipped for it. The report lists the count per kind.

## What the gates prove

Shape: fixed seeds and sample sizes, stated tolerance. Scenarios: each declared golden scenario generates and passes every (a) rule; the burst window shows in a binned count. Edge: loads with zero errors and every kind present.
