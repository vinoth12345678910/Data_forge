// Records the README demo GIFs from the real tool. Usage:
//   node scripts/demo/record.mjs [--capture-only] [scene ...]      scenes: see scenes.mjs, plus tui, mcp, ui
// It starts a throwaway Postgres 16 in Docker, runs each scene's real commands in a clean sandbox, renders the captured
// output in a terminal page, records it with Playwright and converts it with ffmpeg. Needs: Docker, Java 21, ffmpeg,
// `cd ui && npm ci`, a Chromium (FORGE_E2E_BROWSER=<binary> if Playwright's own is not installed).
import { spawnSync, spawn } from 'node:child_process';
import { chromium } from '../../ui/node_modules/playwright-core/index.mjs';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, statSync, readdirSync, rmSync, chmodSync } from 'node:fs';
import { tmpdir, userInfo, hostname } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scenes } from './scenes.mjs';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = join(REPO, 'docs/img');
const PG = 'forge-demo-pg';
const PORT = 55432;
const args = process.argv.slice(2);
const captureOnly = args.includes('--capture-only');
const wanted = args.filter((a) => !a.startsWith('--'));
const sh = (cmd, o = {}) => spawnSync('bash', ['-c', cmd], { encoding: 'utf8', maxBuffer: 64 << 20, ...o });
const log = (m) => console.log(m);

const sandbox = mkdtempSync(join(tmpdir(), 'forge-demo-'));
const bin = join(sandbox, 'bin');
mkdirSync(bin);
const jar = sh(`ls ${REPO}/modules/cli/build/libs/forge-*.jar | head -1`).stdout.trim();
if (!jar) throw new Error('build the jar first: ./gradlew :cli:fatJar');
writeFileSync(join(bin, 'forge'), `#!/bin/sh\nexec "${REPO}/scripts/forge" "$@"\n`);
writeFileSync(join(bin, 'psql'), `#!/bin/sh\nexec docker exec -i ${PG} psql -U postgres -d app "$@"\n`);
chmodSync(join(bin, 'forge'), 0o755);
chmodSync(join(bin, 'psql'), 0o755);
const baseEnv = { ...process.env, PATH: `${bin}:${process.env.PATH}`, PGPASSWORD: 'pw', FORGE_JAR: jar, REPO };

function startPg() {
  sh(`docker rm -f ${PG} >/dev/null 2>&1`);
  const r = sh(`docker run -d --rm --name ${PG} -p ${PORT}:5432 -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=app postgres:16-alpine`);
  if (r.status !== 0) throw new Error('cannot start postgres: ' + r.stderr);
  for (let i = 0; i < 60; i++) {
    if (sh(`docker exec ${PG} pg_isready -q -U postgres -d app`).status === 0) break;
    sh('sleep 1');
  }
  sh('sleep 2');
}
function resetDb(schema) {
  sh(`docker exec ${PG} psql -q -U postgres -c "drop database if exists app with (force)" -c "create database app"`);
  if (schema) {
    const r = sh(`docker exec -i ${PG} psql -q -v ON_ERROR_STOP=1 -U postgres -d app < "${REPO}/golden/events-merch/schema.sql"`);
    if (r.status !== 0) throw new Error('schema load failed: ' + r.stderr);
  }
}
function writeConfig(dir, backup) {
  let t = readFileSync(join(REPO, 'golden/events-merch/phase3.forge.yml'), 'utf8');
  const conn = `connection:\n  url: postgres://localhost:${PORT}/app\n  user: postgres\n  password_env: PGPASSWORD\n  env: dev\n` +
    (backup ? '  backup_cmd: "echo backup-ok > backup.log"\n' : '');
  if (!t.includes('ddl: schema.sql\n')) throw new Error('golden config changed: no ddl line');
  writeFileSync(join(dir, 'forge.yml'), t.replace('ddl: schema.sql\n', conn));
}
function mask(text, dir) {
  const real = sh(`cd "${dir}" && pwd -P`).stdout.trim();
  return text.split(real).join('/work').split(dir).join('/work').split(`localhost:${PORT}`).join('localhost:5432')
    .split(`127.0.0.1:${PORT}`).join('127.0.0.1:5432').split(String(PORT)).join('5432');
}
function runCmd(cmd, dir) {
  const r = sh(`{ ${cmd.split('localhost:5432').join(`localhost:${PORT}`)}\n} 2>&1`, { cwd: dir, env: baseEnv, timeout: 300000 });
  return mask((r.stdout || '') + (r.stderr || ''), dir);
}

