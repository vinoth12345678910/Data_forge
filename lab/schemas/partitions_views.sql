-- Lab schema: partitioned table, inheritance, view, materialized view.
create table parents (id bigint primary key, name text not null);
create table events (id bigint not null, parent_id bigint not null references parents(id), at timestamptz not null, primary key (id, at)) partition by range (at);
create table events_2025 partition of events for values from ('2025-01-01') to ('2026-01-01');
create table events_2026 partition of events for values from ('2026-01-01') to ('2027-01-01');
create table base_t (id serial primary key, v text);
create table child_t (extra int) inherits (base_t);
create view v_parents as select id, name from parents;
create materialized view mv_parents as select id, name from parents;
