-- events-merch translated for MySQL 8.4: portable subset of schema.sql (hand-checked). Differences: ids are plain bigint primary keys (the generator supplies them), no deferrable constraints (cycles load NULL-then-update), the Postgres-only ttl check and expression unique index are simplified. Run by the P3.5 gate.
-- events-merch: events, tickets and merchandise. Postgres.
-- Money is integer paise (columns end in _paise). Ids are bigint identity.

create table meta (
    id           smallint primary key default 1,
    generated_at datetime not null,
    constraint meta_one_row check (id = 1)
);

create table warehouses (
    id   bigint primary key,
    code varchar(255) not null unique,
    name varchar(255) not null,
    city varchar(255) not null
);

create table users (
    id          bigint primary key,
    email       varchar(255) not null unique,
    full_name   varchar(255) not null,
    phone       varchar(255),
    role        varchar(255) not null,
    referred_by bigint,
    created_at  datetime not null,
    constraint users_role_valid check (role in ('customer', 'staff', 'admin')),
    constraint users_referred_by_fk foreign key (referred_by) references users (id),
    constraint users_referral_not_self check (referred_by <> id)
);

create table events (
    id         bigint primary key,
    name       varchar(255) not null,
    venue      varchar(255) not null,
    city       varchar(255) not null,
    starts_at  datetime not null,
    ends_at    datetime not null,
    capacity   int not null,
    status     varchar(255) not null,
    created_by bigint not null,
    created_at datetime not null,
    constraint events_times_ordered check (starts_at < ends_at),
    constraint events_capacity_positive check (capacity > 0),
    constraint events_status_valid check (status in ('draft', 'on_sale', 'sold_out', 'closed', 'cancelled')),
    constraint events_created_by_fk foreign key (created_by) references users (id)
);

create table ticket_types (
    id          bigint primary key,
    event_id    bigint not null,
    name        varchar(255) not null,
    price_paise bigint not null,
    quantity    int not null,
    constraint ticket_types_event_fk foreign key (event_id) references events (id),
    constraint ticket_types_event_name_key unique (event_id, name),
    constraint ticket_types_id_event_key unique (id, event_id),
    constraint ticket_types_price_nonneg check (price_paise >= 0),
    constraint ticket_types_quantity_positive check (quantity > 0)
);

create table merchandise (
    id          bigint primary key,
    sku         varchar(255) not null unique,
    name        varchar(255) not null,
    price_paise bigint not null,
    active      boolean not null default true,
    constraint merchandise_price_nonneg check (price_paise >= 0)
);

create table stock (
    warehouse_id bigint not null,
    merch_id     bigint not null,
    stocked      int not null,
    on_hand      int not null,
    primary key (warehouse_id, merch_id),
    constraint stock_warehouse_fk foreign key (warehouse_id) references warehouses (id),
    constraint stock_merch_fk foreign key (merch_id) references merchandise (id),
    constraint stock_counts_valid check (on_hand >= 0 and on_hand <= stocked)
);

create table orders (
    id                bigint primary key,
    user_id           bigint not null,
    status            varchar(255) not null,
    total_paise       bigint not null,
    latest_payment_id bigint,
    placed_at         datetime not null,
    updated_at        datetime not null,
    constraint orders_user_fk foreign key (user_id) references users (id),
    constraint orders_status_valid check (status in ('pending', 'paid', 'fulfilled', 'cancelled', 'refunded')),
    constraint orders_total_nonneg check (total_paise >= 0)
);

create table order_items (
    id               bigint primary key,
    order_id         bigint not null,
    ticket_type_id   bigint,
    merch_id         bigint,
    quantity         int not null,
    unit_price_paise bigint not null,
    constraint order_items_order_fk foreign key (order_id) references orders (id),
    constraint order_items_ticket_type_fk foreign key (ticket_type_id) references ticket_types (id),
    constraint order_items_merch_fk foreign key (merch_id) references merchandise (id),
    constraint order_items_one_target check (((ticket_type_id is not null) + (merch_id is not null)) = 1),
    constraint order_items_quantity_positive check (quantity > 0),
    constraint order_items_price_nonneg check (unit_price_paise >= 0)
);

create table payments (
    id           bigint primary key,
    order_id     bigint not null,
    status       varchar(255) not null,
    amount_paise bigint not null,
    method       varchar(255) not null,
    created_at   datetime not null,
    completed_at datetime,
    constraint payments_status_valid check (status in ('initiated', 'succeeded', 'failed')),
    constraint payments_amount_positive check (amount_paise > 0),
    constraint payments_completed_when_done check ((status = 'initiated') = (completed_at is null))
);

-- The orders <-> payments cycle: both sides deferred, resolved at commit.
alter table orders
    add constraint orders_latest_payment_fk foreign key (latest_payment_id) references payments (id);
alter table payments
    add constraint payments_order_fk foreign key (order_id) references orders (id);

create table registrations (
    id             bigint primary key,
    user_id        bigint not null,
    event_id       bigint not null,
    ticket_type_id bigint not null,
    quantity       int not null,
    order_item_id  bigint not null,
    created_at     datetime not null,
    constraint registrations_user_fk foreign key (user_id) references users (id),
    constraint registrations_event_fk foreign key (event_id) references events (id),
    constraint registrations_ticket_event_fk foreign key (ticket_type_id, event_id) references ticket_types (id, event_id),
    constraint registrations_order_item_fk foreign key (order_item_id) references order_items (id),
    constraint registrations_order_item_key unique (order_item_id),
    constraint registrations_user_event_key unique (user_id, event_id),
    constraint registrations_quantity_positive check (quantity > 0)
);

create table refunds (
    id           bigint primary key,
    payment_id   bigint not null,
    amount_paise bigint not null,
    reason       varchar(255) not null,
    created_at   datetime not null,
    constraint refunds_payment_fk foreign key (payment_id) references payments (id),
    constraint refunds_amount_positive check (amount_paise > 0)
);
