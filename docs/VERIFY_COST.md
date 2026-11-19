# Verify cost probe (register D5)

Written by `scripts/lab-verify-cost.sh` (2026-11-19). Postgres 16, rule `derive: parents.total = sum(children.v)`, 3 children per parent, verify statement timeout 120 s. Machine: Apple M4, 16 GB.

| Parents | Children | Index on children(parent_id) | Result | Time |
|---|---|---|---|---|
| 20000 | 60000 | yes |  PASS parent_total derive parents checked 20000 rows | 1 s |
| 20000 | 60000 | no |  PASS parent_total derive parents checked 20000 rows | 20 s |
| 100000 | 300000 | yes |  PASS parent_total derive parents checked 100000 rows | 1 s |
| 100000 | 300000 | no |  ERROR parent_total derive parents ERROR: canceling statement due to statement timeout | 120 s |
