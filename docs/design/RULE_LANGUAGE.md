# Rule language (approved by the user 2026-11-11 with changes D1b, D1c)

Status: written in Phase 2 Step 0 for the user to approve, change or reject. Nothing is built from it until approved. Alternatives are in "Decisions" at the end.

## The idea in five sentences

1. A rule is one line of text with a **name** and one of **three keywords**: `derive`, `check` or `limit`.
2. `derive` says "this column is always computed like this". `check` says "every row must satisfy this". `limit` says "the children of one parent may use at most the parent's capacity".
3. A rule names its table by the first column it uses; columns are written `table.column`, and a column of a parent row is reached by walking foreign-key columns: `payments.order_id.total_paise`.
4. Every rule compiles to **one SQL query that returns the violating rows** (zero rows = the rule holds). The verify pass runs that query in the database.
5. The same rule, read as an AST, also tells the generator how to build the value so the rule holds by construction.

## Where rules live

`forge.yml`, key `rules` (see the amendment in `docs/CONFIG.md`):

```yaml
rules:
  - name: order_total_matches_items
    derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)
  - name: succeeded_amount_equals_total
    derive: payments.amount_paise = payments.order_id.total_paise when payments.status = 'succeeded'
  - name: payment_completed_after_created
    check: payments.completed_at >= payments.created_at
  - name: registration_within_ticket_quantity
    limit: sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity
```

A rule name is `[a-z][a-z0-9_]*`, unique in the file. Exactly one of `derive`, `check`, `limit` is given. `description` is optional text.

## The three kinds

| Kind | Shape | Meaning | SPEC name |
|---|---|---|---|
| `derive` | `T.col = expr [when cond]` | `T.col` equals `expr` on every row of `T` where `cond` holds | derived column |
| `check` | `cond [when cond]` | `cond` is true (or unknown, as in SQL CHECK) on every row of the subject table | constraint |
| `limit` | `agg per C.fk <= P.col [when cond]` | for every parent row, `agg` over its children in `C` is at most `P.col` | capacity limit |

- **Subject table:** `derive` has the table of the target column. `check` has the table of its first column; every column in a `check` outside an aggregate must start with that table or walk from it. `limit` has the parent table `P` (the table that `C.fk` points to); the rows being tested are parent rows.
- **`when`:** limits the rows the rule applies to. It may only read the subject table (and parents of it). Rows where `when` is false or unknown are exempt.
- **`limit` aggregates:** `sum(C.col)` or `count(*)`; the child table `C` is the table that owns `C.fk`. `C.fk` is a foreign-key column of `C` that points to the table of `P.col`. Phase 2 sums one plain column, not an expression.

## Expressions

| Part | Phase 2 |
|---|---|
| Columns | `table.col` (own row), `table.fkcol.col` (parent row), `table.fk1.fk2.col` (grandparent). Each path segment must select exactly one foreign key; if two foreign keys contain the column the error lists them. |
| Literals | integers, `'text'`, `true`, `false`, `null` |
| Arithmetic | `+ - * /  %` on integers. `/` is integer division, truncating toward zero, as in Postgres and Java. Overflow is an error, never a wrap. |
| Compare | `= <> < <= > >=` on numbers, timestamps, dates; `= <>` on text and booleans |
| Logic | `and or not`, `is null`, `is not null`, `in ('a','b')`. Three-valued as in SQL: an unknown is not true. |
| Functions | `coalesce(a,b,...)`, `least(...)`, `greatest(...)`, `abs(x)` |
| Conditional | `case when c then a [when c then b] else z end` |
| Aggregates | `sum(e)`, `count(*)`, `min(e)`, `max(e)` over a child table, optional `where cond`. Written `sum(child.col * child.col2)`; the join is the one foreign key from `child` to the subject table. If two exist, write `via child.fkcol`: `sum(refunds.amount_paise via refunds.payment_id)`. Inside an aggregate the subject row is still readable (an "outer reference"): `orders.subtotal_minor`. `sum` of no rows is 0; `min` and `max` of no rows are null. |

