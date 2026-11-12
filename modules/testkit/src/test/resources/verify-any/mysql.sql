-- Loaded by hand (not by Data Forge). Data Forge only verifies it.
create table customers (id bigint primary key, email varchar(255) not null);
create table orders (id bigint primary key, customer_id bigint not null references customers (id), status varchar(255) not null, total_paise bigint not null, placed_at datetime not null);
create table order_items (id bigint primary key, order_id bigint not null references orders (id), quantity int not null, unit_price_paise bigint not null);
create table payments (id bigint primary key, order_id bigint not null references orders (id), status varchar(255) not null, amount_paise bigint not null, created_at datetime not null, completed_at datetime);
insert into customers values (1, 'a@example.test'), (2, 'b@example.test');
insert into orders values (1, 1, 'paid', 3000, '2025-01-10 10:00:00'), (2, 2, 'cancelled', 500, '2025-01-11 09:00:00');
insert into order_items values (1, 1, 2, 1000), (2, 1, 1, 1000), (3, 2, 1, 500);
insert into payments values (1, 1, 'succeeded', 3000, '2025-01-10 10:01:00', '2025-01-10 10:02:00'), (2, 2, 'failed', 500, '2025-01-11 09:01:00', '2025-01-11 09:02:00');
