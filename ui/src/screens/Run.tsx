import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { Errors } from '../components/Errors';

export function Run() {
  const qc = useQueryClient();
  const progress = useQuery({
    queryKey: ['progress'],
    queryFn: api.progress,
    refetchInterval: (q) => (q.state.data?.state === 'running' ? 1000 : false),
  });
  const start = useMutation({
    mutationFn: api.generate,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['progress'] }),
  });

  const p = progress.data;
  const env = p?.envelope ?? null;
  const loaded = env && typeof env.data['loaded'] === 'object' && env.data['loaded'] !== null ? (env.data['loaded'] as Record<string, number>) : null;
  const notChecked = env && typeof env.data['not_checked'] === 'string' ? env.data['not_checked'] : null;
  const running = p?.state === 'running' || start.isPending;

  return (
    <section aria-labelledby="h-run">
      <h2 id="h-run">Run</h2>
      <p className="muted">Generates data and loads it into the connected database, then verifies it.</p>
      <button type="button" onClick={() => start.mutate()} disabled={running}>
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
