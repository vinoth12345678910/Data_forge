import { strict as assert } from 'node:assert';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

// The routes ui/src/api/client.ts calls must be exactly the routes docs/design/UI_API.md lists, and nothing in ui/src may
// call or offer delete, truncate, cleanup-run, purge or backup.
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, 'src');
const doc = readFileSync(join(root, '..', 'docs', 'design', 'UI_API.md'), 'utf8');

function files(dir: string, out: string[] = []): string[] {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) files(p, out);
    else if (/\.(ts|tsx)$/.test(n)) out.push(p);
  }
  return out;
}

const documented = new Set([...doc.matchAll(/\|\s*`(GET|POST) (\/api\/[a-z]+)`/g)].map((m) => `${m[1]} ${m[2]}`));
const called = new Set(
  [...readFileSync(join(src, 'api', 'client.ts'), 'utf8').matchAll(/call<[^(]*>\('(GET|POST)',\s*'(\/api\/[a-z]+)'/g)].map((m) => `${m[1]} ${m[2]}`),
);

test('UI_API.md lists routes and api.ts calls exactly those', () => {
  assert.ok(documented.size >= 7, `documented routes: ${[...documented].join(', ')}`);
  assert.deepEqual([...called].sort(), [...documented].sort());
});

test('no route in the doc or in ui/src can delete, truncate, purge, back up or run a cleanup', () => {
  const banned = /(cleanup|purge|truncate|delete|backup|drop)/i;
  for (const r of documented) assert.ok(!banned.test(r), `documented route ${r}`);
  for (const f of files(src)) {
    const text = readFileSync(f, 'utf8');
    for (const m of text.matchAll(/['"`](\/api\/[^'"`?#\s]*)/g)) {
      const path = m[1] ?? '';
      assert.ok(called.has(`GET ${path}`) || called.has(`POST ${path}`), `${f}: a route that is not in UI_API.md: ${path}`);
      assert.ok(!banned.test(path), `${f}: banned route ${path}`);
    }
    assert.ok(!/method\s*:\s*['"`](DELETE|PUT|PATCH)/i.test(text), `${f}: a method other than GET or POST`);
    // a button or link whose label offers a destructive action
    for (const m of text.matchAll(/<(button|a)\b[^>]*>([^<{]*)</g)) {
      assert.ok(!/(delete|truncate|purge|backup|run cleanup|cleanup --run)/i.test(m[2] ?? ''), `${f}: a destructive button or link: ${m[2]}`);
    }
  }
});
