import { expect, test, type Page } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, mkdtempSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

// Part 3 screens against the real local server (serving ui/dist, SQLite fixture). Where the real server cannot produce a case
// (an external table, a declared relation, 2,000 tables, a refused preflight) the answer is mocked in the exact JSON that
// docs/design/SERVICE_LAYER.md documents; the Java side proves the real server produces that JSON (SchemaFieldsTest).

let server: ChildProcess;
let openUrl = '';
let dbUrl = '';
const foreign: string[] = [];

test.beforeAll(async () => {
  const dir = mkdtempSync(join(tmpdir(), 'forge-e2e3-'));
  const file = join(dir, 'app.db');
  const db = new DatabaseSync(file);
  db.exec('create table parent (id integer primary key, name text not null, boss_id integer references parent(id))');
  db.exec('create table child (id integer primary key, parent_id integer not null references parent(id), shape geometry)');
  db.close();
  dbUrl = `sqlite:${file}`;
  server = spawn('./gradlew', ['-q', ':server:run', `--args=0 --ui ${resolve('dist')}`], { cwd: '..', detached: true });
  openUrl = await new Promise<string>((res, rej) => {
    const t = setTimeout(() => rej(new Error('server did not start in 120 s')), 120_000);
    let buf = '';
    server.stdout?.on('data', (d: Buffer) => {
      buf += d.toString();
      const m = /open the UI: (http:\/\/127\.0\.0\.1:\d+\/#token=[0-9a-f]+)/.exec(buf);
      if (m && m[1]) {
        clearTimeout(t);
        res(m[1]);
      }
    });
    server.stderr?.on('data', (d: Buffer) => (buf += d.toString()));
    server.on('exit', (code) => rej(new Error(`server exited early (code ${code}): ` + buf)));
  });
});

// Wait until the server has really exited: the next spec starts its own gradle run, and two at once can fail on the build lock.
test.afterAll(async () => {
  if (!server.pid) return;
  const gone = new Promise<void>((res) => (server.exitCode !== null ? res() : server.once('exit', () => res())));
  process.kill(-server.pid, 'SIGTERM');
  await Promise.race([gone, new Promise<void>((res) => setTimeout(res, 30_000))]);
  await new Promise<void>((res) => setTimeout(res, 3_000));
});

// Every request must go to localhost: anything else fails the test.
test.beforeEach(({ page }) => {
  foreign.length = 0;
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (!['127.0.0.1', 'localhost'].includes(u.hostname) && u.protocol.startsWith('http')) foreign.push(r.url());
  });
});
test.afterEach(() => {
  expect(foreign, 'requests to a host other than localhost').toEqual([]);
});

const json = (body: unknown) => ({ contentType: 'application/json', body: JSON.stringify(body) });
const col = (name: string, type = 'int8', extra: object = {}) => ({ name, type, nullable: false, read_as_text: false, ...extra });
const tbl = (name: string, extra: object = {}) => ({ name, external: false, has_insert_trigger: false, columns: [col('id')], pk: ['id'], fks: [], ...extra });

async function connect(page: Page) {
  await page.getByLabel('Database URL').fill(dbUrl);
  await page.getByLabel(/Password/).fill('pw-not-stored');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  // the fixture has a column of an unknown type, so the warnings stay on screen until the person continues
  const cont = page.getByRole('button', { name: 'Continue to the schema graph' });
  await Promise.race([cont.waitFor(), page.getByRole('heading', { name: 'Schema graph' }).waitFor()]);
  if (await cont.isVisible()) await cont.click();
}

async function shot(page: Page, name: string) {
  mkdirSync('../docs/ui/screens', { recursive: true });
  await page.screenshot({ path: `../docs/ui/screens/${name}.png`, fullPage: true });
}

test.use({ colorScheme: 'dark' });

test('connect asks for the schema to read and lists the init warnings as readable items', async ({ page }) => {
  await page.goto(openUrl);
  await expect(page.getByLabel('Schema to read')).toBeVisible();
  await page.getByLabel('Database URL').fill(dbUrl);
  await page.getByLabel(/Password/).fill('pw-not-stored');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  const w = page.getByTestId('init-warnings');
  await expect(w).toBeVisible();
  const item = w.getByTestId('init-warning').first();
  await expect(item).toContainText('child.shape');
  await expect(item).toContainText('read as text');
  await expect(item.getByTestId('what-to-do')).not.toBeEmpty();
  await expect(w).not.toContainText('SQLException');
  await shot(page, 'connect-warnings');
});

