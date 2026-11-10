---
name: testcontainers-matrix
description: Running one suite on Postgres, MySQL and SQLite with Testcontainers. Use for any Docker-backed test.
---

# testcontainers-matrix

- Docker-backed tests carry `@Tag("containers")`, are excluded from `./gradlew test`, and run with `./gradlew containerTest` (never up-to-date).
- Testcontainers 2.x (Docker 29 rejects the 1.x client). Pin one Postgres image in one constant (`GoldenContainerTest.PG_IMAGE`).
- Fail loudly without Docker (`disabledWithoutDocker = false`); never skip silently. `scripts/verify.sh` step c fails if `containerTest` ran zero tests.
- Phase 3 gate: the suite is green on Postgres, MySQL and SQLite.
- CI is off. The matrix runs locally through `scripts/verify.sh`.

TODO: MySQL image and SQLite setup, once the loaders exist.
