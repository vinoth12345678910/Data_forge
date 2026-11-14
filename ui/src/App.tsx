import { useEffect, useState } from 'react';
import { hasToken } from './api/client';
import { Banner } from './components/Banner';
import { Connect } from './screens/Connect';
import { GoLive } from './screens/GoLive';
import { Run } from './screens/Run';
import { Schema } from './screens/Schema';
import { Verify } from './screens/Verify';
import { useSession } from './state/session';

const SCREENS = ['Connect', 'Schema', 'Run', 'Verify', 'Go-live'] as const;
type Screen = (typeof SCREENS)[number];

export function App() {
  const { connected } = useSession();
  const [screen, setScreen] = useState<Screen>('Connect');

  // Keys 1 to 5 switch screens (not while typing in a field).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && /^(INPUT|SELECT|TEXTAREA)$/.test(t.tagName)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const i = Number(e.key);
      const s = i >= 1 && i <= 5 ? SCREENS[i - 1] : undefined;
      if (s && (connected || s === 'Connect')) setScreen(s);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [connected]);

  return (
    <>
      <Banner />
      <header className="top">
        <h1>Data Forge</h1>
        <nav aria-label="Screens">
          {SCREENS.map((s, i) => (
            <button
              key={s}
              type="button"
              className={s === screen ? 'tab on' : 'tab'}
              aria-current={s === screen ? 'page' : undefined}
              disabled={!connected && s !== 'Connect'}
              onClick={() => setScreen(s)}
            >
              <kbd>{i + 1}</kbd> {s}
            </button>
          ))}
        </nav>
      </header>
      <main>
        {!hasToken ? (
          <p className="errors" role="alert">
            No session token. Open the address the server printed (it ends in #token=...).
          </p>
        ) : null}
        {screen === 'Connect' ? <Connect onDone={() => setScreen('Schema')} /> : null}
        {screen === 'Schema' ? <Schema /> : null}
        {screen === 'Run' ? <Run /> : null}
        {screen === 'Verify' ? <Verify /> : null}
        {screen === 'Go-live' ? <GoLive /> : null}
      </main>
    </>
  );
}
