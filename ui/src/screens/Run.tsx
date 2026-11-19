import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Preflight } from '../api/types';
import { Errors } from '../components/Errors';

export function Run() {
  const qc = useQueryClient();
  const progress = useQuery({
    queryKey: ['progress'],
    queryFn: api.progress,
    refetchInterval: (q) => (q.state.data?.state === 'running' ? 1000 : false),
  });
  const pre = useQuery({ queryKey: ['preflight'], queryFn: api.preflight, retry: false });
  const pf = pre.data?.envelope.data['preflight'] as Preflight | undefined;
  const refusal = pre.data?.envelope.errors.find((e) => e.code === 'memory_preflight');
  const start = useMutation({
    mutationFn: api.generate,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['progress'] }),
  });

  const p = progress.data;
  const env = p?.envelope ?? null;
  const loaded = env && typeof env.data['loaded'] === 'object' && env.data['loaded'] !== null ? (env.data['loaded'] as Record<string, number>) : null;
  const notChecked = env && typeof env.data['not_checked'] === 'string' ? env.data['not_checked'] : null;
  const running = p?.state === 'running' || start.isPending;
  const refused = pf !== undefined && !pf.ok;

  return (
    <section aria-labelledby="h-run">
      <h2 id="h-run">Run</h2>
      <p className="muted">Generates data and loads it into the connected database, then verifies it.</p>
      <div data-testid="preflight" className="preflight">
        <h3>Before you generate</h3>
        {pre.isPending ? <p className="muted">Estimating memory…</p> : null}
        {pre.isError ? <p className="errors" role="alert">{pre.error.message}</p> : null}
        {pf ? (
          <p>
            {pf.estimated_live_mb === null
              ? `No tied composite keys, so there is no memory estimate. This run has ${pf.heap_mb} MB of heap.`
              : `Estimated live memory: ${pf.estimated_live_mb} MB of ${pf.heap_mb} MB of heap (${pf.tied_parent_rows.toLocaleString('en-US')} tied parent rows).`}
          </p>
        ) : null}
        {refused ? (
          <p data-testid="preflight-refusal" className="errors" role="alert">
            {refusal?.message ?? 'This run does not fit in memory.'} Nothing will be loaded. Start the server with -Xmx{pf?.xmx_mb_needed}m.
          </p>
        ) : null}
      </div>
      <button type="button" onClick={() => start.mutate()} disabled={running || refused}>
        {running ? 'Generating…' : 'Generate'}
      </button>
      {start.isError ? <p className="errors" role="alert">{start.error.message}</p> : null}
      <p data-testid="run-state" className="status">
        State: {p?.state ?? 'idle'}
      </p>
      {env ? (
        <div data-testid="run-summary">
          <p className="partial">
            This data is partial. Rules the current phase does not handle are not checked
            {notChecked ? `: ${notChecked}` : '.'}
          </p>
          {loaded ? (
            <table>
              <thead>
                <tr>
                  <th>Table</th>
                  <th>Rows loaded</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(loaded).map(([t, n]) => (
                  <tr key={t}>
                    <td>{t}</td>
                    <td>{n}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <Errors entries={env.errors} />
        </div>
      ) : null}
    </section>
  );
}