test('graph: a declared relation is dashed and labelled, an external table is drawn differently, types and triggers are marked', async ({ page }) => {
  await page.route('**/api/schema', (r) =>
    r.fulfill(json({
      schemas: ['public'],
      tables: [
        tbl('orders', { has_insert_trigger: true, columns: [col('id', 'uuid'), col('user_id', 'uuid'), col('blob', 'text', { read_as_text: true })], fks: [{ name: 'o_u', columns: ['user_id'], refTable: 'auth.users', refColumns: ['id'] }] }),
        tbl('payments', { columns: [col('id'), col('order_ref', 'uuid')] }),
        tbl('auth.users', { external: true, columns: [col('id', 'uuid')] }),
      ],
      relations: [{ table: 'payments', columns: ['order_ref'], refTable: 'orders', refColumns: ['id'], declared: true }],
    })),
  );
  await page.goto(openUrl);
  await connect(page);
  await expect(page.getByRole('heading', { name: 'Schema graph' })).toBeVisible();
  const g = page.getByTestId('graph');
  await expect(g.locator('.edge-declared')).toHaveCount(1);
  await expect(g).toContainText('declared, not a database foreign key');
  await expect(g.getByTestId('node-external')).toContainText('external: not generated, ids from config');
  await expect(g.getByTestId('type-badge-uuid').first()).toBeVisible();
  await expect(g.getByTestId('type-badge-text').first()).toContainText('read as text');
  await expect(g.getByTestId('trigger-mark')).toHaveCount(1);
  await shot(page, 'schema-graph');
});

test('graph: a self reference and a cycle stay visible', async ({ page }) => {
  await page.route('**/api/schema', (r) =>
    r.fulfill(json({
      schemas: ['public'],
      tables: [
        tbl('a', { fks: [{ name: 'a_b', columns: ['id'], refTable: 'b', refColumns: ['id'] }, { name: 'a_a', columns: ['id'], refTable: 'a', refColumns: ['id'] }] }),
        tbl('b', { fks: [{ name: 'b_a', columns: ['id'], refTable: 'a', refColumns: ['id'] }] }),
      ],
      relations: [],
    })),
  );
  await page.goto(openUrl);
  await connect(page);
  await expect(page.getByTestId('graph')).toContainText('self reference');
  await expect(page.getByTestId('cycles')).toContainText('a');
});

test('a 2,000-table schema stays responsive: search, groups, and a clear "showing N of M" notice', async ({ page }) => {
  const tables = Array.from({ length: 2000 }, (_, i) => {
    const grp = `g${String(i % 40).padStart(2, '0')}`;
    const parent = i >= 40 ? `g${String((i - 40) % 40).padStart(2, '0')}_t${String(i - 40).padStart(4, '0')}` : null;
    return tbl(`${grp}_t${String(i).padStart(4, '0')}`, { fks: parent ? [{ name: `f${i}`, columns: ['id'], refTable: parent, refColumns: ['id'] }] : [] });
  });
  await page.route('**/api/schema', (r) => r.fulfill(json({ schemas: ['public'], tables, relations: [] })));
  await page.goto(openUrl);
  await connect(page);
  const t0 = Date.now();
  await expect(page.getByTestId('counter')).toContainText(/of 2,?000 tables/);
  expect(Date.now() - t0).toBeLessThan(5000);
  await expect(page.getByTestId('graph-notice')).toContainText(/Showing \d+ of 2,?000/);
  await page.getByLabel(/Find a table/).fill('g07_t0007');
  await expect(page.getByTestId('hits')).toContainText('g07_t0007');
  await page.getByLabel(/Find a table/).fill('');
  // groups collapse and expand
  const grp = page.getByTestId('group-g07');
  await expect(grp).toBeVisible();
  await grp.locator('summary').click();
  await expect(grp.getByRole('button', { name: /g07_t0007/ })).toBeVisible();
  await grp.locator('summary').click();
  await expect(grp.getByRole('button', { name: /g07_t0007/ })).toBeHidden();
  await shot(page, 'schema-large');
});

test('run: the preflight shows before generating, and a refusal names the -Xmx needed', async ({ page }) => {
  await page.route('**/api/preflight', (r) =>
    r.fulfill(json({ ok: false, envelope: { schema: 'forge.output/1', command: 'generate', ok: false, exit: 1, warnings: [], errors: [{ code: 'memory_preflight', message: 'about 494 MB of live heap are needed; run with -Xmx640m' }],
      data: { preflight: { tied_parent_rows: 10000000, estimated_live_mb: 494, heap_mb: 256, ok: false, xmx_mb_needed: 640 } } } })),
  );
  await page.goto(openUrl);
  await connect(page);
  await page.keyboard.press('3');
  const p = page.getByTestId('preflight');
  await expect(p).toContainText('494');
  await expect(p).toContainText('256');
  await expect(page.getByTestId('preflight-refusal')).toContainText('-Xmx640m');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeDisabled();
  await shot(page, 'run-refused');
});

