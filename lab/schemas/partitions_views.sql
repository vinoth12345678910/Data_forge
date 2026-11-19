-- Lab schema: partitioned table, view, materialized view (inheritance is lab/schemas/inheritance.sql).
create table parents (id bigint primary key, name text not null);
create table events (id bigint not null, parent_id bigint not null references parents(id), at timestamptz not null, primary key (id, at)) partition by range (at);
create table events_2025 partition of events for values from ('2025-01-01') to ('2026-01-01');
create table events_2026 partition of events for values from ('2026-01-01') to ('2027-01-01');
create view v_parents as select id, name from parents;
create materialized view mv_parents as select id, name from parents;
