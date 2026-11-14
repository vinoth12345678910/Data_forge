import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import ReactFlow, { Background, Controls } from 'reactflow';
import 'reactflow/dist/style.css';
import { api } from '../api/client';
import { findCycles, layout } from './graph';

export function Schema() {
  const q = useQuery({ queryKey: ['schema'], queryFn: api.schema });
  const view = useMemo(() => {
    if (!q.data) return null;
    const cycles = findCycles(q.data.tables);
    return { cycles, ...layout(q.data.tables, cycles) };
  }, [q.data]);

  return (
    <section aria-labelledby="h-schema">
      <h2 id="h-schema">Schema graph</h2>
      {q.isPending ? <p className="muted">Reading the schema…</p> : null}
      {q.isError ? <p className="errors" role="alert">{q.error.message}</p> : null}
      {view ? (
        <>
          <p className="muted">
            {view.nodes.length} tables. An arrow runs from the table that holds the foreign key to the table it refers to.
            Self references are marked on the table; dashed amber edges are in a cycle.
          </p>
          <div className="graph" data-testid="graph">
            <ReactFlow nodes={view.nodes} edges={view.edges} fitView nodesConnectable={false} proOptions={{ hideAttribution: true }}>
              <Background color="var(--line)" />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          {view.cycles.length > 0 ? (
            <div data-testid="cycles">
              <h3>Foreign-key cycles</h3>
              <ul>
                {view.cycles.map((c) => (
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
