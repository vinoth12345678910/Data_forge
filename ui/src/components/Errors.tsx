import type { Entry } from '../api/types';

/** Shows what the API's envelope says (rule, table, row), never raw driver text. */
export function Errors({ entries }: { entries: Entry[] }) {
  if (entries.length === 0) return null;
  return (
    <ul className="errors" role="alert">
      {entries.map((e, i) => (
        <li key={i}>
          <strong>{e.code}</strong>: {e.message}
          {e.rule ? <span className="muted"> rule {e.rule}</span> : null}
          {e.table ? <span className="muted"> table {e.table}</span> : null}
          {e.row !== undefined && e.row !== null ? <code className="row">{JSON.stringify(e.row)}</code> : null}
        </li>
      ))}
    </ul>
  );
}
