Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging. Rule used: where a default can be made stricter it may be; it is never made weaker. Where two readings exist, the refusing one wins.

# P5.3 decisions (the guards on `cleanup --run`, audit log; no delete code)

Taken by the builder session under the delegation, from the P5.3 planner's 13 ambiguities. Open to the owner's veto.

**Finding that shapes P5.3:** no frozen safety test can turn green in P5.3. Every refusal test (GuardsTest B2 to B9, AuditLogTest F1 to F3, and the others) starts with a control that needs a successful `--run` (a real delete, `state=cleaned`). Those stay RED until P5.4 and `safety-green.txt` gets no new line in P5.3. P5.3 has its own non-frozen gate tests of the refused paths, written red-first by the test-first agent in `modules/testkit` (package `forge.guards`).

1. **Gate tests**: new, in `modules/testkit/src/test/java/forge/guards/`, written by the test-first agent, RED first. They are not the owner's safety tests; the frozen ones stay frozen.
2. **Backup**: not executed in P5.3 (a backup with no delete after it is a pointless side effect and would show `g3: pass` for a run that deleted nothing). `Backup` is built and tested directly (exit 0, non-zero, timeout with process-group kill, log mode 600, masking) but wired to a delete only in P5.4. `backup_not_configured` IS checked in P5.3 before `run_not_built` (cheap, stricter).
3. **A `--run` that passes every guard** exits 1 with code `run_not_built` ("The guards passed, but deleting is not built yet (P5.4). Nothing was deleted."), never exit 0 and never `not_built` (that code means the whole command is missing). `data.deleted` is 0.
4. **Audit outcome** for it: `refused:run_not_built`. G3 (executed), G5 and G7 are `not_run`.
5. **Order**: resolve the run; write the audit intent (file only on a prod refusal, as in dry-run); prod label; typed confirmation (database name first, then rows); manifest integrity; dry-run freshness (G2); re-scan (G4, G6); `backup_not_configured`. A refusal never echoes the real database name or the real total.
6. **Typed rows**: exact `^[0-9]+$` equal to the manifest total of the tables that would be deleted from (keep tables excluded); `4,820`, ` 5`, `+5`, `05` refuse. A `cleaned` run gives `already_clean` only after G1 passes.
7. **Dry-run lookup**: latest `dry_runs` row for this run by `made_at` then `dry_id`; if dry runs exist only for another manifest: `dry_run_other_manifest`.
8. **Freshness**: age <= 24 h on the database clock; a `made_at` in the future is `dry_run_stale`. The stored digest must equal the current digest.
9. **Re-scan**: `--run` reuses the dry-run scan code (shared package-private method, not copied) and refuses with the first finding's code.
10. **Backup timeout**: `backup_timeout_minutes` converted to milliseconds rounding up; the whole process tree is killed; a killed backup is `backup_failed`.
11. **Secret masking** is best effort for printing only (P5.4).
12. **Audit write order**: file first, then table; an orphan intent line after a table failure is accepted.
13. **State `running` / `partial`**: `run_not_loaded` until P5.4 designs progress records.
