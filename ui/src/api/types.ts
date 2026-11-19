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

export interface ColumnInfo {
  name: string;
  type: string;
  nullable: boolean;
  read_as_text: boolean;
}

export interface TableInfo {
  name: string;
  /** A stub for a table in a schema that is not read: never generated, ids come from the config. */
  external: boolean;
  has_insert_trigger: boolean;
  columns: ColumnInfo[];
  pk: string[];
  fks: Fk[];
}

/** A logical foreign key declared in forge.yml. Not a database foreign key. */
export interface Relation {
  table: string;
  columns: string[];
  refTable: string;
  refColumns: string[];
  declared: true;
}

export interface SchemaAnswer {
  schemas: string[];
  tables: TableInfo[];
  relations: Relation[];
}

export interface InitWarning {
  kind: 'trigger' | 'text_read' | 'external_fk';
  message: string;
  what_to_do: string;
  table?: string;
  column?: string;
}

export interface Preflight {
  tied_parent_rows: number;
  estimated_live_mb: number | null;
  heap_mb: number;
  ok: boolean;
  xmx_mb_needed: number | null;
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
  reason?: string;
}

/** A rule from an --expected file that forge.yml does not hold. */
export interface NotCheckedRule {
  name: string;
  reason: string;
}
