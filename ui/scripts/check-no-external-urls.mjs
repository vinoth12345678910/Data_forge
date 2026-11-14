// Fails on any external URL in the UI source or the built output (offline-bundle rule).
// Source (ui/src, ui/index.html): no http(s):// or protocol-relative URL at all except 127.0.0.1 and localhost.
// Built output (ui/dist): the same, except two strings the bundled libraries carry that are never requested:
// XML namespace URIs on w3.org, the React error-decoder text on reactjs.org and React Flow's error-message links on
// reactflow.dev (text and an attribution link we hide; the page never requests any of them).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const url = /(?:https?:)?\/\/[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}[^\s"'`)<>]*|https?:\/\/[^\s"'`)<>]+/g;
const local = /^(?:https?:)?\/\/(?:127\.0\.0\.1|localhost)(?:[:/]|$)/;
const libs = /^https?:\/\/(?:www\.w3\.org|reactjs\.org|reactflow\.dev)(?:[/#]|$)/;

function files(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) files(p, out);
    else if (/\.(ts|tsx|js|mjs|css|html|svg|json)$/.test(n)) out.push(p);
  }
  return out;
}

const targets = [
  { path: join(root, 'src'), strict: true, must: true },
  { path: join(root, 'index.html'), strict: true, must: true },
  { path: join(root, 'dist'), strict: false, must: false },
];
let bad = 0;
for (const t of targets) {
  if (!existsSync(t.path)) {
    if (t.must) { console.error(`check-no-external-urls: ${t.path} does not exist, nothing to check`); bad++; }
    continue;
  }
  const list = statSync(t.path).isDirectory() ? files(t.path) : [t.path];
  for (const f of list) {
    for (const m of readFileSync(f, 'utf8').matchAll(url)) {
      const u = m[0];
      if (local.test(u) || (!t.strict && libs.test(u))) continue;
      console.error(`external URL in ${f}: ${u}`);
      bad++;
    }
  }
}
if (bad) process.exit(1);
console.log('check-no-external-urls: ok');
