// Real-browser render measurement of the Schema screen with a mocked /api (no forge server; only a 127.0.0.1 static server).
// Usage: node test/render-bench.mjs <dist-dir> <chromium-executable> [N ...]
// Needs a Chromium binary; Playwright's own download may not match the installed version, so the path is an argument.
import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';

const [dist, exe, ...ns] = process.argv.slice(2);
if (!dist || !exe) throw new Error('usage: node test/render-bench.mjs <dist> <chromium> [N ...]');
const sizes = ns.length ? ns.map(Number) : [100, 500, 2000, 5000];
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };

function schema(n) {
  const tables = [];
  for (let i = 1; i <= n; i++) {
    const columns = ['id', 'name', 'amount', 'created_at'].map((c) => ({ name: c, nullable: false }));
    const fks = [];
    if (i > 1) {
      const p = Math.max(1, i % 7 === 0 ? Math.floor(i / 2) : i - 1);
      columns.push({ name: 'parent_id', nullable: false });
      fks.push({ name: `t${i}_fk`, columns: ['parent_id'], refTable: `t${p}`, refColumns: ['id'] });
    }
    tables.push({ name: `t${i}`, columns, pk: ['id'], fks });
  }
  return { tables };
}

const server = createServer((req, res) => {
  const p = req.url === '/' ? '/index.html' : req.url.split('?')[0];
  const f = join(resolve(dist), p);
  if (!existsSync(f)) {
    res.writeHead(404).end();
    return;
  }
  res.writeHead(200, { 'Content-Type': types[extname(f)] ?? 'application/octet-stream' }).end(readFileSync(f));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ executablePath: exe, headless: true });
console.log('N | nodes in DOM | edges in DOM | submit-to-first-node ms | submit-to-painted ms | counter text');
for (const n of sizes) {
  const page = await browser.newPage();
  await page.route('**/api/connect', (r) => r.fulfill({ json: { ok: true, envelope: { errors: [], warnings: [], data: {} } } }));
  await page.route('**/api/schema', (r) => r.fulfill({ json: schema(n) }));
  await page.goto(`${base}/#token=abc`);
  await page.getByLabel('Database URL').fill('sqlite:/x');
  await page.evaluate(() => {
    window.__t0 = 0;
    window.__first = 0;
    window.__done = 0;
    const mo = new MutationObserver(() => {
      if (!window.__first && document.querySelector('.react-flow__node')) {
        window.__first = performance.now();
        requestAnimationFrame(() => requestAnimationFrame(() => (window.__done = performance.now())));
      }
    });
    mo.observe(document.body, { childList: true, subtree: true });
    document.querySelector('form').addEventListener('submit', () => (window.__t0 = performance.now()), true);
  });
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  let row;
  try {
    await page.waitForFunction(() => window.__done > 0, null, { timeout: 180_000 });
    await page.waitForTimeout(500);
    row = await page.evaluate(() => ({
      nodes: document.querySelectorAll('.react-flow__node').length,
      edges: document.querySelectorAll('.react-flow__edge').length,
      first: window.__first - window.__t0,
      done: window.__done - window.__t0,
      counter: document.querySelector('[data-testid=counter]')?.textContent ?? '-',
    }));
  } catch (e) {
    row = { nodes: '?', edges: '?', first: 'TIMEOUT', done: 'TIMEOUT', counter: String(e.message).slice(0, 60) };
  }
  const f = (x) => (typeof x === 'number' ? x.toFixed(0) : x);
  console.log(`${n} | ${row.nodes} | ${row.edges} | ${f(row.first)} | ${f(row.done)} | ${row.counter}`);
  await page.close();
}
await browser.close();
server.close();
