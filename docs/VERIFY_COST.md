# Verify cost probe (register D5)

Written by `scripts/lab-verify-cost.sh` (2026-11-19). Postgres 16, rule `derive: parents.total = sum(children.v)` (selective rows add `when parents.id % 1000 = 0`), 3 children per parent, verify statement timeout 120 s, wall time of the whole `forge verify` command (JVM start and schema read included, about 1 s). Mode grouped is the default; legacy is `--legacy-sql`. Machine: Apple M4, 16 GB.

| Mode | Rule | Parents | Children | Index on children(parent_id) | Result | Time |
|---|---|---|---|---|---|---|
| grouped | all | 20000 | 60000 | yes |  PASS parent_total derive parents checked 20000 rows | 397 ms |
| grouped | all | 20000 | 60000 | no |  PASS parent_total derive parents checked 20000 rows | 388 ms |
| grouped | selective | 20000 | 60000 | yes |  PASS parent_total derive parents checked 20 rows | 451 ms |
| legacy | all | 20000 | 60000 | yes |  PASS parent_total derive parents checked 20000 rows | 420 ms |
| legacy | all | 20000 | 60000 | no |  PASS parent_total derive parents checked 20000 rows | 19987 ms |
| legacy | selective | 20000 | 60000 | yes |  PASS parent_total derive parents checked 20 rows | 392 ms |
| grouped | all | 100000 | 300000 | yes |  PASS parent_total derive parents checked 100000 rows | 525 ms |
| grouped | all | 100000 | 300000 | no |  PASS parent_total derive parents checked 100000 rows | 421 ms |
| grouped | selective | 100000 | 300000 | yes |  PASS parent_total derive parents checked 100 rows | 415 ms |
| legacy | all | 100000 | 300000 | yes |  PASS parent_total derive parents checked 100000 rows | 532 ms |
| legacy | all | 100000 | 300000 | no |  ERROR parent_total derive parents ERROR: canceling statement due to statement timeout slow: this rule reads the child r | 120386 ms |
| legacy | selective | 100000 | 300000 | yes |  PASS parent_total derive parents checked 100 rows | 413 ms |
| grouped | all | 1000000 | 3000000 | yes |  PASS parent_total derive parents checked 1000000 rows | 1469 ms |
| grouped | all | 1000000 | 3000000 | no |  PASS parent_total derive parents checked 1000000 rows | 1379 ms |
| grouped | selective | 1000000 | 3000000 | yes |  PASS parent_total derive parents checked 1000 rows | 917 ms |
| legacy | all | 1000000 | 3000000 | yes |  PASS parent_total derive parents checked 1000000 rows | 2626 ms |
| legacy | all | 1000000 | 3000000 | no |  ERROR parent_total derive parents ERROR: canceling statement due to statement timeout slow: this rule reads the child r | 120425 ms |
| legacy | selective | 1000000 | 3000000 | yes |  PASS parent_total derive parents checked 1000 rows | 498 ms |
