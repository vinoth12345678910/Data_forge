// Run: node --test test/   (no browser)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { TableInfo } from '../src/api/types';
import { findCycles } from '../src/screens/cycles.ts';

function tbl(name: string, ...refs: string[]): TableInfo {
  return {
    name,
    columns: [{ name: 'id', nullable: false }],
    pk: ['id'],
    fks: refs.map((r, i) => ({ name: `${name}_fk${i}`, columns: ['x'], refTable: r, refColumns: ['id'] })),
  } as TableInfo;
}

test('no cycle in a plain chain, self references ignored', () => {
  assert.deepEqual(findCycles([tbl('a', 'b'), tbl('b', 'c'), tbl('c', 'c')]), []);
});

test('two cycles and a bridge table: components sorted, listed in completion order', () => {
  const t = [tbl('a', 'b'), tbl('b', 'a', 'c'), tbl('c'), tbl('d', 'e'), tbl('e', 'f'), tbl('f', 'd', 'a')];
  assert.deepEqual(findCycles(t), [['a', 'b'], ['d', 'e', 'f']]);
});

test('reference to an unknown table is ignored', () => {
  assert.deepEqual(findCycles([tbl('a', 'zzz')]), []);
});

test('a 20000-table chain does not overflow the stack and has no cycle', () => {
  const t: TableInfo[] = [];
  for (let i = 0; i < 20000; i++) t.push(tbl(`t${i}`, ...(i < 19999 ? [`t${i + 1}`] : [])));
  assert.deepEqual(findCycles(t), []);
});

test('a ring of 3 inside a long chain is the only cycle found', () => {
  const t: TableInfo[] = [];
  const n = 20000;
  for (let i = 0; i < n; i++) {
    const refs = i < n - 1 ? [`t${i + 1}`] : [];
    if (i === 10002) refs.push('t10000'); // t10000 -> t10001 -> t10002 -> t10000
    t.push(tbl(`t${i}`, ...refs));
  }
  assert.deepEqual(findCycles(t), [['t10000', 't10001', 't10002']]);
});
