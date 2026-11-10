# Reusable prompts

Swap the bracketed parts per week. Order: 1 -> review -> 2 -> 3 -> (4 only if a check failed) -> next week's 1.
All prompts obey the strict rule: ONE FEATURE = ONE BRANCH.

## Prompt 1: Plan the week (no code yet)

```
Read CLAUDE.md, docs/SPEC.md and docs/PLAN.md. We are starting [Week 2: generator core].

Work in plan mode. Change no code yet.

1. List the exact files, classes and interfaces you will add. Keep names short and plain.
2. Write the gate test first, as a test class name and what it asserts. For Week 2 the gate is: same seed, same spec, two runs, byte-identical output.
3. List the design decisions you need from me (for example how the seed is hashed per row). Do NOT decide these yourself.
4. List what could break the hard rules: no network, no unseeded randomness, no HashMap iteration order in generation code.

Report as a short plan with a numbered task list. Then stop and wait for me.
```

## Prompt 2: Build with the gate first

```
Plan approved. Go ahead with [Week 2], one task at a time.

1. Write the gate test first. Run it and show me it fails.
2. Build until it passes. One commit per task, on branch w02-generator-core.
3. If you hit a design choice I have not decided, stop and ask. Do not guess.
4. Do not touch code outside this week's scope.
5. When the gate is green, update docs/STATUS.md (done, not done, next, decisions, open questions) and tell me which gate passed.

Then stop and wait for me. Do not start the next week.
```

## Prompt 3: Checks before the next feature

```
Gate is green. Before the next week, three checks. Change no feature code unless I agree.

1. Determinism. Run generate 5 times with the same seed on [golden schema: ledgerboard]. Compare output hashes. Then run with a different JVM, with -XX:+UseSerialGC and default GC, and with the table order shuffled in forge.yml. Any difference: find the line that causes it (HashMap order, parallel stream, time, locale, unseeded Random) and propose a fix.

2. Memory and speed. Release build. Generate 1M rows into Postgres. Report peak heap and peak RSS (use /usr/bin/time -l and a JFR recording), rows per second, and startup time of `forge --help`. Then run it 3 times in a row and tell me whether memory grows. Tell me what holds the most memory.

3. The rules I did not test yet, as separate rows in the same table:
   - Network: run the full suite in Docker with --network none. It must pass.
   - Edge cases: NULLs, max length, unicode, DST dates on Postgres.
   - A deliberately broken row. The verify pass must catch it and name the rule and row.
   - Tell me which parts need me to do something by hand.

Report all three as short tables with a verdict each (PASS, WARN, FAIL). Then stop and wait for me.
```

Phase 5 add-on line for Prompt 3: "I have written the safety tests myself. Run them and report. Do not edit them."
Phase 6 swap: replace check 2 with a Playwright run on each screen, with a screenshot per screen.

## Prompt 4: Fixes, re-measure, continue

```
Agreed on the fixes for [items 1 and 2 from your report]. Go ahead, one commit each, then re-measure.

1. Commit 1, [determinism]: replace [HashMap in TablePlanner] with [LinkedHashMap / sorted order]. Add a regression test that fails on the old code.
2. Commit 2, [memory]: stream rows in batches of [N] instead of building the full list. Keep heap bounded. Add a test that loads 1M rows with -Xmx256m.
3. Do not change anything else. Make sure nothing breaks when [optional feature] is off.
4. Re-run the checks from my last prompt for the standard cases. Send the same tables plus the new numbers, before and after.

Then continue with the next unfinished checkpoint in docs/PLAN.md, same loop as before: tell me which one it is before you start, and use Prompt 1 style (plan first, gate test first).

If you differ from any instruction above, tell me exactly where and why. If you could not test something, say so plainly. Do not claim it works.
```
