# Quick start

## What you need

- **A Postgres database that already has your tables** (Postgres 16 is the tested target), reachable from where you run `forge`, with its password in the environment variable `PGPASSWORD`.
- **Either Java 21, or the self-contained bundle** (it carries its own Java; macOS on Apple silicon only, see [RELEASE.md](RELEASE.md)).

## Install

From the release files (see [RELEASE.md](RELEASE.md) for checksums):

```bash
tar -xzf forge-1.0.0-rc1.tar.gz && ./forge-1.0.0-rc1/forge --version                       # needs Java 21
tar -xzf forge-1.0.0-rc1-macos-arm64.tar.gz && ./forge-1.0.0-rc1-macos-arm64/bin/forge --version   # no Java needed
```

Put the `forge` launcher on your `PATH` (or call it by its path). From a source checkout: `bash scripts/package.sh`.

## The five commands

These are the commands of the README, in order, in a directory of your choice. `forge.yml`, `forge.schema.json` and `.forge/` (the manifests) are written to the current directory.

```bash
forge init --url postgres://localhost:5432/app --user postgres --password-env PGPASSWORD --env dev
forge generate
forge verify
forge drift
forge cleanup --dry-run
```

This is what they print against the golden events schema in Postgres 16 in a clean container, recorded by `scripts/docs-test.sh --record` and compared by `scripts/docs-test.sh` on every `verify.sh` run. Run ids are shown as `<run-id>`; the working directory in the container was `/work`.

<!-- expected:start -->
```text
$ forge init --url postgres://localhost:5432/app --user postgres --password-env PGPASSWORD --env dev
Wrote forge.yml: 12 tables, seed 42.
Cycle broken: orders.orders_latest_payment_fk (deferred)
Check not fully understood: order_items.order_items_one_target (num_nonnulls(ticket_type_id, merch_id) = 1)
Check not fully understood: payments.payments_completed_when_done ((status = 'initiated'::text) = (completed_at IS NULL))
Wrote /work/forge.schema.json (the baseline forge drift compares with).
(exit 0)

$ forge generate
Loaded 1101 rows into 12 tables (run <run-id>). Manifest: /work/.forge/manifests/<run-id>.json
No rules in forge.yml: nothing to verify.
Note: this data is partial. Structure (types, keys, NOT NULL, unique, check, foreign keys) holds, and only the 0 rule(s) in forge.yml, plus the rules its state machines imply, are generated and verified. Rules this phase does not handle are not checked (see docs/design/PHASE3_RESULTS.md); there is no full realism yet.
(exit 0)

$ forge verify
forge verify  env dev
forge verify: 0 rules on postgres://localhost:5432/app
result: 0 FAIL, 0 ERROR   (exit code 0)
(exit 0)

$ forge drift
drift: 0 change(s)  error 0  warning 0  info 0  (fail on error)
(exit 0)

$ forge cleanup --dry-run
forge cleanup --dry-run  run <run-id>  env dev  db app
  stock  manifest 100  live 100
  warehouses  manifest 100  live 100
  registrations  manifest 100  live 100
  order_items  manifest 100  live 100
  ticket_types  manifest 100  live 100
  refunds  manifest 100  live 100
  payments  manifest 100  live 100
  orders  manifest 100  live 100
  events  manifest 100  live 100
  users  manifest 100  live 100
  meta  manifest 1  live 1
  merchandise  manifest 100  live 100
Dry run: 1101 rows in 12 tables would be deleted by a later run. Nothing was deleted.
(exit 0)
```
<!-- expected:end -->

## What this shows

- `init` wrote `forge.yml` with **no business rules**, so `generate` says "No rules in forge.yml: nothing to verify" and `verify` has nothing to check. The data is structurally valid only.
- Add rules to `forge.yml` ([Rules guide](RULES_GUIDE.md)), run `forge generate` again on an empty database, and `forge verify` lists each rule as passed, failed or not checked.
- `forge cleanup --dry-run` removed nothing. Cleanup that deletes is `forge cleanup --run`, behind the guards in [SAFETY.md](SAFETY.md).
