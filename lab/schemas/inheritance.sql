-- Lab schema: table inheritance. The child does not inherit the parent's primary key constraint.
create table base_t (id serial primary key, v text);
create table child_t (extra int) inherits (base_t);
