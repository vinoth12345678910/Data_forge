import type { TableInfo } from '../api/types';

/** Tables in a foreign-key cycle (strongly connected sets of two or more); self references are separate. */
export function findCycles(tables: TableInfo[]): string[][] {
  const next = new Map<string, string[]>();
  for (const t of tables) next.set(t.name, []);
  for (const t of tables) {
    for (const f of t.fks) if (f.refTable !== t.name && next.has(f.refTable)) next.get(t.name)?.push(f.refTable);
  }
  const index = new Map<string, number>();
  const low = new Map<string, number>();
  const onStack = new Set<string>();
  const stack: string[] = [];
  const out: string[][] = [];
  let i = 0;
  // Iterative depth-first search (explicit call stack): a very long chain must not overflow the JS stack.
  for (const t of tables) {
    if (index.has(t.name)) continue;
    const calls: { v: string; k: number }[] = [{ v: t.name, k: 0 }];
    index.set(t.name, i);
    low.set(t.name, i);
    i++;
    stack.push(t.name);
    onStack.add(t.name);
    while (calls.length > 0) {
      const top = calls[calls.length - 1]!;
      const v = top.v;
      const nb = next.get(v) ?? [];
      if (top.k < nb.length) {
        const w = nb[top.k++]!;
        if (!index.has(w)) {
          index.set(w, i);
          low.set(w, i);
          i++;
          stack.push(w);
          onStack.add(w);
          calls.push({ v: w, k: 0 });
        } else if (onStack.has(w)) {
          low.set(v, Math.min(low.get(v) ?? 0, index.get(w) ?? 0));
        }
        continue;
      }
      if (low.get(v) === index.get(v)) {
        const comp: string[] = [];
        let w: string | undefined;
        do {
          w = stack.pop();
          if (w === undefined) break;
          onStack.delete(w);
          comp.push(w);
        } while (w !== v);
        if (comp.length > 1) out.push(comp.sort());
      }
      calls.pop();
      const parent = calls[calls.length - 1];
      if (parent) low.set(parent.v, Math.min(low.get(parent.v) ?? 0, low.get(v) ?? 0));
    }
  }
  return out;
}
