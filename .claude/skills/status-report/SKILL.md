---
name: status-report
description: The 5-line docs/STATUS.md entry format and the PASS/WARN/FAIL table style. Use at the end of every session.
---

# status-report

End every session with a 5-line entry at the top of `docs/STATUS.md`, under `## <date> <what> (branch <name>)`:
- **Done:** what is finished and verified.
- **Not done:** what is left or was skipped.
- **Next:** the next feature (only when the user says so).
- **Decisions:** choices made, and who made them.
- **Open:** open questions and anything not tested.

Also update the "Current state" block in `CLAUDE_PROJECT.md`.

Check tables use one verdict per row: PASS, WARN, FAIL. `scripts/verify.sh` prints PASS, FAIL or SKIPPED; never report SKIPPED as PASS. Say plainly what could not run.