function capture(sc) {
  const dir = join(sandbox, sc.name);
  mkdirSync(dir);
  resetDb(sc.db !== 'none');
  if (sc.db === 'rules') writeConfig(dir, sc.name === 'cleanup');
  for (const c of sc.pre || []) { const r = sh(c, { cwd: dir, env: baseEnv }); if (r.status !== 0) throw new Error(`${sc.name}: setup failed: ${c}\n${r.stdout}${r.stderr}`); }
  const done = [];
  for (const s of sc.steps) {
    if (s.reset) { resetDb(true); }
    const show = typeof s.show === 'function' ? s.show(done) : s.show;
    done.push({ show, out: runCmd(s.run || show, dir), hold: s.hold });
  }
  return done;
}

const TERM = readFileSync(join(REPO, 'scripts/demo/term.html'), 'utf8');
const launch = () => chromium.launch({ headless: true, executablePath: process.env.FORGE_E2E_BROWSER || undefined });

// frames: [{file, ms}] (each shown for ms). Builds a GIF with ffmpeg (palette from the frames; shrinks if over 3 MB).
function framesToGif(frames, gif, dir) {
  const list = join(dir, 'list.txt');
  const body = frames.map((f) => `file '${f.file}'\nduration ${(f.ms / 1000).toFixed(3)}`).join('\n') + `\nfile '${frames[frames.length - 1].file}'\n`;
  writeFileSync(list, body);
  const run = (fps, w) => sh(`ffmpeg -loglevel error -y -f concat -safe 0 -i "${list}" -vf "fps=${fps},scale=${w}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=48[p];[b][p]paletteuse=dither=none" "${gif}"`);
  let r = run(10, 860);
  if (r.status !== 0) throw new Error('ffmpeg failed: ' + r.stderr);
  if (statSync(gif).size > 3 * 1024 * 1024) { run(8, 720); }
  return statSync(gif).size;
}

async function render(name, steps, opt = {}) {
  const dir = mkdtempSync(join(sandbox, 'frames-'));
  const browser = await launch();
  const page = await (await browser.newContext({ viewport: { width: 940, height: 590 } })).newPage();
  await page.setContent(TERM);
  const o = { lead: 600, type: 32, afterType: 350, line: 55, afterOut: 1300, tail: 2200, ...opt };
  const tl = await page.evaluate(([s, op]) => timeline(s, op), [steps, o]);
  const total = tl[tl.length - 1].t;
  // one frame per 100 ms tick, the latest snapshot at or before the tick; consecutive identical frames are merged
  const picked = [];
  let j = 0;
  for (let tick = 0; tick <= total; tick += 100) {
    while (j + 1 < tl.length && tl[j + 1].t <= tick) j++;
    if (picked.length && picked[picked.length - 1].j === j) picked[picked.length - 1].ms += 100;
    else picked.push({ j, ms: 100 });
  }
  const frames = [];
  for (let i = 0; i < picked.length; i++) {
    await page.evaluate((h) => { document.getElementById('t').innerHTML = h; }, tl[picked[i].j].html);
    const file = `f${String(i).padStart(4, '0')}.png`;
    await page.locator('#t').screenshot({ path: join(dir, file) });
    frames.push({ file, ms: picked[i].ms });
  }
  await browser.close();
  const size = framesToGif(frames, join(OUT, `${name}.gif`), dir);
  return { seconds: (total / 1000).toFixed(1), size };
}

