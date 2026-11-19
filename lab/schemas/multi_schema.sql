-- Lab schema: a foreign key into another schema (the Supabase auth.users pattern).
create schema auth;
create table auth.users (id uuid primary key default gen_random_uuid(), email text not null unique);
create table public.profiles (id uuid primary key references auth.users(id), display_name text not null);
create table public.posts (id bigint generated always as identity primary key, author uuid not null references public.profiles(id), body text not null);
