---
name: gate-first
description: The build loop for one feature: failing gate test, build until green, commit, checks, status. Use at the start of every build.
---

# gate-first

1. Branch from the latest `main`, named by feature (`p1-schema-reader`). One feature = one branch.
2. Plan mode first. The user approves the plan.
3. Write the gate test first. Run it and show it **failing for the right reason**.
4. Build until it passes. One commit per task. Stay inside the feature's scope and modules.
5. Checks (determinism, memory and speed, untested rules). Fixes only if a check fails, each with a regression test.
6. Run `bash scripts/verify.sh`. Report each step PASS, FAIL or SKIPPED (never SKIPPED as PASS).
7. Write the 5-line `docs/STATUS.md` entry and update "Current state" in `CLAUDE_PROJECT.md` (use the `status-report` skill).
8. Push and open a PR. The user merges. Do not start the next feature until told.

If a gate fails, do not start the next feature. Fix it, add a regression test, then move on.
