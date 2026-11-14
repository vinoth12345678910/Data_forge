// The demo scenes. Each step's `show` is what is typed on screen; `run` (default: show) is what is executed in a clean
// sandbox on the real tool. `pre` runs hidden before the step. Differences between show and run are only transport:
// the database is on a non-default port and `psql` runs inside the Postgres container.
const FIVE = [
  'forge init --url postgres://localhost:5432/app --user postgres --password-env PGPASSWORD --env dev',
  'forge generate',
  'forge verify',
  'forge drift',
  'forge cleanup --dry-run | tail -3',
];
const dry = (t) => (t.map((s) => /Dry run: (\d+) rows/.exec(s.out)).find(Boolean) || [0, '0'])[1];

export const scenes = [
  { name: 'hero', db: 'rules', steps: [
    { show: 'forge generate | tail -7' },
    { show: 'forge verify | tail -7' },
    { show: 'forge cleanup --dry-run | tail -4' },
  ] },
  { name: 'quickstart', db: 'bare', steps: FIVE.map((c) => ({ show: c })) },
  { name: 'how-it-works', db: 'bare', steps: [
    { show: 'forge init --url postgres://localhost:5432/app --user postgres --password-env PGPASSWORD --env dev | head -3' },
    { show: 'forge generate --out sql --out-dir out | tail -2' },
    { show: 'ls out' },
  ] },
  { name: 'schema', db: 'bare', pre: [`cat > inspect.py <<'PY'
import json
d = json.load(open('forge.schema.json'))
print('tables:', len(d['tables']))
print('self-references:', [t['name'] for t in d['tables'] for f in t['fks'] if f['refTable'] == t['name']])
print('composite keys:', [t['name'] for t in d['tables'] if len(t['pk']) > 1])
PY`], steps: [
    { show: 'forge init --url postgres://localhost:5432/app --user postgres --password-env PGPASSWORD --env dev' },
    { show: 'python3 inspect.py' },
  ] },
  { name: 'determinism', db: 'rules', steps: [
    { show: 'forge generate --out sql --out-dir a | grep Wrote' },
    { show: 'forge generate --out sql --out-dir b | grep Wrote' },
    { show: 'cat a/*.sql | shasum -a 256; cat b/*.sql | shasum -a 256' },
    { show: "sed -i.bak 's/^seed: .*/seed: 43/' forge.yml", hold: 400 },
    { show: 'forge generate --out sql --out-dir c | grep Wrote' },
    { show: 'cat c/*.sql | shasum -a 256' },
  ] },
  { name: 'rules', db: 'bare', pre: ['forge init --url postgres://localhost:55432/app --user postgres --password-env PGPASSWORD --env dev >/dev/null',
      "printf 'rules:\\n  - name: order_total_matches_items\\n    derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)\\n' > rule.yml"], steps: [
    { show: 'cat rule.yml' },
    { show: "sed -i.bak '/^rules: \\[\\]$/d' forge.yml", hold: 300 },
    { show: 'cat rule.yml >> forge.yml', hold: 400 },
    { show: 'forge generate | tail -3' },
    { show: 'forge verify | tail -4' },
    { show: 'psql -c "update orders set total_paise = total_paise + 1 where id = (select min(id) from orders)"' },
    { show: 'forge verify | tail -6' },
  ] },
  { name: 'realism', db: 'rules', pre: ['forge generate >/dev/null'], steps: [
    { show: 'psql -c "select status, count(*) from orders group by 1 order by 2 desc"' },
    { show: 'psql -c "select o.status as order, p.status as payment, count(*) from orders o join payments p on p.order_id = o.id group by 1, 2 order by 1, 2"' },
    { show: 'psql -c "select count(*) as payments, count(*) filter (where p.created_at >= o.placed_at) as after_order from payments p join orders o on o.id = p.order_id"' },
  ] },
  { name: 'scenarios', db: 'rules', steps: [
    { show: 'forge generate -s sale_rush | grep -o "Loaded [0-9]* rows"' },
    { show: 'psql -c "select status, count(*) from orders group by 1 order by 2 desc"' },
    { reset: true, show: 'forge generate --edge | grep -o "Loaded [0-9]* rows"' },
    { show: "psql -c \"select name from events where name ~ '[^ -~]'\"" },
    { show: 'psql -c "select lower(email) as email, count(*) from users group by 1 having count(*) > 1 limit 2"' },
  ] },
  { name: 'verify', db: 'rules', pre: ['forge generate >/dev/null', 'cp "$REPO/golden/events-merch/expected.yml" .',
      'psql -c "update orders set total_paise = total_paise + 1 where id = (select min(id) from orders)" >/dev/null'], steps: [
    { show: 'forge verify --expected expected.yml | grep -c "^  PASS"' },
    { show: 'forge verify --expected expected.yml | grep -E "^  FAIL|id=|^result"' },
    { show: 'forge verify --expected expected.yml | grep -E "^  (SKIP|DB)" | head -7' },
  ] },
  { name: 'drift', db: 'bare', pre: ['forge init --url postgres://localhost:55432/app --user postgres --password-env PGPASSWORD --env dev >/dev/null'], steps: [
    { show: 'forge drift; echo "exit code: $?"' },
    { show: 'psql -c "alter table users add column nickname text"' },
    { show: 'forge drift; echo "exit code: $?"' },
    { show: 'forge drift --fail-on warning | tail -1; echo "exit code: ${PIPESTATUS[0]}"' },
  ] },
  { name: 'outputs', db: 'rules', steps: [
    { show: 'forge generate --out sql --out-dir out | grep Wrote' },
    { show: 'ls out | head -6' },
    { reset: true, show: 'psql -c "select count(*) as orders from orders"' },
    { show: 'cat out/*.sql | psql -q' },
    { show: 'psql -c "select count(*) as orders from orders"' },
  ] },
  { name: 'cleanup', db: 'rules', pre: ['forge generate >/dev/null'], steps: [
    { show: 'forge cleanup --dry-run | tail -1' },
    { show: 'psql -c "insert into refunds (payment_id, amount_paise, reason, created_at) select min(id), 1, \'manual\', now() from payments"' },
    { show: 'forge cleanup --dry-run 2>&1 | grep -E "BLOCKED|^Dry run"' },
    { show: 'psql -c "delete from refunds where reason = \'manual\'"' },
    { show: 'forge cleanup --dry-run | tail -1' },
    { show: (t) => `forge cleanup --run --confirm-db app --confirm-rows ${dry(t)} | tail -2` },
    { show: (t) => `forge cleanup --drop-metadata --confirm-db app --confirm-rows ${dry(t)} 2>&1 | tail -1` },
    { show: 'forge readiness 2>&1 | grep -v "\\[ \\]"' },
  ] },
];
