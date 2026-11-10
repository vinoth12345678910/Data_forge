-- Small hand-written valid dataset. Dataset clock: 2026-06-01.
-- Loaded in one transaction; the orders <-> payments cycle resolves at commit.

insert into meta (id, generated_at) values (1, '2026-06-01 00:00:00+00');

insert into warehouses (id, code, name, city) values
    (1, 'BLR', 'Bengaluru Hub', 'Bengaluru'),
    (2, 'DEL', 'Delhi Hub', 'Delhi');

insert into users (id, email, full_name, phone, role, referred_by, created_at) values
    (1, 'asha@example.test',   'Asha Rao',     '+919800000001', 'admin',    null, '2026-01-01 09:00+00'),
    (2, 'ben@example.test',    'Ben Thomas',   '+919800000002', 'customer', 1,    '2026-01-10 09:00+00'),
    (3, 'chitra@example.test', 'Chitra Iyer',  '+919800000003', 'customer', 2,    '2026-01-20 09:00+00'),
    (4, 'dev@example.test',     'Dev Malhotra', null,            'customer', null, '2026-02-01 09:00+00'),
    (5, 'esha@example.test',   'Esha Nair',    '+919800000005', 'customer', 3,    '2026-02-10 09:00+00'),
    (6, 'farid@example.test',  'Farid Khan',   '+919800000006', 'staff',    null, '2026-02-15 09:00+00');

insert into events (id, name, venue, city, starts_at, ends_at, capacity, status, created_by, created_at) values
    (1, 'Monsoon Music Fest', 'Palace Grounds',  'Bengaluru', '2026-07-10 18:00+00', '2026-07-10 23:00+00', 100, 'on_sale',   1, '2026-02-01 10:00+00'),
    (2, 'Spring Tech Meet',   'Convention Hall', 'Hyderabad', '2026-04-10 10:00+00', '2026-04-10 17:00+00',  50, 'closed',    1, '2026-02-01 10:00+00'),
    (3, 'Comedy Night',       'Town Theatre',    'Mumbai',    '2026-08-01 19:00+00', '2026-08-01 22:00+00',  20, 'sold_out',  1, '2026-02-01 10:00+00'),
    (4, 'Winter Gala',        'Grand Ballroom',  'Delhi',     '2026-12-01 19:00+00', '2026-12-01 23:00+00', 200, 'draft',     1, '2026-02-01 10:00+00'),
    (5, 'Cancelled Carnival', 'Open Ground',     'Pune',      '2026-09-05 12:00+00', '2026-09-05 20:00+00',  80, 'cancelled', 1, '2026-02-01 10:00+00');

insert into ticket_types (id, event_id, name, price_paise, quantity) values
    (1, 1, 'General',   50000, 60),
    (2, 1, 'VIP',      150000, 30),
    (3, 2, 'Standard',  20000, 50),
    (4, 3, 'Front row', 80000,  8),
    (5, 3, 'Back row',  40000, 12),
    (6, 4, 'Early bird', 30000, 100);

insert into merchandise (id, sku, name, price_paise, active) values
    (1, 'TSHIRT-M', 'Festival T-shirt (M)', 79900, true),
    (2, 'MUG-01',   'Festival Mug',         29900, true);

-- stocked minus paid/fulfilled merch lines = on_hand (merch 1 sold 2, merch 2 sold 3)
insert into stock (warehouse_id, merch_id, stocked, on_hand) values
    (1, 1, 100, 99),
    (2, 1,  50, 49),
    (1, 2,  80, 77);

insert into orders (id, user_id, status, total_paise, latest_payment_id, placed_at, updated_at) values
    (1, 2, 'fulfilled', 799800, 1, '2026-05-01 10:00+00', '2026-05-02 10:00+00'),
    (2, 3, 'paid',      480000, 3, '2026-05-02 11:00+00', '2026-05-02 11:05+00'),
    (3, 4, 'paid',      189700, 4, '2026-05-10 12:00+00', '2026-05-10 12:05+00'),
    (4, 5, 'refunded',  150000, 5, '2026-05-11 13:00+00', '2026-05-15 09:00+00'),
    (5, 2, 'pending',    79900, 6, '2026-05-20 14:00+00', '2026-05-20 14:00+00'),
    (6, 3, 'cancelled',  29900, 7, '2026-05-21 15:00+00', '2026-05-21 15:10+00'),
    (7, 6, 'fulfilled',  20000, 8, '2026-03-01 10:00+00', '2026-03-02 10:00+00');

insert into order_items (id, order_id, ticket_type_id, merch_id, quantity, unit_price_paise) values
    (1, 1, 4,    null, 8, 80000),
    (2, 1, null, 1,    2, 79900),
    (3, 2, 5,    null, 12, 40000),
    (4, 3, 1,    null, 2, 50000),
    (5, 3, null, 2,    3, 29900),
    (6, 4, 2,    null, 1, 150000),
    (7, 5, null, 1,    1, 79900),
    (8, 6, null, 2,    1, 29900),
    (9, 7, 3,    null, 1, 20000);

insert into payments (id, order_id, status, amount_paise, method, created_at, completed_at) values
    (1, 1, 'succeeded', 799800, 'upi',  '2026-05-01 10:00+00', '2026-05-01 10:01+00'),
    (2, 1, 'failed',    799800, 'card', '2026-05-01 09:55+00', '2026-05-01 09:56+00'),
    (3, 2, 'succeeded', 480000, 'card', '2026-05-02 11:00+00', '2026-05-02 11:01+00'),
    (4, 3, 'succeeded', 189700, 'upi',  '2026-05-10 12:00+00', '2026-05-10 12:01+00'),
    (5, 4, 'succeeded', 150000, 'card', '2026-05-11 13:00+00', '2026-05-11 13:01+00'),
    (6, 5, 'initiated',  79900, 'upi',  '2026-05-20 14:00+00', null),
    (7, 6, 'failed',     29900, 'card', '2026-05-21 15:00+00', '2026-05-21 15:01+00'),
    (8, 7, 'succeeded',  20000, 'upi',  '2026-03-01 10:00+00', '2026-03-01 10:01+00');

insert into registrations (id, user_id, event_id, ticket_type_id, quantity, order_item_id, created_at) values
    (1, 2, 3, 4,  8, 1, '2026-05-01 10:05+00'),
    (2, 3, 3, 5, 12, 3, '2026-05-02 11:05+00'),
    (3, 4, 1, 1,  2, 4, '2026-05-10 12:05+00'),
    (4, 6, 2, 3,  1, 9, '2026-03-01 10:05+00');

insert into refunds (id, payment_id, amount_paise, reason, created_at) values
    (1, 4, 29900,  'mug damaged',    '2026-05-12 09:00+00'),
    (2, 5, 150000, 'event conflict', '2026-05-15 09:00+00');
