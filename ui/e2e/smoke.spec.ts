import { expect, test, type Page } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

// Smoke test against the real local server (./gradlew :server:run) serving ui/dist, with a SQLite fixture.
// Run `npm run build` first. No network is used.

let server: ChildProcess;
let openUrl = '';
let dbUrl = '';

test.beforeAll(async () => {
  const dir = mkdtempSync(join(tmpdir(), 'forge-e2e-'));
  const file = join(dir, 'app.db');
  const db = new DatabaseSync(file);
  db.exec('create table parent (id integer primary key, name text not null, boss_id integer references parent(id))');
  db.exec('create table child (id integer primary key, parent_id integer not null references parent(id))');
  db.close();
  dbUrl = `sqlite:${file}`;

  server = spawn('./gradlew', ['-q', ':server:run', `--args=0 --ui ${resolve('dist')}`], { cwd: '..', detached: true });
  openUrl = await new Promise<string>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('server did not start in 120 s')), 120_000);
    let buf = '';
    server.stdout?.on('data', (d: Buffer) => {
      buf += d.toString();
      const m = /open the UI: (http:\/\/127\.0\.0\.1:\d+\/#token=[0-9a-f]+)/.exec(buf);
      if (m && m[1]) {
        clearTimeout(t);
        resolve(m[1]);
      }
    });
    server.on('exit', () => reject(new Error('server exited early: ' + buf)));
  });
});

test.afterAll(() => {
  if (server.pid) process.kill(-server.pid, 'SIGTERM');
});

async function connect(page: Page, env: string) {
  await page.getByLabel('Database URL').fill(dbUrl);
  await page.getByLabel('Environment label').selectOption(env);
  await page.getByLabel(/Password/).fill('pw-not-stored');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
}

test('connect, schema, run, verify, go-live, prod refused', async ({ page }) => {
  await page.goto(openUrl);
  await expect(page.getByRole('heading', { name: 'Connect' })).toBeVisible();

  // Connect: dev works, the password field is cleared afterwards
  await connect(page, 'dev');
  await expect(page.getByRole('heading', { name: 'Schema graph' })).toBeVisible();
  await page.keyboard.press('1');
  await expect(page.getByLabel(/Password/)).toHaveValue('');
  await expect(page.locator('body')).not.toContainText('pw-not-stored');

  // Schema graph renders and marks the self reference
  await page.keyboard.press('2');
  await expect(page.getByTestId('graph')).toBeVisible();
  await expect(page.getByTestId('graph')).toContainText('parent');
  await expect(page.getByTestId('graph')).toContainText('child');
  await expect(page.getByTestId('graph')).toContainText('self reference');

  // Run: generate finishes (done or failed) and the summary says the data is partial
  await page.keyboard.press('3');
  await page.getByRole('button', { name: 'Generate' }).click();
  await expect(page.getByTestId('run-state')).toContainText(/done|failed/, { timeout: 120_000 });
  await expect(page.getByTestId('run-summary')).toContainText('This data is partial');

  // Verify on a SQLite fixture has no rules: an error is shown and no empty "Failed (0)" list that could read as clean
  await page.keyboard.press('4');
  await page.getByRole('button', { name: 'Run verify' }).click();
  await expect(page.getByRole('alert').first()).toBeVisible();
  await expect(page.getByTestId('verify-failed')).toHaveCount(0);

  // Go-live: dry run, readiness and a copyable command; nothing on the page runs it
  await page.keyboard.press('5');
  await page.getByRole('button', { name: 'Run dry run and readiness' }).click();
  await expect(page.getByTestId('golive-dry')).toBeVisible();
  await expect(page.getByTestId('golive-ready')).toBeVisible();
  await expect(page.getByTestId('command')).toContainText('forge cleanup --run');
  await expect(page.getByRole('button', { name: /run cleanup|purge|backup|delete/i })).toHaveCount(0);

  // Prod: red banner on every screen while selected, and connect is refused
  await page.keyboard.press('1');
  await page.getByLabel('Environment label').selectOption('prod');
  await expect(page.getByRole('alert').first()).toContainText('PROD');
  await page.getByLabel('Database URL').fill(dbUrl); // the form was reset when the screen was left
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.getByText('env_refused')).toBeVisible();
});

test('verify keeps passed, failed and not-checked in three separate lists (mocked answer)', async ({ page }) => {
  await page.route('**/api/verify', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        ok: false,
        envelope: {
          schema: 'forge.output/1', command: 'verify', ok: false, exit: 1, warnings: [],
          data: { rules: [
            { name: 'r_pass', status: 'PASS' },
            { name: 'r_fail', status: 'FAIL' },
            { name: 'r_skip', status: 'NOT_CHECKED' },
            { name: 'r_db', status: 'ENFORCED_BY_DB' },
          ] },
          errors: [{ code: 'rule_failed', message: 'orders.total is 500, the rule gives 800', rule: 'r_fail', table: 'orders', row: { id: 7 } }],
        },
      }),
    }),
  );
  await page.goto(openUrl);
  await connect(page, 'dev');
  await expect(page.getByRole('heading', { name: 'Schema graph' })).toBeVisible();
  await page.keyboard.press('4');
  await page.getByRole('button', { name: 'Run verify' }).click();
  await expect(page.getByTestId('verify-passed')).toContainText('r_pass');
  await expect(page.getByTestId('verify-passed')).not.toContainText('r_skip');
  await expect(page.getByTestId('verify-passed')).not.toContainText('r_db');
  await expect(page.getByTestId('verify-failed')).toContainText('r_fail');
  await expect(page.getByTestId('verify-failed')).toContainText('orders');
  await expect(page.getByTestId('verify-notchecked')).toContainText('r_skip');
  await expect(page.getByTestId('verify-notchecked')).toContainText('r_db');
  await expect(page.getByTestId('verify-notchecked')).not.toContainText('r_pass');
});
