---
name: jdbc-dialects
description: Metadata quirks and bulk-load syntax per database (Postgres, MySQL, SQLite). SKELETON: details are TODO until verified.
---

# jdbc-dialects (skeleton)

Known from the plan:
- Postgres: schema read via JDBC `DatabaseMetaData`; bulk load with streaming `COPY`; cycles via deferred constraints.
- MySQL and SQLite loaders sit behind the same loader interface (Phase 3).

TODO (verify against the real database before writing it down; do not assume):
- Metadata quirks per database (types, defaults, generated columns, identity).
- Bulk-load syntax and batch sizes for MySQL and SQLite.
- How each handles cyclic FKs and deferrable constraints.
- Which golden schema features each dialect cannot express.
