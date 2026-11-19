-- Lab schema: a trigger that creates a profile for each new user (the Supabase pattern).
create table users (id bigint generated always as identity primary key, email text not null unique);
create table profiles (user_id bigint primary key references users(id), created_by text not null default 'trigger');
create function mk_profile() returns trigger language plpgsql as $$ begin insert into profiles(user_id) values (new.id); return new; end $$;
create trigger users_profile after insert on users for each row execute function mk_profile();
