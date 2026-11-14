import type { GoLive, Progress, SchemaAnswer, Wrapped } from './types';

/** The session token comes from the URL fragment the server prints (#token=...). It lives in memory only. */
const token: string = (() => {
  const m = /token=([0-9a-f]+)/.exec(window.location.hash);
  if (m && m[1]) {
    window.history.replaceState(null, '', window.location.pathname);
    return m[1];
  }
  return '';
})();

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { 'X-Forge-Token': token, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  // 422 on connect carries a { ok:false, envelope } body the screen wants to show; other failures are errors.
  if (!res.ok && !(path === '/api/connect' && res.status === 422)) {
    const msg = (json as { error?: string } | null)?.error ?? `request failed (${res.status})`;
    throw new ApiError(msg, res.status);
  }
  return json as T;
}

export const hasToken = token !== '';

export const api = {
  connect: (b: { url: string; env: string; user: string; password: string }) => call<Wrapped>('POST', '/api/connect', b),
  schema: () => call<SchemaAnswer>('GET', '/api/schema'),
  generate: () => call<{ state: string }>('POST', '/api/generate', {}),
  progress: () => call<Progress>('GET', '/api/progress'),
  verify: () => call<Wrapped>('POST', '/api/verify', {}),
  golive: () => call<GoLive>('GET', '/api/golive'),
};
