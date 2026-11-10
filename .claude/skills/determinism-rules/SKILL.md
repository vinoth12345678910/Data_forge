---
name: determinism-rules
description: Banned constructs in generation code and the byte-identical two-run test recipe. Use when writing, testing or auditing generation code.
---

# determinism-rules

Same seed and same spec must give byte-identical output. The seed per value is derived from hash(seed, table, column, row); the exact hashing design is **user-owned**: stop and ask.

## Banned in generation code
- `HashMap` / `HashSet` iteration order (use ordered or sorted collections)
- `parallelStream` and any ordering that depends on threads
- unseeded `Random`, `Math.random`, `ThreadLocalRandom`
- wall clock: `System.currentTimeMillis`, `Instant.now`, `LocalDate.now` (use the dataset clock)
- default `Locale`, default time zone, default charset
- file listing order, unordered database results, `Object.hashCode` order

## Test recipe
1. Generate 5 times with the same seed on a golden schema. Compare output hashes: all equal.
2. Run with a different JVM, with `-XX:+UseSerialGC` and the default GC, and with table order shuffled in `forge.yml`. No difference.
3. Any difference: find the line (HashMap order, parallel stream, time, locale, unseeded Random) and propose a fix with a regression test.

Name the test `*DeterminismTest` so `scripts/verify.sh` step b picks it up.
