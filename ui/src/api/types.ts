/** One error or warning of a forge.output/1 envelope (docs/design/OUTPUTS.md). */
export interface Entry {
  code: string;
  message: string;
  rule?: string | null;
  table?: string | null;
  row?: unknown;
}

export interface Envelope {
  schema: string;
  command: string;
  ok: boolean;
  exit: number;
  data: Record<string, unknown>;
  warnings: Entry[];
  errors: Entry[];
}

export interface Fk {
  name: string;
  columns: string[];
  refTable: string;
  refColumns: string[];
}

export interface TableInfo {
  name: string;
  columns: { name: string; nullable: boolean }[];
  pk: string[];
  fks: Fk[];
}

export interface SchemaAnswer {
  tables: TableInfo[];
}

export interface Wrapped {
  ok: boolean;
  envelope: Envelope;
}

export type RunState = 'idle' | 'running' | 'done' | 'failed';

export interface Progress {
  state: RunState;
  envelope: Envelope | null;
}

export interface GoLive {
  dry_run: Envelope;
  readiness: Envelope;
  checklist: unknown[];
  command: string;
}

export interface VerifyRule {
  name: string;
  kind?: string;
  subject?: string;
  status: string;
  checked?: number;
  violations?: number;
}
