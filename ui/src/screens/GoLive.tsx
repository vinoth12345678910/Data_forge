import { useMutation } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Envelope } from '../api/types';
import { CopyText } from '../components/CopyText';
import { Errors } from '../components/Errors';

function Facts({ env }: { env: Envelope }) {
  const tables = Array.isArray(env.data['tables']) ? (env.data['tables'] as Record<string, unknown>[]) : [];
  const checks = Array.isArray(env.data['checks']) ? (env.data['checks'] as Record<string, unknown>[]) : [];
  return (
    <>
      <p className={env.ok ? 'ok' : 'status'}>{env.ok ? 'OK' : `Not OK (exit ${env.exit})`}</p>
      {tables.length > 0 ? (
        <table>
          <thead>
            <tr><th>Table</th><th>Manifest rows</th><th>Live rows</th><th>Method</th></tr>
          </thead>
          <tbody>
            {tables.map((t, i) => (
              <tr key={i}>
                <td>{String(t['name'])}</td>
                <td>{String(t['manifest_rows'])}</td>
                <td>{String(t['live_rows'])}</td>
                <td>{String(t['method'])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {checks.length > 0 ? (
        <ul>
          {checks.map((c, i) => (
            <li key={i}>
              {String(c['name'] ?? c['check'] ?? `check ${i + 1}`)}: {String(c['passed'] ?? c['ok'] ?? c['status'] ?? '')}
            </li>
          ))}
        </ul>
      ) : null}
      <Errors entries={env.errors} />
    </>
  );
}

export function GoLive() {
  const load = useMutation({ mutationFn: api.golive });
  const g = load.data;
  return (
    <section aria-labelledby="h-golive">
      <h2 id="h-golive">Go-live</h2>
      <p className="muted">
        A dry run and a readiness check. This screen never runs a cleanup, a purge or a backup. A person types the command below in a
        terminal.
      </p>
      <button type="button" onClick={() => load.mutate()} disabled={load.isPending}>
        {load.isPending ? 'Checking…' : 'Run dry run and readiness'}
      </button>
      {load.isError ? <p className="errors" role="alert">{load.error.message}</p> : null}
      {g ? (
        <div className="golive">
          <div data-testid="golive-dry">
            <h3>Cleanup dry run</h3>
            <Facts env={g.dry_run} />
          </div>
          <div data-testid="golive-ready">
            <h3>Readiness</h3>
            <Facts env={g.readiness} />
          </div>
          <div data-testid="golive-checklist">
            <h3>Manual checklist</h3>
            <ul>{g.checklist.map((c, i) => <li key={i}>{typeof c === 'string' ? c : JSON.stringify(c)}</li>)}</ul>
          </div>
          <div>
            <h3>The command a person types</h3>
            <CopyText text={g.command} />
          </div>
        </div>
      ) : null}
    </section>
  );
}
