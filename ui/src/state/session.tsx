import { createContext, useContext, useState, type ReactNode } from 'react';

interface Session {
  connected: boolean;
  env: string;
  setConnected: (c: boolean) => void;
  setEnv: (e: string) => void;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [connected, setConnected] = useState(false);
  const [env, setEnv] = useState('dev');
  return <Ctx.Provider value={{ connected, env, setConnected, setEnv }}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error('SessionProvider missing');
  return s;
}
