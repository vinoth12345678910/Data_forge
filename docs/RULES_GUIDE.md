# Rules guide

A rule is one entry under `rules:` in `forge.yml` with a **name** and one keyword. Every rule compiles to one SQL query that returns the violating rows (zero rows means the rule holds); `forge verify` runs that query in the database. The full grammar and error format are in [docs/design/RULE_LANGUAGE.md](design/RULE_LANGUAGE.md). These examples are from the golden events configuration (`golden/events-merch/phase3.forge.yml`), which the test suite generates and verifies.

| Keyword | Meaning | Example |
|---|---|---|
| `derive` | a column is always computed like this | `derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)` |
| `check` | every row must satisfy this | `check: payments.completed_at >= payments.created_at` |
| `limit` | the children of one parent may use at most the parent's capacity | `limit: sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity` |
| `before` / `after` | time order, with an optional gap range `by:` | `before: registrations.created_at < registrations.ticket_type_id.event_id.starts_at` and `by: 1h..30d` |
| `verify` | like `check`, but run only by the verify pass; the generator ignores it | `verify: refunds.payment_id.status = 'succeeded'` |

```yaml
rules:
  - name: order_total_matches_items
    derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)
  - name: registration_before_event
    before: registrations.created_at < registrations.ticket_type_id.event_id.starts_at
    by: 1h..30d
  - name: referrer_joined_first
    after: users.created_at >= users.referred_by.created_at when users.referred_by is not null
    by: 0s..90d
```

- Columns are written `table.column`. A column of a parent row is reached by walking foreign-key columns: `payments.order_id.total_paise`.
- `when <condition>` limits a rule to the rows where the condition holds.
- A rule that cannot be read is rejected with an error that names the rule. A rule that Data Forge cannot check is listed by `forge verify` as not checked, never as passed.
- Rule names are letters, digits and underscores.

## State machines

A `states:` block per table gives a mix of statuses, the allowed transitions, and the child rows each status needs:

```yaml
states:
  orders:
    column: status
    start: pending
    mix: { pending: 20, paid: 30, fulfilled: 25, cancelled: 10, refunded: 15 }
    transitions: [pending -> paid, paid -> fulfilled, pending -> cancelled, paid -> refunded, fulfilled -> refunded]
```

`forge verify` also runs the rules the state machines imply (the status is valid; child counts per parent state). See [docs/design/STATE_MACHINES.md](design/STATE_MACHINES.md).

## Scenarios

Scenarios are named in `forge.yml` and change the row counts and the mix on demand (`forge generate -s sale_rush`). The events example (`golden/events-merch/phase3.forge.yml`) defines `default` and `sale_rush` ([docs/design/SHAPE_AND_SCENARIOS.md](design/SHAPE_AND_SCENARIOS.md) describes the idea; some scenario names in that document, such as `event-day-rush`, belong to an older draft config the engine does not read).

## Writing your own

1. Run `forge init`; it drafts `forge.yml` with `rules: []` (no rules).
2. Replace `rules: []` with one rule, run `forge generate` on an empty database, then `forge verify`.
3. To see a failure on purpose, edit one row so it breaks the rule and run `forge verify`: it names the rule, the table and the row.
4. Rules that need a time gap can fail when a parent's time leaves no room for it; the error says so.