**Not in Phase 2 (a clear error says so):** text comparison with `<` or `>` (collation differs by database), text functions, interval and date arithmetic, `now()` or any clock (Phase 3 temporal rules), floating point, grouping by more than one key, recursion (tree depth), `exists`, status flow. Money is integer minor units, so none of this is needed for the golden rules.

## Grammar (ANTLR4, `modules/rules/src/main/antlr/Rules.g4`)

Three entry rules, one per kind. Keywords are case-insensitive; identifiers are lower-case as stored (a quoted `"Name"` is allowed for others).

```antlr
grammar Rules;

deriveRule : colRef '=' expr whenClause? EOF ;
checkRule  : expr whenClause? EOF ;
limitRule  : agg 'per' colRef '<=' colRef whenClause? EOF ;

whenClause : 'when' expr ;

expr
  : expr op=('*' | '/' | '%') expr
  | expr op=('+' | '-') expr
  | expr op=('=' | '<>' | '<' | '<=' | '>' | '>=') expr
  | expr 'is' 'not'? 'null'
  | expr 'not'? 'in' '(' expr (',' expr)* ')'
  | 'not' expr
  | expr 'and' expr
  | expr 'or' expr
  | 'case' ('when' expr 'then' expr)+ 'else' expr 'end'
  | fn=IDENT '(' (expr (',' expr)*)? ')'
  | agg
  | colRef
  | literal
  | '(' expr ')'
  ;

agg    : fn=('sum' | 'count' | 'min' | 'max') '(' ('*' | expr)? ('where' expr)? ('via' colRef)? ')' ;
colRef : IDENT ('.' IDENT)+ ;
literal: INT | STRING | 'true' | 'false' | 'null' ;
```

