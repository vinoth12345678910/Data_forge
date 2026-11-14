import { useMutation } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Entry, VerifyRule } from '../api/types';
import { Errors } from '../components/Errors';

const FAILED = new Set(['FAIL', 'ERROR']);

export function Verify() {
  const run = useMutation({ mutationFn: api.verify });
  const env = run.data?.envelope ?? null;
  const rules: VerifyRule[] = env && Array.isArray(env.data['rules']) ? (env.data['rules'] as VerifyRule[]) : [];
  const passed = rules.filter((r) => r.status === 'PASS');
  const failed = rules.filter((r) => FAILED.has(r.status));
  const notChecked = rules.filter((r) => r.status !== 'PASS' && !FAILED.has(r.status));
  const failedEntries: Entry[] = env ? env.errors.filter((e) => e.code === 'rule_failed') : [];
  const otherEntries: Entry[] = env ? env.errors.filter((e) => e.code !== 'rule_failed') : [];

  return (
    <section aria-labelledby="h-verify">
      <h2 id="h-verify">Verify</h2>
      <p className="muted">The database judges each rule. Three lists, kept apart: passed, failed, and not checked.</p>
      <button type="button" onClick={() => run.mutate()} disabled={run.isPending}>
        {run.isPending ? 'Verifying…' : 'Run verify'}
      </button>
      {run.isError ? <p className="errors" role="alert">{run.error.message}</p> : null}
      <Errors entries={otherEntries} />
      {env ? (
        <div className="verify-lists">
          <div data-testid="verify-passed" className="list passed">
            <h3>Passed ({passed.length})</h3>
            <ul>{passed.map((r) => <li key={r.name}>{r.name}</li>)}</ul>
          </div>
          <div data-testid="verify-failed" className="list failed">
            <h3>Failed ({failed.length})</h3>
            <ul>{failed.map((r) => <li key={r.name}>{r.name} <span className="muted">{r.status}</span></li>)}</ul>
            <Errors entries={failedEntries} />
          </div>
          <div data-testid="verify-notchecked" className="list notchecked">
            <h3>Not checked in this phase ({notChecked.length})</h3>
            <p className="muted">These are not passes. They are listed with their status.</p>
            <ul>{notChecked.map((r) => <li key={r.name}>{r.name} <span className="muted">{r.status}</span></li>)}</ul>
          </div>
        </div>
      ) : null}
    </section>
  );
}
