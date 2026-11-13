# Handoff for P5.5 and P5.6 (read this first, then `CLAUDE.md` and `CLAUDE_RULE.md`; do not re-read the design docs unless you change the code they describe)

Written 2026-11-13 by the session that built P5.1 to P5.4. The owner starts P5.5 and P5.6 in a fresh session to save tokens.

## Owner orders (they override the old "stop after each feature" rule for Phase 5)
- Run P5.5 then P5.6 back to back, no waiting for the owner. **Stacked branches**: each branch is cut from the previous one and each PR targets the previous branch. **Never merge any PR. Never push to main.**
- Stop only if: Docker is unreachable; a frozen safety test is wrong or impossible (write it in `docs/STATUS.md`, never edit it); a gate fails and cannot be fixed inside the rules; a hard rule is at risk.
- Per feature, in order: tests RED first, build, safety-reviewer on the cleanup diff (the commit hook forces it), mutation check (commit first, break, run, restore with `git checkout`), full `bash scripts/verify.sh`, a 5-line STATUS.md entry, the PR.
- **Usage-saving orders**: no Monitor or polling loops (run one long command that writes a short summary file, read only that); print only summary lines from Gradle and tests; **few, large commits under `modules/cleanup`** so the safety-reviewer hook runs once per feature after tests are green; run determinism-auditor, scope-guard, dialect-specialist and perf-profiler **once at the end of the phase** (not per feature); do not use planner/test-first agents; write code directly; short messages ("what changed, what is red, what is next").
- Safety design is delegated to the defaults of the Phase 5 prompt: **where a default can be made stricter it may be, never weaker; where two readings exist the refusing one wins.** Record each decision you take in a `docs/design/P5_5_DECISIONS.md` for the owner's veto (same style as `P5_2_DECISIONS.md`, `P5_3_DECISIONS.md`).

## Branch and PR state
| Branch | From | PR | State |
|---|---|---|---|
| `p5-design` | main | #45 | MERGED into main |
| `p5-ownership` (P5.1) | p5-design | #46 | open, verify.sh all PASS |
| `p5-classify` (P5.2) | p5-ownership | #47 | open, verify.sh all PASS |
| `p5-guards` (P5.3) | p5-classify | #48 | open, verify.sh all PASS |
| `p5-run` (P5.4) | p5-guards | not yet | **see "P5.4 close-out" below** |
| `p5-purge` (P5.5) | p5-run | | cut it from `p5-run` once P5.4 is committed |
| P5.6 | P5.5 branch | | cut from the P5.5 branch |

## P5.4 close-out (check `git log` and `git status` on `p5-run` first)
P5.4 (cleanup --run that deletes) is built and green but may still be uncommitted. If `git status` shows modified files under `modules/cleanup`, finish it before P5.5:
1. Everything lives in the working tree/index of `p5-run`: `Delete.java` (the only class with delete/update/truncate SQL), `Guards.java` (G1 to G8 plus `execute`, the lock, backup, batches, final check, `halt`/`restore`), `Own.java` (`Plan`, `Slice`), `Dry.java` (partial runs, truncate method, cycle problems, `already_clean` exit 0), `Classify.java` (warning `other_triggers_or_rules`), `Backup.java` (tree kill loop, masking), `Resolve.java`, `Typed.java` (total counts every manifest table), `Request.java` (`resume`), `Found.java`; CLI `CleanupCmd.java` (`--resume` implies `--run`).
2. Run the new tests (`forge.guards.RunFixesTest`), the container tests and the frozen suite (command below). Expected: only the P5.5 reds and the 2 impossible MySQL cases fail.
3. Mutation checks H1 (remove the `lock table ... access exclusive` in `Delete.truncate`) and H3 (remove the `halt`/partial-state handling in `Guards.execute`): `RunFixesTest.h1_*` and `h3_*` must fail; restore with `git checkout`.
4. Final safety-reviewer pass on the staged cleanup diff (it must finish after the last edit to `modules/cleanup`), then one large commit. Put the RED evidence (below) in the commit message and STATUS.md.
5. `bash scripts/verify.sh`, STATUS entry, push, PR against `p5-guards`.