test('run: an accepted preflight shows the estimate and keeps the partial-data text verbatim', async ({ page }) => {
  await page.route('**/api/preflight', (r) =>
    r.fulfill(json({ ok: true, envelope: { schema: 'forge.output/1', command: 'generate', ok: true, exit: 0, warnings: [], errors: [],
      data: { preflight: { tied_parent_rows: 1000000, estimated_live_mb: 151, heap_mb: 256, ok: true, xmx_mb_needed: null } } } })),
  );
  await page.route('**/api/progress', (r) =>
    r.fulfill(json({ state: 'done', envelope: { schema: 'forge.output/1', command: 'generate', ok: true, exit: 0, warnings: [], errors: [],
      data: { partial: true, not_checked: 'Rules this phase does not handle are not checked; see docs/design/PHASE3_RESULTS.md.' } } })),
  );
  await page.goto(openUrl);
  await connect(page);
  await page.keyboard.press('3');
  await expect(page.getByTestId('preflight')).toContainText('151');
  await expect(page.getByTestId('preflight-refusal')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();
  await expect(page.getByTestId('run-summary')).toContainText('Rules this phase does not handle are not checked; see docs/design/PHASE3_RESULTS.md.');
  await shot(page, 'run');
});

test('verify: three states, a reason for each not-checked rule, and not-checked is never counted as passed', async ({ page }) => {
  await page.route('**/api/verify', (r) =>
    r.fulfill(json({ ok: false, envelope: { schema: 'forge.output/1', command: 'verify', ok: false, exit: 1, warnings: [],
      data: { rules: [
        { name: 'r_pass', status: 'PASS', checked: 10, violations: 0 },
        { name: 'r_fail', status: 'FAIL', checked: 10, violations: 1 },
        { name: 'r_na', status: 'NOT_APPLICABLE', reason: 'the target has no column orders.discount_paise' },
      ], counts: { PASS: 1, FAIL: 1, NOT_APPLICABLE: 1 }, not_checked_rules: [{ name: 'r_ghost', reason: 'not checked in this phase: the engine cannot check this rule yet' }] },
      errors: [{ code: 'rule_failed', message: 'orders.total is 500, the rule gives 800', rule: 'r_fail', table: 'orders', row: { id: 7 } }] } })),
  );
  await page.goto(openUrl);
  await connect(page);
  await page.keyboard.press('4');
  await page.getByRole('button', { name: 'Run verify' }).click();
  await expect(page.getByTestId('verify-passed')).toContainText('Passed (1)');
  await expect(page.getByTestId('verify-failed')).toContainText('r_fail');
  await expect(page.getByTestId('verify-failed')).toContainText('orders');
  const nc = page.getByTestId('verify-notchecked');
  await expect(nc).toContainText('Not checked in this phase (2)');
  await expect(nc).toContainText('the target has no column orders.discount_paise');
  await expect(nc).toContainText('the engine cannot check this rule yet');
  await expect(page.getByTestId('verify-passed')).not.toContainText('r_na');
  await expect(page.getByTestId('verify-passed')).not.toContainText('r_ghost');
  expect(await nc.evaluate((el) => getComputedStyle(el).borderTopStyle)).toBe('dashed');
  await shot(page, 'verify');
});

test('go-live shows placeholders only, never pre-filled', async ({ page }) => {
  await page.goto(openUrl);
  await connect(page);
  await page.keyboard.press('5');
  await page.getByRole('button', { name: 'Run dry run and readiness' }).click();
  const cmd = page.getByTestId('command');
  await expect(cmd).toContainText('forge cleanup --run');
  await expect(cmd).toContainText('<database name>');
  await expect(page.getByRole('button', { name: /run cleanup|purge|backup|delete|truncate/i })).toHaveCount(0);
  await expect(page.locator('input[value*="app.db"]')).toHaveCount(0);
  await shot(page, 'go-live');
});

test('error state: a failing schema call shows an alert, not a blank screen', async ({ page }) => {
  await page.route('**/api/schema', (r) => r.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' }));
  await page.goto(openUrl);
  await connect(page);
  await expect(page.getByRole('alert').first()).toBeVisible();
  await shot(page, 'schema-error');
});
