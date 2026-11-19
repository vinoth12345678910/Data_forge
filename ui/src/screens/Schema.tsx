import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, { Background, Controls, type ReactFlowInstance } from 'reactflow';
import 'reactflow/dist/style.css';
import { api } from '../api/client';
import type { TableInfo } from '../api/types';
import { findCycles, layout } from './graph';
import { dagrePositions } from './positions';
import {
  buildAdjacency,
  counterText,
  DEFAULT_HOPS,
  LARGE_SCHEMA_THRESHOLD,
  MAX_HOPS,
  mostConnected,
  searchTables,
  selectNeighbourhood,
  validPick,
} from './subgraph';

export function Schema() {
  const q = useQuery({ queryKey: ['schema'], queryFn: api.schema });
  const tables = q.data?.tables;
  const total = tables?.length ?? 0;
  const large = total > LARGE_SCHEMA_THRESHOLD;

  const [query, setQuery] = useState('');
  const [pickedRaw, setPicked] = useState<string | undefined>(undefined);
  const [hops, setHops] = useState(DEFAULT_HOPS);
  const [flow, setFlow] = useState<ReactFlowInstance | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const picked = useMemo(() => validPick(tables, pickedRaw), [tables, pickedRaw]);
  const cycles = useMemo(() => (tables ? findCycles(tables) : []), [tables]);
  const adj = useMemo(() => (tables && large ? buildAdjacency(tables) : null), [tables, large]);
  const hits = useMemo(() => (tables ? searchTables(tables, query) : []), [tables, query]);
  const center = picked ?? (adj ? mostConnected(adj) : undefined);

  // Large schemas: only the neighbourhood is laid out and drawn. Small ones: everything, as before.
  const sel = useMemo(() => (adj && center ? selectNeighbourhood(adj, center, hops) : null), [adj, center, hops]);
  const shown = useMemo(() => (tables ? (sel ? tables.filter((t) => sel.names.has(t.name)) : tables) : []), [tables, sel]);
  // Positions depend on the shown tables only; the selected border is applied on top without a new dagre run.
  const pos = useMemo(() => dagrePositions(shown), [shown]);
  const relations = q.data?.relations ?? [];
  const view = useMemo(() => (tables ? layout(shown, cycles, large ? center : picked, pos, relations) : null), [tables, shown, cycles, large, center, picked, pos, relations]);

  // "/" focuses the search box (not while typing in a field).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
      if (t && /^(INPUT|SELECT|TEXTAREA)$/.test(t.tagName)) return;
      e.preventDefault();
      searchRef.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Centre the view on the chosen table (or fit the neighbourhood) after the nodes change.
  useEffect(() => {
    if (!flow || !view) return;
    const id = window.setTimeout(() => {
      if (!large && picked) void flow.fitView({ nodes: [{ id: picked }], maxZoom: 1.2, duration: 200 });
      else void flow.fitView({ duration: 0 });
    }, 0);
    return () => window.clearTimeout(id);
  }, [flow, view, large, picked]);

  const choose = (name: string) => {
    setPicked(name);
    setQuery('');
  };

  return (
    <section aria-labelledby="h-schema">
      <h2 id="h-schema">Schema graph</h2>
      {q.isPending ? <p className="muted">Reading the schema…</p> : null}
      {q.isError ? <p className="errors" role="alert">{q.error.message}</p> : null}
      {view && tables ? (
        <>
          <p className="muted">
            {large ? null : `${total} tables. `}An arrow runs from the table that holds the foreign key to the table it refers to.
            Self references are marked on the table; dashed amber edges are in a cycle.
          </p>
          <div className="graph-tools">
            <label>
              <span className="label-text">
                Find a table <kbd>/</kbd>
              </span>
              <input
                ref={searchRef}
                type="search"
                value={query}
                placeholder="part of a table name"
                aria-controls={query.trim() ? 'schema-hits' : undefined}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && hits[0]) choose(hits[0]);
                  if (e.key === 'Escape') {
                    setQuery('');
                    e.currentTarget.blur();
                  }
                }}
              />
            </label>
            {large && sel ? (
              <>
                <span data-testid="counter" role="status">
                  {counterText(shown.length, total)}
                  {sel.capped ? `. The view is cut at ${shown.length} tables; the nearest are shown.` : ''}
                </span>
                <span className="hops">
                  Hops: {hops}{' '}
                  <button type="button" onClick={() => setHops(hops - 1)} disabled={hops <= 1} aria-label="Fewer hops">
                    −
                  </button>{' '}
                  <button type="button" onClick={() => setHops(hops + 1)} disabled={hops >= MAX_HOPS}>
                    Expand a hop
                  </button>
                </span>
                {picked ? (
                  <button type="button" onClick={() => setPicked(undefined)}>
                    Back to the most connected table
                  </button>
                ) : null}
              </>
            ) : null}
          </div>
          {query.trim() ? (
            <ul id="schema-hits" data-testid="hits" aria-label="Matching tables" className="hits">
              {hits.length === 0 ? <li className="muted">No table matches.</li> : null}
              {hits.map((h) => (
                <li key={h}>
                  <button type="button" onClick={() => choose(h)}>
                    {h}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {large && center ? (
            <p className="muted">
              Centred on <strong>{center}</strong>, within {hops} {hops === 1 ? 'hop' : 'hops'}.
              Click a table to centre on it.
            </p>
          ) : null}
          {large ? (
            <p data-testid="graph-notice" className="muted">
              Showing {shown.length} of {total} tables around {center ?? 'the most connected table'}. Search a name, open a group below, or
              expand a hop to see more.
            </p>
          ) : null}
          <div className="graph" data-testid="graph">
            <ReactFlow
              nodes={view.nodes}
              edges={view.edges}
              fitView
              onInit={setFlow}
              onNodeClick={(_, n) => choose(n.id)}
              nodesConnectable={false}
              proOptions={{ hideAttribution: true }}
            >
              <Background color="var(--line)" />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          {large ? <Groups tables={tables} onPick={choose} /> : null}
          <details>
            <summary>{large ? 'Visible tables as text' : 'Tables as text'}</summary>
            <ul data-testid="schema-text">
              {shown.map((t) => (
                <li key={t.name}>
                  <button type="button" className="linklike" onClick={() => choose(t.name)}>
                    {t.name}
                  </button>{' '}
                  ({t.columns.length} columns)
                  {t.fks.length > 0 ? `: ${t.fks.map((f) => `${f.columns.join(', ')} → ${f.refTable}`).join('; ')}` : ''}
                </li>
              ))}
            </ul>
          </details>
          {cycles.length > 0 ? (
            <div data-testid="cycles">
              <h3>Foreign-key cycles</h3>
              <ul>
                {cycles.map((c) => (
                  <li key={c.join()}>{c.join(' ↔ ')}</li>
                ))}
              </ul>
            </div>
          ) : (
            <p className="muted">No foreign-key cycles between tables.</p>
          )}
        </>
      ) : null}
    </section>
  );
}

/** The group of a table: the part of its name before the first "_" or ".", or "(no prefix)". */
function groupOf(name: string): string {
  const m = /^[^_.]+(?=[_.])/.exec(name);
  return m ? m[0] : '(no prefix)';
}

/** All tables of a big schema in collapsible groups. A group lists its tables only while it is open, so 2,000 tables stay cheap. */
function Groups({ tables, onPick }: { tables: TableInfo[]; onPick: (name: string) => void }) {
  const [open, setOpen] = useState<Set<string>>(new Set());
  const groups = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const t of tables) {
      const g = groupOf(t.name);
      m.set(g, [...(m.get(g) ?? []), t.name]);
    }
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [tables]);
  const toggle = (g: string, isOpen: boolean) =>
    setOpen((prev) => {
      const n = new Set(prev);
      if (isOpen) n.add(g);
      else n.delete(g);
      return n;
    });
  return (
    <div data-testid="groups">
      <h3>Groups ({groups.length})</h3>
      {groups.map(([g, names]) => (
        <details key={g} data-testid={`group-${g}`} open={open.has(g)} onToggle={(e) => toggle(g, e.currentTarget.open)}>
          <summary>
            {g} ({names.length} tables)
          </summary>
          {open.has(g) ? (
            <ul>
              {names.map((n) => (
                <li key={n}>
                  <button type="button" className="linklike" onClick={() => onPick(n)}>
                    {n}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </details>
      ))}
    </div>
  );
}
