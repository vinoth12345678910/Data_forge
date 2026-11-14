import { useState } from 'react';

/** A command shown as text with a Copy button. Nothing here runs it. */
export function CopyText({ text }: { text: string }) {
  const [done, setDone] = useState(false);
  const copy = () => {
    void navigator.clipboard.writeText(text).then(() => setDone(true));
  };
  return (
    <div className="copy">
      <pre data-testid="command">{text}</pre>
      <button type="button" onClick={copy}>
        {done ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}
