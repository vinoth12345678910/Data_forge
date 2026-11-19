# LOGICAL_RELATIONS (register A7) — decision needed (config change)

**Probe result:** `relations:` is not a key: `relations: unknown key. Allowed here: [version, seed, clock, locale, connection, ddl, use, tables, rules, states, scenarios, cleanup, manifest_dir]`. It does not exist, so the "check first" in the register is answered. Apps with no database foreign keys (Prisma `relationMode = "prisma"`, legacy) generate tables with unrelated ids.

**Options**
1. A top-level `relations:` list: `- {from: orders.user_id, to: users.id}` (composite: lists of columns). The reader merges them into the schema graph as foreign keys flagged `logical`.
2. Per-table `columns.<col>.references: users.id`. No new top-level key, but spread out and harder to review.
3. Infer by naming (`user_id` -> `users.id`) in `init` and write the guesses to forge.yml for you to confirm. Convenience on top of 1 or 2, never a replacement.

**Trade-offs:** 1 is explicit and diff-friendly. Logical keys must act like real ones in the planner (parent first, cycles), in verify (a compiled orphan check, since the database does not enforce them), and in cleanup (children found through them). That last point touches Phase 5 safety design (yours): cleanup must either see logical children or refuse tables that have them.

**Dialects:** none differ in generation. Verify compiles `not exists (select 1 from parent ...)` on all three. Drift: a logical relation never appears in the schema, so drift ignores it.

**Recommendation:** option 1 plus option 3 as an `init` suggestion; verify gets an orphan check; cleanup treats a logical child like a foreign-key child (refuses to delete a parent while manifest-foreign children exist) only after you approve the safety side.

**Decision for you:** key name and shape, and whether cleanup honours logical relations.