const OPTS = { cleanup: { type: 18, line: 30, afterOut: 750, afterType: 250, tail: 1800 }, mcp: { afterOut: 1800, tail: 3000 } };
// The guard is built from this machine's own user and host names, so no personal name is written into the repository.
const esc = (x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const BAD = new RegExp(['\\/Users\\/', '\\/private\\/', '\\/var\\/folders', esc(userInfo().username), esc(hostname().split('.')[0])].join('|'), 'i');
function hygiene(name, steps) {
  const bad = steps.filter((s) => BAD.test(s.show) || BAD.test(s.out));
  if (bad.length) { const t = bad[0].show + '\n' + bad[0].out; const m = BAD.exec(t); throw new Error(`${name}: path, user name or host name in the captured text: ...${t.slice(Math.max(0, m.index - 40), m.index + 60)}...`); }
}

function captureTui() {
  const dir = join(sandbox, 'tui'); mkdirSync(dir);
  resetDb(true);
  const url = `postgres://localhost:${PORT}/app`;
  const menu = 'q Quit > ';
  const script = JSON.stringify([[menu, '1'], ['Database URL: ', url], ['Environment label (dev, test, staging): ', 'dev'], ['User (optional): ', 'postgres'],
    ['Password', 'pw'], [menu, '2'], [menu, '3'], [menu, '5'], [menu, 'q']]);
  const r = spawnSync('python3', [join(REPO, 'scripts/demo/pty_session.py'), script, join(REPO, 'modules/tui/build/install/tui/bin/tui')],
    { encoding: 'utf8', cwd: dir, env: baseEnv, timeout: 600000 });
  return [{ show: 'tui', out: mask(r.stdout, dir), hold: 3000 }];
}
function captureMcp() {
  const dir = join(sandbox, 'mcp'); mkdirSync(dir);
  resetDb(true); writeConfig(dir, false);
  sh('forge generate >/dev/null', { cwd: dir, env: baseEnv });
  return [{ show: 'python3 mcp_client.py forge', out: runCmd(`python3 "${REPO}/scripts/demo/mcp_client.py" "${REPO}/scripts/forge"`, dir), hold: 3000 }];
}

async function recordUi() {
  resetDb(true);
  const dir = mkdtempSync(join(sandbox, 'ui-'));
  const server = spawn(join(REPO, 'modules/server/build/install/server/bin/server'), ['0', '--ui', join(REPO, 'ui/dist')], { env: baseEnv });
  const url = await new Promise((res, rej) => {
    let buf = ''; const t = setTimeout(() => rej(new Error('server did not start')), 60000);
    server.stdout.on('data', (d) => { buf += d; const m = /open the UI: (http:\/\/127\.0\.0\.1:\d+\/#token=[0-9a-f]+)/.exec(buf); if (m) { clearTimeout(t); res(m[1]); } });
  });
  const browser = await launch();
  const page = await (await browser.newContext({ viewport: { width: 1000, height: 640 } })).newPage();
  const shots = [];
  let capturing = true;
  const grab = (async () => {
    let i = 0;
    while (capturing) {
      const file = `u${String(i++).padStart(4, '0')}.png`;
      const t = Date.now();
      try { await page.screenshot({ path: join(dir, file) }); shots.push({ file, t }); } catch { /* page busy: skip */ }
      await new Promise((r) => setTimeout(r, 60));
    }
  })();
  const pause = (ms) => page.waitForTimeout(ms);
  await page.goto(url); await pause(900);
  await page.getByLabel('Database URL').pressSequentially(`postgres://127.0.0.1:${PORT}/app`, { delay: 25 });
  await page.getByLabel('Environment label').selectOption('dev');
  await page.getByLabel(/^User/).fill('postgres');
  await page.getByLabel(/Password/).fill('pw'); await pause(500);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await page.getByRole('heading', { name: 'Schema graph' }).waitFor(); await pause(2500);
  await page.keyboard.press('3'); await pause(700);
  await page.getByRole('button', { name: 'Generate' }).click();
  await page.getByTestId('run-summary').waitFor({ timeout: 120000 }); await pause(1800);
  await page.keyboard.press('5'); await pause(600);
  await page.getByRole('button', { name: 'Run dry run and readiness' }).click();
  await page.getByTestId('command').waitFor({ timeout: 60000 }); await pause(1500);
  await page.getByTestId('command').scrollIntoViewIfNeeded(); await pause(3500);
  capturing = false; await grab;
  await browser.close(); server.kill();
  const frames = shots.map((s2, i) => ({ file: s2.file, ms: i + 1 < shots.length ? shots[i + 1].t - s2.t : 1500 }));
  return framesToGif(frames, join(OUT, 'ui.gif'), dir);
}

startPg();
let failed = 0;
try {
  const names = [...scenes.map((s) => s.name), 'tui', 'mcp', 'ui'];
  for (const name of names) {
    if (wanted.length && !wanted.includes(name)) continue;
    try {
      if (name === 'ui') { if (captureOnly) continue; log(`ui: ${(await recordUi() / 1024).toFixed(0)} KB`); continue; }
      const steps = name === 'tui' ? captureTui() : name === 'mcp' ? captureMcp() : capture(scenes.find((s) => s.name === name));
      hygiene(name, steps);
      if (captureOnly) { console.log(`\n===== ${name}`); for (const s of steps) console.log(`$ ${s.show}\n${s.out.trimEnd()}`); continue; }
      const r = await render(name, steps, OPTS[name]);
      log(`${name}: ${r.seconds} s, ${(r.size / 1024).toFixed(0)} KB`);
    } catch (e) { failed++; log(`${name}: FAILED ${e.message}`); }
  }
} finally {
  sh(`docker rm -f ${PG} >/dev/null 2>&1`);
  rmSync(sandbox, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
