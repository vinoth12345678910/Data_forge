// Run: node --test test/   (no browser, no extra packages)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { genSchema } from './fixture.ts';
import {
  buildAdjacency,
  counterText,
  LARGE_SCHEMA_THRESHOLD,
  MAX_VISIBLE_TABLES,
  mostConnected,
  searchTables,
  selectNeighbourhood,
  validPick,
} from '../src/screens/subgraph.ts';

const big = genSchema(2000).tables;
const adj = buildAdjacency(big);

test('threshold is 150', () => assert.equal(LARGE_SCHEMA_THRESHOLD, 150));

test('adjacency is undirected and ignores self references', () => {
  const t = genSchema(3).tables;
  t[0]!.fks.push({ name: 'self', columns: ['x'], refTable: 't1', refColumns: ['id'] });
  const a = buildAdjacency(t);
  assert.deepEqual(a.get('t1'), ['t2']);
  assert.deepEqual(a.get('t2'), ['t1', 't3']);
});

test('start table is the most connected, ties by name', () => {
  const start = mostConnected(adj)!;
  const max = Math.max(...[...adj.values()].map((v) => v.length));
  assert.equal(adj.get(start)!.length, max);
  const tie = mostConnected(buildAdjacency(genSchema(2).tables));
  assert.equal(tie, 't1');
});

test('one hop is the table and its direct neighbours', () => {
  const s = selectNeighbourhood(adj, 't100', 1);
  assert.deepEqual([...s.names].sort(), ['t100', ...adj.get('t100')!].sort());
  assert.equal(s.capped, false);
});

test('more hops never show fewer tables, zero hops is one table', () => {
  assert.equal(selectNeighbourhood(adj, 't100', 0).names.size, 1);
  let prev = 0;
  for (let h = 0; h <= 5; h++) {
    const n = selectNeighbourhood(adj, 't100', h).names.size;
    assert.ok(n >= prev);
    prev = n;
  }
  assert.ok(prev > 4);
});

test('the cap limits the view and says so', () => {
  const s = selectNeighbourhood(adj, 't1', 200, 25);
  assert.equal(s.names.size, 25);
  assert.equal(s.capped, true);
  assert.ok(s.names.has('t1'));
  assert.ok(MAX_VISIBLE_TABLES >= 100);
});

test('unknown start gives an empty view', () => assert.equal(selectNeighbourhood(adj, 'nope', 2).names.size, 0));

test('counter text', () => assert.equal(counterText(23, 2000), 'showing 23 of 2000 tables'));

test('search: substring, case-insensitive, exact then prefix first, limited', () => {
  assert.deepEqual(searchTables(big, 't1999'), ['t1999']);
  assert.deepEqual(searchTables(big, 'T1999'), ['t1999']);
  const r = searchTables(big, '19', 5);
  assert.equal(r.length, 5);
  assert.deepEqual(searchTables(big, 't7', 3).slice(0, 1), ['t7']);
  assert.deepEqual(searchTables(big, ''), []);
  assert.deepEqual(searchTables(big, 'zzz'), []);
  const mixed = searchTables(genSchema(30).tables.map((t) => ({ ...t, name: t.name === 't5' ? 'x_t1' : t.name })), 't1', 20);
  assert.equal(mixed[0], 't1');
  assert.ok(mixed.includes('x_t1'));
});

test('the visible set for a large schema is far below the total', () => {
  const s = selectNeighbourhood(adj, mostConnected(adj)!, 1);
  assert.ok(s.names.size < 50 && s.names.size < big.length);
});

test('a stale pick (not a table of this schema) is treated as none', () => {
  const t = genSchema(3).tables;
  assert.equal(validPick(t, 't2'), 't2');
  assert.equal(validPick(t, 'gone'), undefined);
  assert.equal(validPick(t, undefined), undefined);
  assert.equal(validPick(undefined, 't2'), undefined);
});
