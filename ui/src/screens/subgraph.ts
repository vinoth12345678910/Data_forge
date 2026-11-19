import type { TableInfo } from '../api/types';

/** Above this many tables the graph shows a neighbourhood, not the whole schema. */
export const LARGE_SCHEMA_THRESHOLD = 150;
export const DEFAULT_HOPS = 1;
export const MAX_HOPS = 6;
/** A hub table can have thousands of neighbours; the view never shows more than this many tables. */
export const MAX_VISIBLE_TABLES = 300;
export const MAX_SEARCH_HITS = 20;

export type Adjacency = Map<string, string[]>;

/** Undirected table neighbours through foreign keys (either direction); self references are ignored. Lists are sorted. */
export function buildAdjacency(tables: TableInfo[]): Adjacency {
  const sets = new Map<string, Set<string>>();
  for (const t of tables) sets.set(t.name, new Set());
  for (const t of tables) {
    for (const f of t.fks) {
      if (f.refTable === t.name || !sets.has(f.refTable)) continue;
      sets.get(t.name)?.add(f.refTable);
      sets.get(f.refTable)?.add(t.name);
    }
  }
  const out: Adjacency = new Map();
  for (const [k, v] of sets) out.set(k, [...v].sort());
  return out;
}

/** The table with the most foreign-key links (in plus out); ties go to the smaller name. Undefined for no tables. */
export function mostConnected(adj: Adjacency): string | undefined {
  let best: string | undefined;
  let bestN = -1;
  for (const [name, nb] of adj) {
    if (nb.length > bestN || (nb.length === bestN && best !== undefined && name < best)) {
      best = name;
      bestN = nb.length;
    }
  }
  return best;
}

export interface Selection {
  names: Set<string>;
  /** True when the hop limit was not the reason it stopped: the cap on visible tables cut the view. */
  capped: boolean;
}

/** Breadth-first from start, up to `hops` steps, at most `max` tables (nearest first, then by name). */
export function selectNeighbourhood(adj: Adjacency, start: string, hops: number, max = MAX_VISIBLE_TABLES): Selection {
  const names = new Set<string>();
  if (!adj.has(start)) return { names, capped: false };
  names.add(start);
  let frontier = [start];
  let capped = false;
  for (let h = 0; h < hops && frontier.length > 0; h++) {
    const next: string[] = [];
    for (const v of frontier) {
      for (const w of adj.get(v) ?? []) {
        if (names.has(w)) continue;
        if (names.size >= max) {
          capped = true;
          continue;
        }
        names.add(w);
        next.push(w);
      }
    }
    frontier = next;
  }
  return { names, capped };
}

/** Table names containing the query (case-insensitive); exact match first, then prefix matches, then the rest, each by name. */
export function searchTables(tables: TableInfo[], query: string, limit = MAX_SEARCH_HITS): string[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const rank = (n: string): number => (n.toLowerCase() === q ? 0 : n.toLowerCase().startsWith(q) ? 1 : 2);
  return tables
    .map((t) => t.name)
    .filter((n) => n.toLowerCase().includes(q))
    .sort((a, b) => rank(a) - rank(b) || (a < b ? -1 : a > b ? 1 : 0))
    .slice(0, limit);
}

/** "showing 23 of 2000 tables" */
export function counterText(shown: number, total: number): string {
  return `showing ${shown} of ${total} tables`;
}

/** The picked table if it is still a table of this schema, else undefined (stale after a refetch or reconnect). */
export function validPick(tables: TableInfo[] | undefined, picked: string | undefined): string | undefined {
  return picked !== undefined && tables?.some((t) => t.name === picked) ? picked : undefined;
}
