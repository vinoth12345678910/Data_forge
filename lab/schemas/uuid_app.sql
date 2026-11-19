-- Lab schema (written for this project, no third-party licence). uuid keys everywhere, as in a Supabase app.
create table accounts (id uuid primary key default gen_random_uuid(), name text not null, created_at timestamptz not null default now());
create table orders (id uuid primary key default gen_random_uuid(), account_id uuid not null references accounts(id), total_cents bigint not null check (total_cents >= 0), status text not null check (status in ('new','paid','shipped')), created_at timestamptz not null default now());
create table order_lines (id uuid primary key default gen_random_uuid(), order_id uuid not null references orders(id), qty int not null check (qty > 0), unit_cents bigint not null);
