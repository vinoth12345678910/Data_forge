import type { SchemaAnswer, TableInfo } from '../src/api/types';

/** An /api/schema-shaped payload in the shape of scripts/lab-gen-schema.sh: t1..tN, parent_id references t(i-1), every 7th references t(i/2). */
export function genSchema(n: number): SchemaAnswer {
  const tables: TableInfo[] = [];
  for (let i = 1; i <= n; i++) {
    const columns = [
      { name: 'id', nullable: false },
      { name: 'name', nullable: false },
      { name: 'amount', nullable: false },
      { name: 'created_at', nullable: false },
    ];
    const fks = [];
    if (i > 1) {
      let p = i % 7 === 0 ? Math.floor(i / 2) : i - 1;
      if (p < 1) p = 1;
      columns.push({ name: 'parent_id', nullable: false });
      fks.push({ name: `t${i}_parent_id_fkey`, columns: ['parent_id'], refTable: `t${p}`, refColumns: ['id'] });
    }
    tables.push({ name: `t${i}`, columns, pk: ['id'], fks });
  }
  return { tables };
}
