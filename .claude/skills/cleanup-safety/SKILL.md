---
name: cleanup-safety
description: The 8 cleanup guards as a checklist and the tests that must exist. SKELETON: the safety design is user-owned; the user writes the tests.
---

# cleanup-safety (skeleton)

**The whole safety design is user-owned.** The user writes the Phase 5 safety tests; Claude only runs and reports them and never edits or grades them. Edits under `modules/cleanup/src/test/` are blocked by a hook.

## The 8 guards (checklist)
1. Environment label (a prod label refuses generation).
2. Dry run within 24 hours.
3. Backup gate: a failed `backup_cmd` aborts.
4. Real-data blocker: a real row pointing at a manifest row blocks cleanup.
5. Count check with rollback: a count mismatch rolls back.
6. Keep list.
7. Truncate only at 100% coverage.
8. Audit log.
Plus: sequence reset, and a manual checklist for what cleanup cannot clean.

## Tests that must exist (the user writes them)
- Hand-inserted real rows are never deleted.
- Prod label refuses generation.
- Failed `backup_cmd` aborts.
- A real row pointing to a manifest row blocks cleanup.
- Count mismatch rolls back.
- After cleanup and readiness, table counts equal the pre-run state and `_dataforge` is gone.

TODO (user to supply): the design details behind each guard.