Precedence is the order written (SQL's). In `limitRule`, `colRef` after `<=` is `P.col` (parent table, plain column).

## From text to a checked rule (module `rules`)

1. **Parse** with ANTLR. Syntax errors come from our own error listener, never the default one that prints to the console.
2. **Build the AST** (`RuleExpr` records, in module `core`, so the generator can read them without depending on `rules`).
3. **Type-check against the `SchemaGraph`:** every table and column exists; every path segment selects one foreign key; types fit the operator (no `text + bigint`); `derive` targets a real, non-generated, non-foreign-key column and no two rules derive the same column; `limit` parent column belongs to the table that `C.fk` references; `when` reads only the subject table.
4. **Dependency check:** build the graph "this column is computed from these columns". A cycle is an error naming every rule in it. Derived and limited columns may not be part of a primary key, a unique key or a foreign key (so a computed value never has to be unique or point anywhere).
5. **Compile to SQL** (next section).

## Compiling to SQL

Every rule becomes a query on the subject table `t`, returning its primary key (all key columns) plus the columns the rule read, so a failure can print the row.

```sql
-- derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)
select t.id, t.total_paise as actual,
       coalesce((select sum(c.quantity * c.unit_price_paise) from order_items c where c.order_id = t.id), 0) as expected
from orders t
where t.total_paise is distinct from
      coalesce((select sum(c.quantity * c.unit_price_paise) from order_items c where c.order_id = t.id), 0)

-- check: payments.completed_at >= payments.created_at     (unknown passes, like CHECK)
select t.id, t.completed_at, t.created_at from payments t
where (t.completed_at >= t.created_at) is false

-- limit: sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity
select t.id, t.quantity as cap,
       (select coalesce(sum(c.quantity), 0) from registrations c where c.ticket_type_id = t.id) as used
from ticket_types t
where (select coalesce(sum(c.quantity), 0) from registrations c where c.ticket_type_id = t.id) > t.quantity
```

- `when cond` is added as `and (cond) is true` on `derive` and `limit`, and `where (cond) is true and ((expr) is false)` on `check`.
- A parent column is a join in a scalar subquery on the foreign key: `(select p.total_paise from orders p where p.id = t.order_id)`.
- Composite foreign keys join on every column of the key.
- The count query is `select count(*) from (<query>) v`; the sample query adds `order by <pk> limit n` (a fixed order, so reports are stable). A table with no primary key is not allowed in a rule (error).
- A `derive` and a `check` compare with `is distinct from` and `is false` so a null where a value is expected is a violation, while an unknown in a check passes, as SQL CHECK does.
- Only standard SQL is used, no Postgres-only functions, so the same text can later run on MySQL and SQLite (Phase 3).

## Applying a rule during generation (module `gen`)

Rows stay pure functions of (seed, table, column, row) as in `SEEDED_RANDOM.md`. Rules add no randomness and no dependence on other rows' random state. They add two things: a **count/accumulate pre-pass** and a **column plan**.

1. **Plan.** From the rule AST, each ruled column gets a role: `derived` (computed, never drawn), `clamped` (drawn inside a bound the rule gives), `redrawn` (drawn, then tested, drawn again), or `lifted` (a capacity made large enough).
2. **Pre-pass.** For every aggregate, one pass over the child table computes, for each parent row index, the count or sum. It generates only the cells the aggregate reads and the foreign key's parent pick (cheap, pure), and streams: it keeps ONE aggregate value per parent key (one array entry per parent row, 8 bytes), never the child rows. The passes run in dependency order: a child's sum can read a column that another rule derived.
3. **Row.** When `RowGen` builds a row it asks the plan. A `derived` cell is evaluated from the row's other cells, parent cells (recomputed by the same pure function) and the accumulator arrays. A `clamped` cell is drawn by the normal generator with its range narrowed by the rule. A `redrawn` cell uses the Phase 1 attempt-salted redraw (same limit, same error).
4. **After computing**, the final row is tested against the table's own check constraints (Phase 1 `Eval`). A derived value that breaks a check is a `GenError` naming the rule, the table, the row and the check. A derived value is never redrawn.

How each kind is applied:

| Kind | Applied as |
|---|---|
| `derive` | the column is computed. With `when`, rows where `when` is false keep the normally generated value. |
| `check` with shape `col <= expr`, `<`, `>=`, `>` (the column is on one side, alone, and the other side does not read it) | `clamped`: the column's allowed range is narrowed to the bound the other side gives on this row. If the narrowed range is empty, `GenError` naming the rule and row. |
| any other `check` | `redrawn`: whole-row redraw, up to the Phase 1 limit, then `GenError` naming the rule and row. |
| `limit` on `sum` or `count` | see "Capacity limits" below. |

### Capacity limits (D1c, approved as option 2)

The pre-pass gives, for parent row `p`, `n(p)` = how many children point at it and `lo(p)` = the lowest value a child of `p` may take in the limited column (1 for `quantity > 0`; for a child that is itself a capacity, its own lower bound, see below). One `long` per parent key for each; never the child rows.

- **Children shrink.** Each child value is drawn at most `floor(cap(p) / n(p))` (the even share). Capacities stay ordinary draws and stay believable.
- **A cap is raised only when counts force it:** `cap(p) = max(drawn cap, n(p) * lo(p))`. So a cap is never raised just to cover usage, only when even the smallest children (each at least `lo`) cannot fit. For `count(*)` limits each child counts 1, so `cap(p) = max(drawn cap, n(p))`.
- **Chains** (registrations -> ticket type quantity -> event capacity): a column that is both a cap and a limited child has its own lower bound `lo` = `max(own smallest value, n(children) * their lo)`; the event capacity is raised to `n * max(lo)` over its ticket types, and each ticket type quantity is then drawn between its `lo` and its even share. The pre-passes run in dependency order.
- **A cap that another rule computes** (for example `payments.amount_paise`) cannot be raised: if its value is below `n(p) * lo(p)`, `GenError` naming the rule, parent row, cap and child count. A NULL cap means "no limit": nothing is applied for that parent.

## Error messages with position

Format, one rule per message, 1-based line and column inside the rule text:

```
rules[2] order_total_matches_items (derive), 1:32: unknown column orders.totl (did you mean total_paise?)
  orders.totl = sum(order_items.quantity * order_items.unit_price_paise)
  ^
```

Every error has: the rule list position, the rule name, the kind, `line:col`, one sentence, and the rule text with a caret. "Did you mean" uses edit distance over the table's columns (error text only; not generation code). A runtime failure names the rule and the row, for example `rule order_total_matches_items violated by orders id=17: total_paise is 500, the rule gives 800`.

## Why this is strong enough for the golden rules (honest)

Written for `events-merch` and `ecommerce`, the Phase 2 language covers the 6 + 9 rules classed (a) in `PHASE2_RULE_SCOPE.md`. It does not cover status flow, time order against a parent, group-by-two-keys, recursion or allocation across sibling rows; those stay out and are listed there as deferred, not failed.

## Decisions for the user

**D1a. How kinds are written.**
1. **Keyword per kind (`derive:` / `check:` / `limit:`), recommended.** Explicit; the parser needs no guessing; easy to explain.
2. One `expr:` key, kind inferred from shape (as SPEC section 7 shows). Shortest to write, but the same text can mean different kinds depending on small changes, and error messages get vaguer.
3. Raw SQL rules. Strongest, but a SQL string tells the generator nothing, so no "correct by construction".

**D1b. How rules are applied during generation.** APPROVED: option 1; the pre-pass streams and keeps one aggregate per parent key. The Phase 1 promise changes: see `SEEDED_RANDOM.md`, "Amendment for Phase 2".
1. **Count/accumulate pre-pass, then pure cells (above), recommended.** Keeps rows pure and memory at one array per aggregate. Cost: one extra pass over each child table that feeds an aggregate.
2. Generate rows into memory and fix them afterwards. Simple, but memory grows with row count (breaks the 1M-row bound) and rows stop being pure.
3. Load, then `UPDATE` derived columns with SQL. Trivially correct in Postgres, but the generator's own output (and later SQL/CSV output) is not correct by construction, which is the Phase 2 promise.

**D1c. Capacity limits.** APPROVED: option 2 (shrink the children; raise a cap only when counts force it). Options as proposed:
1. Lift a free cap; share a pinned cap evenly. Never fails on a free cap, so the gate on generated data is robust. Cost: capacities in generated data are not independent draws (they are at least what is used).
2. **(chosen, with the refinement above)** Shrink the children for `sum`, lift only for `count`. Capacity stays an independent draw, but a small cap with many children is a generation error that depends on row counts.
3. Shrink only; `count` limits deferred. Smallest engine, drops `coupon_total_uses_within_limit` from scope.

## As built in P2.5 (generation)

- Code: `Ruled` (the engine, with its own evaluator of the rule tree) in `gen`; `RowGen.of(graph, config, rules)` takes the checked rules as data. With no rules `RowGen` behaves exactly as in Phase 1 (pinned by `RulesOffIdenticalTest` and `RulesOffGoldenContainerTest`).
- Supported: `derive` of whole-number columns (with `when`; aggregates over a child table with outer references; parent paths); `check` as a clamp when it has the shape `own_column <op> expr` on a whole-number, date or timestamp column, otherwise by redraw (the Phase 1 attempts, up to 5,000); `limit` as designed in D1c (children shrink to an even share, a cap is raised only when counts force it, a derived cap is never raised).
- Not supported yet, each a clear `GenError` at the start or at the row: derive of text, decimal or time columns; a rule reading decimal (`numeric`) columns; a `when` on a `limit`; a rule that sets a key, sequence, shuffled or comparison-tied column; an aggregate or limit whose child foreign key is nullable while the child table is redrawn by checks; a column derived by one rule and also limited.
- A parent's key column is read directly (not through the whole parent row) when the parent has rules, so a parent waiting for its children never asks for itself.
- Measured on ecommerce (rules on vs off): 20,000 rows per table, 2.3 s vs 2.1 s, heap about 120 MB vs 85 MB (a rough in-process check, not a JFR profile).
