// Fails if ui/src calls anything but the six routes of docs/design/UI_API.md, uses a method other than GET or POST,
// or names a cleanup-run, purge, truncate, delete, backup or drop route.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const allowed = new Set(['/api/connect', '/api/schema', '/api/generate', '/api/progress', '/api/verify', '/api/golive']);
const banned = /(cleanup|purge|truncate|delete|backup|drop|resume)/i;

function files(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) files(p, out);
    else if (/\.(ts|tsx)$/.test(n)) out.push(p);
  }
  return out;
}

if (!existsSync(src)) { console.error('check-no-delete-calls: ui/src does not exist, nothing to check'); process.exit(1); }
let bad = 0;
for (const f of files(src)) {
  const text = readFileSync(f, 'utf8');
  for (const m of text.matchAll(/['"`](\/api\/[^'"`?#\s]*)/g)) {
    const path = m[1];
    if (!allowed.has(path) || banned.test(path)) { console.error(`${f}: route not allowed: ${path}`); bad++; }
  }
  for (const m of text.matchAll(/method\s*:\s*['"`]([A-Za-z]+)['"`]/g)) {
    if (!['GET', 'POST'].includes(m[1].toUpperCase())) { console.error(`${f}: method not allowed: ${m[1]}`); bad++; }
  }
  if (/\b(axios|http|https|api|client|fetcher)\.(delete|put|patch)\s*\(/i.test(text)) { console.error(`${f}: an HTTP delete/put/patch call`); bad++; }
}
if (bad) process.exit(1);
console.log('check-no-delete-calls: ok');
