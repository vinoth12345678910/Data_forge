import dagre from 'dagre';
import type { Edge, Node } from 'reactflow';
import type { TableInfo } from '../api/types';

/** Tables in a foreign-key cycle (strongly connected sets of two or more); self references are separate. */
export function findCycles(tables: TableInfo[]): string[][] {
  const next = new Map<string, string[]>();
  for (const t of tables) next.set(t.name, []);
  for (const t of tables) {
    for (const f of t.fks) if (f.refTable !== t.name && next.has(f.refTable)) next.get(t.name)?.push(f.refTable);
  }
  const index = new Map<string, number>();
  const low = new Map<string, number>();
  const onStack = new Set<string>();
  const stack: string[] = [];
  const out: string[][] = [];
  let i = 0;
  const visit = (v: string): void => {
    index.set(v, i);
    low.set(v, i);
    i++;
    stack.push(v);
    onStack.add(v);
    for (const w of next.get(v) ?? []) {
      if (!index.has(w)) {
        visit(w);
        low.set(v, Math.min(low.get(v) ?? 0, low.get(w) ?? 0));
      } else if (onStack.has(w)) {
        low.set(v, Math.min(low.get(v) ?? 0, index.get(w) ?? 0));
      }
    }
    if (low.get(v) === index.get(v)) {
      const comp: string[] = [];
      let w: string | undefined;
      do {
        w = stack.pop();
        if (w === undefined) break;
        onStack.delete(w);
        comp.push(w);
      } while (w !== v);
      if (comp.length > 1) out.push(comp.sort());
    }
  };
  for (const t of tables) if (!index.has(t.name)) visit(t.name);
  return out;
}

export interface Laid {
  nodes: Node[];
  edges: Edge[];
}

const W = 200;
const H = 72;

export function layout(tables: TableInfo[], cycles: string[][]): Laid {
  const inCycle = new Set(cycles.flat());
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: 32, ranksep: 80 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const t of tables) g.setNode(t.name, { width: W, height: H });
  const edges: Edge[] = [];
  for (const t of tables) {
    for (const f of t.fks) {
      if (f.refTable === t.name) continue; // a self reference is shown on the table, not as a loop edge
      g.setEdge(t.name, f.refTable);
      const cyc = inCycle.has(t.name) && inCycle.has(f.refTable);
      edges.push({
        id: `${t.name}.${f.name}`,
        source: t.name,
        target: f.refTable,
        label: f.columns.join(', '),
        animated: false,
        style: cyc ? { stroke: 'var(--warn)', strokeDasharray: '6 4' } : { stroke: 'var(--line-strong)' },
        labelStyle: { fill: 'var(--muted)', fontSize: 11 },
        labelBgStyle: { fill: 'var(--bg)' },
      });
    }
  }
  dagre.layout(g);
  const nodes: Node[] = tables.map((t) => {
    const p = g.node(t.name);
    const selfCols = t.fks.filter((f) => f.refTable === t.name).map((f) => f.columns.join(', '));
    const flags = [selfCols.length ? `↻ self reference (${selfCols.join('; ')})` : '', inCycle.has(t.name) ? '⚠ in a cycle' : ''].filter(Boolean);
    return {
      id: t.name,
      position: { x: p.x - W / 2, y: p.y - H / 2 },
      data: {
        label: (
          <div>
            <strong>{t.name}</strong>
            <div className="node-sub">
              {t.columns.length} columns{t.pk.length ? ` · pk ${t.pk.join(', ')}` : ''}
            </div>
            {flags.map((f) => (
              <div key={f} className="node-flag">
                {f}
              </div>
            ))}
          </div>
        ),
      },
      style: {
        width: W,
        minHeight: H,
        background: 'var(--panel)',
        color: 'var(--text)',
        border: `1px solid ${inCycle.has(t.name) || selfCols.length ? 'var(--warn)' : 'var(--line-strong)'}`,
        borderRadius: 6,
        fontSize: 12,
      },
    };
  });
  return { nodes, edges };
}