### RED evidence for RunFixesTest (against the pre-fix P5.4 code; all in `modules/testkit/src/test/java/forge/guards/RunFixesTest.java`)
| Test | Dialects | Failure before the fix |
|---|---|---|
| `h1_truncateTakesTheTableLockSoAConcurrentInsertIsNeverTruncated` | PG | run exited 0 and truncated; expected exit 1 `row_count_mismatch` (a row inserted by another session just before the truncate was destroyed) |
| `h2_notNullSelfReferenceWithAnOutsideReferrerIsCleaned` | PG, SQLite (2) | run exited 1 (`NullPointerException` in `together()`, reported as `batch_failed`); expected exit 0 |
| `h3_aFailureOutsideABatchAfterACommitIsPartialWithTheTrueCount` | PG | exit 2 and "Nothing was deleted" with state `running`; expected exit 3, state `partial`, `data.deleted` = rows really deleted |
| `m5_everyChunkOfABatchIsLockedBeforeAnyIsChecked` | MySQL | **not RED**: passes against the pre-fix code (the reviewer's stale-snapshot theory was not reproduced on MySQL 8.4); kept as a regression test, fix kept as defence |
| `m6_resumeNeverAcceptsARunMarkedRunning` | PG, MySQL, SQLite (3) | `--resume` on a `running` run exited 0 and deleted; expected exit 1 `run_in_progress` |
| `m7_noDriverTextOnTheScreen` | PG, MySQL, SQLite (3) | the database's own error text (`leaky-detail-xyz`) reached the screen |
M6 route taken: **require `partial`**; `running` is always refused; the operator sets `runs.state` to `partial` by hand (documented in `docs/design/CLEANUP.md` "Accepted limits" and README). M1 to M4 are documented as accepted limits there; L1 to L7 go in STATUS.md.

## How the cleanup code is laid out (`modules/cleanup/src/main/java/forge/cleanup/`, ~20 classes)
`Cleanup` (public entry: `dry`, `run`, `classify`), `Request` (public record), `Outcome` (exit, data, errors, warnings, text). Internals: `Resolve` (which run, state), `Integrity` (manifest checks), `Own` (fingerprint scan, `Plan.check`), `Blockers`/`Found` (real rows, cascades, triggers), `Digest`, `Dry`, `Classify`, `Guards`, `Typed`, `Backup` (not shell-injection safe by design: runs the configured command), `Delete`, `Audit` (file + table, intent/result), `Meta` (DDL for `dry_runs`/`audit`), `MetaRead`, `Keys`, `Sql`, `Ctx` (read-only connection + schema + config).
CLI: `modules/cli/src/main/java/forge/cli/{CleanupCmd,ClassifyCmd,Forge}.java`. In `Forge.java`: `Readiness` is still a stub; `purge` is not registered; `cleanup --drop-metadata` returns `not_built`.
Hard rules in code: delete/update/truncate SQL only in `Delete`; reads through `Db.openReadOnly()`; FK checks never switched off; every refusal writes an audit intent + result entry; refusals write no `dry_runs` row; error text on screen never echoes driver text or the real database name.

## What P5.5 must build (all design is in `docs/design/CLASSIFY_PURGE_READINESS.md`, `CLEANUP.md`, `KEEP_TABLES.md`, `GUARDS.md`; the frozen tests in `modules/testkit/src/test/java/forge/safety/` are the spec)
1. **`forge readiness [--run-id ID] [--json]`** read-only checklist (counts_differ, manifest_rows_remain, test_patterns_remain, foreign_keys_invalid, sequence_not_reset, metadata_present, ...): `ReadinessTest` i1 to i3 and the readiness half of `GuardsTest.b2`.
2. **Sequence reset after a complete cleanup**: next id = max+1 of what remains (`SequencesTest` j1 = "next id after cleanup is max+1", j2 = "nothing but a complete run moves sequences"). The manifest already records sequence state (`Manifest.Seq`, `runs`). Postgres `setval`, MySQL `auto_increment`, SQLite `sqlite_sequence`. Audit output field `sequences_reset` (CLEANUP.md).
3. **`forge purge --dry-run | --run --rule NAME ... [--include-unmanifested]`** with `cleanup.purge` rules and `cleanup.test_patterns` (config already parsed in `Config.Cleanup`): `ClassifyPurgeTest` k2a to k2d, `KeepTablesTest.d1` (purge on a keep table = `keep_table_purge`). Same guards, audit and delete engine as cleanup (reuse `Guards`/`Delete`; do not add delete SQL elsewhere).
4. **`forge cleanup --drop-metadata --confirm-db --confirm-rows`**: drops schema `_dataforge` (PG) / `_dataforge_*` tables (MySQL, SQLite); the audit **file** survives. Needed by `AuditLogTest.f3`, `ResumeAndScopeTest.l2`, and the controls of `ReadinessTest`.
Then update `scripts/safety-green.txt` with the newly green frozen tests (procedure below), `docs/design/CONFIG.md`/CLEANUP.md as needed, STATUS, PR (base = the P5.4 branch `p5-run`).

## What P5.6 must do (branch from the P5.5 branch)
- Run the whole frozen suite and `containerTest` on all 3 databases; report counts.
- **1M-row cleanup measurement** under `-Xmx256m` (same style as `docs/PERF.md`, `scripts/perf.sh`, harness `ScaleContainerTest`): generate 1M rows, `cleanup --dry-run`, `cleanup --run`, record seconds, live/peak MB, rows/s on Postgres, MySQL, SQLite. The engine counts all watched tables per batch and re-reads fingerprints: if it is too slow or memory grows, that is where to look (`Delete.watch/counts`, `Own.Plan`, `Keys.Members` holds every list key in memory).
- Write `docs/PHASE5_RESULTS.md` (results table, mutation checks, the impossible tests, open items). Final STATUS entry and PR.
- **End-of-phase reviews (once)**: run determinism-auditor, scope-guard, dialect-specialist and perf-profiler on the full Phase 5 diff (`git diff main...HEAD` or per branch), show findings, fix real bugs test-first, note the rest in STATUS.md.
- Then the final report to the owner plus the list of reviews for fresh sessions.

## Known-impossible frozen tests (do not edit them; they are written up in STATUS.md)
- `DeleteOrderTest.g2_selfReferencingTable` MySQL `nodes:parent_id` and `BlockersTest.c3b` MySQL `org:boss_id`: MySQL InnoDB cannot delete a row that references itself (NOT NULL self FK) while FK checks are on, and Data Forge never turns them off (proved in a MySQL 8.4 container: error 1451 even for the last row).
- `ResumeAndScopeTest.l4_*` is `@Disabled` in the frozen set; the rule is covered by `forge.guards.SqliteForeignKeysTest` (a wrapping JDBC driver ignoring `PRAGMA foreign_keys = ON`).
Because of the MySQL cases the final `safety-green.txt` cannot be the single line `forge.safety.*`; keep the explicit method list and say why in PHASE5_RESULTS.md.

## Commands that work (use bash, not zsh)
```bash
# one summary line set, no log dumping
./gradlew --continue containerTest :testkit:safetyTest > $S/all.log 2>&1
grep -E "tests completed" $S/all.log; grep -E "FAILED$" $S/all.log | grep -v "^> Task" | sed -E 's/ > .*//' | sort | uniq -c
./gradlew :testkit:safetyTest --tests 'forge.safety.SequencesTest'      # one class
./gradlew :testkit:containerTest --tests 'forge.guards.*'                # my non-frozen tests
bash scripts/verify.sh > $S/verify.log 2>&1; grep -E "^(PASS|FAIL|SKIP)" $S/verify.log   # ~10 min; step i = frozen list; step e = clean container
bash scripts/verify.sh --only-e                                          # just the clean-machine build (~30 s)
```
`$S` = your scratchpad directory. Gradle printing `BUILD SUCCESSFUL in 350ms` after an edit is normal (warm daemon). A `containerTest` takes about 5 to 8 minutes, the frozen suite about 8 to 12.
**Updating `scripts/safety-green.txt`** (one `--tests` pattern per line, `forge.safety.<Class>.<prefix>_*`): never remove a line to make a run pass. A pattern must be green on all three dialects; a method with any failing dialect cannot be listed. Derive the green methods from the Gradle log lines `Class > method(...) FAILED` against the `void <id>_...` methods in the safety sources.

## Hooks and gotchas (all learned the hard way)
- The commit hook `guard-cleanup-commit.sh` looks at the **working tree**: any `git commit` while `modules/cleanup` has uncommitted changes is blocked until a safety-reviewer finished after the last edit. The hook runs **before** the command, so unstage in a separate Bash call. Test-only commits are impossible while cleanup is dirty; plan one large commit.
- `modules/cleanup/src/test/` and `modules/testkit/src/test/java/forge/safety/` are never edited. Put new tests in `modules/testkit/src/test/java/forge/guards/` (helpers `Check`, `GuardsKit` in `forge/testkit/`; `GuardsKit.Target.pair/custom`, `Target.run/dry/sql/q/one/open`).
- Fixtures: `forge.testkit.OwnershipKit` (containers, MySQL root grants incl. PROCESS, `T.open()`), `DryHalvesTest.custom/rows` (generate with custom DDL).
- MySQL: sessions need `ANSI_QUOTES` and UTC (`Sql.session`); no truncate; `information_schema.triggers` only for users with TRIGGER.
- Postgres truncate only when the table holds nothing but manifest rows (checked under `lock table ... access exclusive`).
- `Forge.env` is a public static hook the tests set; the config reads passwords from env names.
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; PR bodies end with the "Generated with Claude Code" line. PRs: `gh pr create --base <previous branch> --head <this branch>`.
- A clean-machine container does not reap zombies: tests that check a process is dead must treat state `Z` as dead (`BackupTest` does).
- Keep `docs/STATUS.md` entries to 5 lines (done, not done, next, decisions, open), update the "Current state" block in `CLAUDE_PROJECT.md` each feature. **L4 is covered by `SqliteForeignKeysTest`; say so in STATUS.**

## Open items to carry (full list in `docs/STATUS.md`)
Owner review of the delegated safety design is still pending for every PR. Accepted limits M1 to M4, M6 and L1 to L7 are in `docs/design/CLEANUP.md` "Accepted limits" and README. Open questions: whether a dry run on prod with `--allow-prod` also needs a typed confirmation; `allow_triggers` per trigger instead of per table; whether `--confirm-rows 0` should be refused; `dry_runs.kind` filter for purge dry runs (P5.5 must not let a purge dry run satisfy a cleanup run, or the reverse).
