import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { Entry, InitWarning } from '../api/types';
import { Errors } from '../components/Errors';
import { useSession } from '../state/session';

export function Connect({ onDone }: { onDone: () => void }) {
  const { env, setEnv, setConnected } = useSession();
  const [url, setUrl] = useState('');
  const [user, setUser] = useState('');
  const [password, setPassword] = useState('');
  const [schema, setSchema] = useState('public');
  const [warnings, setWarnings] = useState<InitWarning[]>([]);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Entry[]>([]);
  const [ok, setOk] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrors([]);
    setOk(false);
    setWarnings([]);
    try {
      const r = await api.connect({ url, env, user, password, schema });
      setPassword(''); // the password is not kept after the request
      setConnected(r.ok);
      setOk(r.ok);
      setErrors(r.envelope.errors ?? []);
      const w = r.envelope.data?.['init_warnings'];
      setWarnings(Array.isArray(w) ? (w as InitWarning[]) : []);
      // With warnings the person reads them first and moves on with the button; without, go straight to the graph.
      if (r.ok && !(Array.isArray(w) && w.length > 0)) onDone();
    } catch (err) {
      setPassword('');
      setConnected(false);
      setErrors([{ code: 'request_failed', message: err instanceof Error ? err.message : 'request failed' }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="h-connect">
      <h2 id="h-connect">Connect</h2>
      <p className="muted">Reads the schema only. A prod label is refused.</p>
      <p className="muted">
        With MySQL or SQLite only the schema graph works for now; Run, Verify and Go-live need a Postgres connection, because the rules come
        from the Postgres schema reader.
      </p>
      <form onSubmit={(e) => void submit(e)} className="form">
        <label>
          Database URL
          <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="postgres://host:5432/db, mysql://host:3306/db or sqlite:/path/app.db" required />
        </label>
        <label>
          Environment label
          <select value={env} onChange={(e) => setEnv(e.target.value)}>
            <option>dev</option>
            <option>test</option>
            <option>staging</option>
            <option>prod</option>
          </select>
        </label>
        <label>
          Schema to read
          <input value={schema} onChange={(e) => setSchema(e.target.value)} autoComplete="off" placeholder="public" />
          <span className="muted">One schema (Postgres). Reading several schemas is not built; a foreign key into another schema is shown as an external table.</span>
        </label>
        <label>
          User (optional)
          <input value={user} onChange={(e) => setUser(e.target.value)} autoComplete="off" />
        </label>
        <label>
          Password (kept in memory, never stored)
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="off" />
        </label>
        <button type="submit" disabled={busy}>
          {busy ? 'Connecting…' : 'Connect'}
        </button>
      </form>
      {ok ? <p className="ok" role="status">Connected. Schema read.</p> : null}
      <Errors entries={errors} />
      {warnings.length > 0 ? (
        <div data-testid="init-warnings" className="warnings">
          <h3>Things to know about this schema ({warnings.length})</h3>
          <ul>
            {warnings.map((w, i) => (
              <li key={i} data-testid="init-warning">
                <strong>{w.table}{w.column ? `.${w.column}` : ''}</strong>: {w.message}
                <div className="muted">
                  What to do: <span data-testid="what-to-do">{w.what_to_do}</span>
                </div>
              </li>
            ))}
          </ul>
          <button type="button" onClick={onDone}>
            Continue to the schema graph
          </button>
        </div>
      ) : null}
    </section>
  );
}
