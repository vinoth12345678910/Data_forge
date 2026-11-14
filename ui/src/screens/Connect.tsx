import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { Entry } from '../api/types';
import { Errors } from '../components/Errors';
import { useSession } from '../state/session';

export function Connect({ onDone }: { onDone: () => void }) {
  const { env, setEnv, setConnected } = useSession();
  const [url, setUrl] = useState('');
  const [user, setUser] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Entry[]>([]);
  const [ok, setOk] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrors([]);
    setOk(false);
    try {
      const r = await api.connect({ url, env, user, password });
      setPassword(''); // the password is not kept after the request
      setConnected(r.ok);
      setOk(r.ok);
      setErrors(r.envelope.errors ?? []);
      if (r.ok) onDone();
    } catch (err) {
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
    </section>
  );
}
