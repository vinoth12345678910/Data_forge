import dagre from 'dagre';
import type { TableInfo } from '../api/types';

export const NODE_W = 200;
export const NODE_H = 72;

/** Dagre positions (node centres) for the given tables. Foreign keys to tables outside the list, and self references, add no edge. */
export function dagrePositions(tables: TableInfo[]): Map<string, { x: number; y: number }> {
  const names = new Set(tables.map((t) => t.name));
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: 32, ranksep: 80 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const t of tables) g.setNode(t.name, { width: NODE_W, height: NODE_H });
  for (const t of tables) for (const f of t.fks) if (f.refTable !== t.name && names.has(f.refTable)) g.setEdge(t.name, f.refTable);
  dagre.layout(g);
  const out = new Map<string, { x: number; y: number }>();
  for (const t of tables) {
    const p = g.node(t.name);
    out.set(t.name, { x: p.x, y: p.y });
  }
  return out;
}
