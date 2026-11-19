import type { Edge, Node } from 'reactflow';
import type { TableInfo } from '../api/types';
import { findCycles } from './cycles';
import { dagrePositions, NODE_H as H, NODE_W as W } from './positions';

export { findCycles };

export interface Laid {
  nodes: Node[];
  edges: Edge[];
}

/** Nodes and edges for these tables only; `cycles` come from the whole schema. `selected` is drawn with the accent border; `pos` (from dagrePositions) can be reused across picks. */
export function layout(
  tables: TableInfo[],
  cycles: string[][],
  selected?: string,
  pos: Map<string, { x: number; y: number }> = dagrePositions(tables),
): Laid {
  const inCycle = new Set(cycles.flat());
  const here = new Set(tables.map((t) => t.name));
  const edges: Edge[] = [];
  for (const t of tables) {
    for (const f of t.fks) {
      if (f.refTable === t.name || !here.has(f.refTable)) continue; // a self reference is shown on the table, not as a loop edge
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
  const nodes: Node[] = tables.map((t) => {
    const p = pos.get(t.name) ?? { x: 0, y: 0 };
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
        border: `${t.name === selected ? 2 : 1}px solid ${t.name === selected ? 'var(--accent)' : inCycle.has(t.name) || selfCols.length ? 'var(--warn)' : 'var(--line-strong)'}`,
        borderRadius: 6,
        fontSize: 12,
      },
    };
  });
  return { nodes, edges };
}
