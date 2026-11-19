-- Lab schema: one table of less common Postgres types.
create extension if not exists hstore; create extension if not exists citext; create extension if not exists ltree;
create type mood as enum ('sad','ok','happy');
create domain posint as integer check (value > 0);
create type pair as (a int, b text);
create table kinds (
  id bigint generated always as identity primary key,
  j jsonb, jj json, tsv tsvector, price money, addr inet, cidr_c cidr, mac macaddr, h hstore, ci citext, lt ltree,
  r int4range, dr daterange, tr tstzrange, b bit(8), vb bit varying(16), arr int[], tarr text[], m mood, p posint,
  pr pair, u uuid, bytes bytea, iv interval, t time, tz timetz, pt point, x xml, n numeric(12,2), f float8);
