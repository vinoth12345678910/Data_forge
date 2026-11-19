// Run: node test/bench.ts   (Node 22.6+ strips types). Measures payload, parse, full-graph layout and subgraph layout. No browser.
import { genSchema } from './fixture.ts';
import { dagrePositions } from '../src/screens/positions.ts';
import { buildAdjacency, mostConnected, selectNeighbourhood } from '../src/screens/subgraph.ts';
import type { SchemaAnswer } from '../src/api/types';

const ms = (t0: number): string => (performance.now() - t0).toFixed(1);
console.log('N | payload KB | parse ms | full nodes/edges | full dagre ms | subgraph(1 hop) nodes | adjacency+select ms | sub dagre ms');
for (const n of [100, 500, 2000, 5000]) {
  const json = JSON.stringify(genSchema(n));
  let t = performance.now();
  const data = JSON.parse(json) as SchemaAnswer;
  const parse = ms(t);
  const edges = data.tables.reduce((a, x) => a + x.fks.length, 0);
  t = performance.now();
  dagrePositions(data.tables);
  const full = ms(t);
  t = performance.now();
  const adj = buildAdjacency(data.tables);
  const start = mostConnected(adj) ?? '';
  const sel = selectNeighbourhood(adj, start, 1);
  const selMs = ms(t);
  const subs = data.tables.filter((x) => sel.names.has(x.name));
  t = performance.now();
  dagrePositions(subs);
  console.log(`${n} | ${(json.length / 1024).toFixed(0)} | ${parse} | ${n}/${edges} | ${full} | ${subs.length} | ${selMs} | ${ms(t)}`);
}
