# State machines (Phase 3, decision 1)

Status: written in Phase 3 Step 0. The user said "don't ask me anything, finish Phase 3", so the recommended option was taken for each of the five Phase 3 decisions. Each is open to change on review; the choices are marked **TAKEN**.

## The idea in five sentences

1. A table with a status column gets a **machine**: the allowed statuses, a target mix (whole-number weights), and the allowed transitions.
2. Each row's final status is dealt from the mix, so the mix is exact to within one row per status, not random.
3. The row's history is the shortest path of transitions from `start` to its final status; each step gets a timestamp, so status flow and time order agree.
4. A status can require **child rows** (a paid order has exactly one succeeded payment). The child rows are laid out from the parent: the parent decides how many children it has and what status each child gets.
5. Verification is ordinary rules: the machine compiles to checks that run as SQL in the database; it never reads the generator.

## Syntax (`states:` in forge.yml; was reserved in Phase 1)

```yaml
states:
  orders:                         # table
    column: status                # the status column (text, with a check IN list)
    start: pending
    mix: { pending: 20, paid: 30, fulfilled: 25, cancelled: 10, refunded: 15 }   # weights, whole numbers, sum > 0
    transitions:                  # allowed moves; used for the path and verified
      - pending -> paid
      - paid -> fulfilled
      - pending -> cancelled
      - paid -> refunded
      - fulfilled -> refunded
    at:                           # optional timestamps: the first column is set at start, the last column at the final transition
      start: placed_at
      last: updated_at
    children:                     # rows each state needs
      payments:                   # child table
        fk: order_id              # its foreign key to this table
        status: status            # the child's status column (set from the list below)
        by_state:                 # the child statuses created for a parent in that state, in time order
          pending:   []
          paid:      [succeeded]
          fulfilled: [succeeded]
          refunded:  [succeeded]
          cancelled: [failed]
        latest: latest_payment_id # optional: parent column that points at the last child
```

Rules of the format:
- `by_state` lists every state of the machine (a missing state is an error). An entry is a list of child statuses, in time order; `[]` means no child. An entry `[failed*, succeeded]` means "zero to two `failed`, then `succeeded`" (a `*` after a status repeats it 0 to 2 times, dealt from the row).
- A child table is laid out **entirely** from its parent: its row count comes from the layout, and a `rows:` entry for it is an error naming the layout (the forge.yml says "rows: derived").
- Several machines may name the same child table through different parents only if the child table has one layout owner; two owners is an error.
- A machine on a child table (payments: initiated, succeeded, failed) is written the same way, with its `mix` replaced by the statuses the parent layout hands out (`mix` is then forbidden on that table: the status comes from the slot).
- Unknown key, unknown status, a transition to an undeclared status, or a state with no path from `start` is an error with the path in forge.yml.

## How status is assigned during generation (**TAKEN: dealt quotas plus layout**)

| Step | How |
|---|---|
| Final status of row r | `Perm(key("table","status","deal"), N).at(r)` gives a shuffled slot in `[0, N)`; the slot is cut by the cumulative weights. Exact quotas. Pure function of (seed, table, r, N). |
| Path | breadth-first, in the order transitions are written, from `start` to the final status. Pure, no draws. |
| Timestamps | step i of k lands at `start + (end - start) * i / k` plus a small draw; `start` is the existing generated value of the `start` column, and `end` is capped at the `clock`. A row in `start` has `last = start`. Clamped so no column passes the clock. |
| Child rows | A pre-pass over the parent table fills one `long` per parent: the running total of children. Child row j finds its parent by binary search over those totals and its slot by subtraction. Memory: 8 bytes per parent row, never the child rows. |
| Child status and fk | The child's `fk` and `status` come from the slot, not from the Phase 2 dealt pick. All other child columns are generated as before. |
| `latest` | The parent's pointer column is the key of the child at its last slot. |

Rows stay pure functions of (seed, table, row, N) plus the pre-pass totals, as in the Phase 2 amendment: no rule adds randomness from another row.

## How it is verified (own SQL, never the generator)

Compiled to rules, run by the same verify pass as Phase 2:
1. **Status valid**: the column is in the machine's states (also a db check in the goldens).
2. **Required children**: for every state, the child counts by status match the `by_state` list (`paid` order: exactly one succeeded payment; counts of other statuses are checked for the fixed ones only, so `failed*` stays free).
3. **Time order along the path**: each timestamp column is not after its successor, and not after the clock.
4. **No transition outside the machine** can be seen in a snapshot, so only the end state and the child shape are verified. This is stated in the report.

## Gate for P3.1

On generated events-merch and ecommerce: every state machine rule passes under our verify pass and the golden `violations_sql`; every status appears; the mix is within 1 percentage point at 10,000 rows; same seed twice is byte-identical, and also when the table order in forge.yml is shuffled.

## Alternatives

| Option | Why not |
|---|---|
| Random pick from the check list (Phase 2) | no flow, no children |
| Generate in memory, then repair | memory grows, rows stop being pure |
| Simulate event by event | strongest realism, but order dependent: row N depends on rows before it |

## As built in P3.1 (differences from the text above)

- Code: `States` (layouts and dealt statuses) and the hooks in `RowGen` (`Col.ov`, `Col.lay`) in `gen`; `StateRules` in `rules`; `Config.StateConf` and `ChildConf` in `core`. A new rule kind `verify:` (same grammar as `check`) is run only by the verify pass; the generator ignores it. `forge verify` also runs the rules `StateRules` derives from `states` (status valid; child counts per parent state).
- The `rows` entry of a layout child table is **ignored** (the layout decides); it is not an error, so the file `forge init` writes still loads.
- Status along the path: only the final status is assigned. The parent's `at.start`/`at.last` timestamps are **not** built in P3.1; they come with the P3.2 time rules. A child's `at` (`column`, `from`, `gap`) is built: child time = parent time + slot * gap + 1..gap seconds, capped at the clock, so siblings rise with the slot.
- `?` (0 or 1) was added next to `*` (0 to 2). The `by:` path (a child laid out by a grandparent's status) is **not built**: `reservation_status_matches_order` stays out of P3.1.
- A language limit found: a `verify`/`check` that has only aggregates and no own column fails with "a check must read at least one column"; the golden file adds `orders.id is not null and (...)`.
- Only single-column foreign keys can be laid out.

## Suggested by init (Wave 3, C4)

`forge init` notices status-like columns (`StateHints` in `schema`) and prints a note that names them and the `states:` entry to add. It never writes the entry: mix, start and transitions are your design.
