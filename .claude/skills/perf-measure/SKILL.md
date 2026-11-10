---
name: perf-measure
description: How to measure speed and memory: JFR, /usr/bin/time -l, the 1M-row recipe, the report table. Use for the checks step and the scale gate.
---

# perf-measure

Release build. Generate 1M rows into Postgres.

- Peak RSS and wall time: `/usr/bin/time -l <command>` (macOS).
- Heap and allocation: a JFR recording, for example `-XX:StartFlightRecording=filename=run.jfr,settings=profile`.
- Report: peak heap, peak RSS, rows per second, startup time of `forge --help`.
- Run it 3 times in a row and say whether memory grows. Say what holds the most memory.
- Bounded memory: stream rows in batches; a test loads 1M rows with `-Xmx256m`.

Report as a short table with a verdict per row: PASS, WARN or FAIL. Record the time per release. State the machine and commands so it can be reproduced. Say what you could not measure.
