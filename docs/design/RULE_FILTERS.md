# RULE_FILTERS (register A2) — decision needed

**Probe results:** `limit` already accepts `[when cond]` (RULE_LANGUAGE.md). `when` reading the PARENT table compiles. `when` reading the CHILD table is refused: `this rule is about slots, but bookings.status starts at another table; reach a parent through a foreign key column...`. So the gap is a child-side filter ("only confirmed bookings count"), not a missing `when`.

**Options**
1. `where` inside the aggregate: `sum(bookings.qty where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity`. Same form as `fill` in PHASE3_LANGUAGE_CHANGES.md, so it serves A5 too.
2. Allow child columns in the limit's `when`. Reuses the keyword, but `when` then means two things (parent exemption and child filter).
3. Filter only in verify, never in generation: cheap, but generator and verifier disagree; breaks "verify never trusts the generator".

**Trade-offs:** 1 is clearest and matches the existing design draft; it needs generator support (children outside the filter must not consume capacity) and a compile per dialect (`sum(...) filter (where ...)` on Postgres/SQLite 3.30+; `sum(case when ... then ... end)` works on all three, so compile to `case`). 2 is smaller but muddier. 3 is unsafe.

**Dialects:** compile to `sum(case when <cond> then col else 0 end)` and `sum(case when <cond> then 1 else 0 end)` for `count`: identical on all three.

**Recommendation:** option 1, compiled as `case`. Build after you approve the grammar line.

**Decision for you:** approve `agg(col where cond)` syntax?

## Decision (owner, 2026-11-19) and what was built
Option 1, with conditions: unfiltered and rules-off output byte-identical (determinism tests green before and after); a filter may not use a column that depends on the aggregate it filters (refused with a message); pre-pass cost measured. Built as `sum(c.col where cond)` / `count(* where cond)` in a limit. Instead of reordering the draw, the filter reads the plain drawn value of its columns, so the columns it may read are restricted (see the register A2 row). Compiled to `... and (cond) is true` in verify.
