-- Lab schema: quoted, reserved-word, mixed-case and Unicode names.
create table "Order" ("Id" serial primary key, "select" text, "Naïve Col" text, "from" int);
create table "ünï table" (id serial primary key, o int not null references "Order"("Id"), "group" text);
create table "user" (id serial primary key, "order" int references "Order"("Id"));
