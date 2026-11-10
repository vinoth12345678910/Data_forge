-- Small hand-written valid dataset. Dataset clock: 2026-06-01. Currency INR, amounts in paise.
-- Sits exactly on coupon limits: WELCOME10 is used 3 of 3 times, user 2 twice of 2 allowed.

insert into meta (id, generated_at, currency) values (1, '2026-06-01 00:00:00+00', 'INR');

insert into users (id, email, full_name, created_at) values
    (1, 'asha@example.test',   'Asha Rao',     '2026-01-01 09:00+00'),
    (2, 'ben@example.test',    'Ben Thomas',   '2026-01-10 09:00+00'),
    (3, 'chitra@example.test', 'Chitra Iyer',  '2026-01-20 09:00+00'),
    (4, 'dev@example.test',    'Dev Malhotra', '2026-02-01 09:00+00'),
    (5, 'esha@example.test',   'Esha Nair',    '2026-02-10 09:00+00'),
    (6, 'farid@example.test',  'Farid Khan',   '2026-02-15 09:00+00');

-- Electronics > Phones > Smartphones, Electronics > Accessories, Home > Kitchen > Cookware > Pans, Books
insert into categories (id, parent_id, name) values
    (1, null, 'Electronics'),
    (5, null, 'Home'),
    (9, null, 'Books'),
    (2, 1, 'Phones'),
    (4, 1, 'Accessories'),
    (6, 5, 'Kitchen'),
    (3, 2, 'Smartphones'),
    (7, 6, 'Cookware'),
    (8, 7, 'Pans');

insert into products (id, category_id, sku, name, price_minor, active) values
    (1, 3, 'PH-100',  'Smartphone X', 2500000, true),
    (2, 4, 'ACC-01',  'Phone case',     49900, true),
    (3, 8, 'PAN-01',  'Frying pan',    129900, true),
    (4, 9, 'BOOK-01', 'Novel',          39900, true);

-- on_hand = opening - shipped (shipped/delivered shipments) + restocked; reserved = active reservations
insert into inventory (product_id, opening_qty, on_hand, reserved) values
    (1,  50,  50, 0),
    (2, 100,  98, 0),
    (3,  40,  40, 2),
    (4,  60,  56, 0);

insert into coupons (id, code, discount_type, value, max_uses, per_user_limit, starts_at, ends_at) values
    (1, 'WELCOME10', 'percent', 10,    3,    2,    '2026-01-01 00:00+00', '2026-12-31 00:00+00'),
    (2, 'FLAT500',   'fixed',   50000, null, 1,    '2026-03-01 00:00+00', '2026-12-31 00:00+00');

insert into orders (id, user_id, status, subtotal_minor, discount_minor, shipping_minor, total_minor, placed_at, updated_at) values
    (1, 2, 'returned',          209700,  20970, 5000,  193730, '2026-02-10 10:00+00', '2026-02-20 11:00+00'),
    (2, 2, 'partially_shipped', 2599800, 259980, 0,   2339820, '2026-05-08 11:00+00', '2026-05-10 10:00+00'),
    (3, 3, 'shipped',            39900,   3990, 4000,   39910, '2026-04-01 12:00+00', '2026-04-05 12:00+00'),
    (4, 4, 'paid',              149700,  50000, 0,      99700, '2026-05-15 13:00+00', '2026-05-15 13:01+00'),
    (5, 5, 'pending',           259800,      0, 5000,  264800, '2026-05-31 23:50+00', '2026-05-31 23:50+00'),
    (6, 3, 'cancelled',         119700,      0, 4000,  123700, '2026-05-20 10:00+00', '2026-05-20 10:30+00'),
    (7, 4, 'cancelled',          49900,      0, 4000,   53900, '2026-05-22 09:00+00', '2026-05-22 09:10+00'),
    (8, 5, 'shipped',            79800,  50000, 4000,   33800, '2026-05-04 15:00+00', '2026-05-08 12:00+00');

insert into order_items (id, order_id, product_id, quantity, unit_price_minor) values
    (1,  1, 3, 1, 129900),
    (2,  1, 4, 2,  39900),
    (3,  2, 1, 1, 2500000),
    (4,  2, 2, 2,  49900),
    (5,  3, 4, 1,  39900),
    (6,  4, 2, 3,  49900),
    (7,  5, 3, 2, 129900),
    (8,  6, 4, 3,  39900),
    (9,  7, 2, 1,  49900),
    (10, 8, 4, 2,  39900);

insert into coupon_redemptions (id, coupon_id, user_id, order_id, redeemed_at) values
    (1, 1, 2, 1, '2026-02-10 10:00+00'),
    (2, 1, 2, 2, '2026-05-08 11:00+00'),
    (3, 1, 3, 3, '2026-04-01 12:00+00'),
    (4, 2, 4, 4, '2026-05-15 13:00+00'),
    (5, 2, 5, 8, '2026-05-04 15:00+00');

-- one reservation per order item, 30 minute hold
insert into reservations (id, order_item_id, product_id, quantity, status, created_at, expires_at) values
    (1,  1, 3, 1, 'converted', '2026-02-10 10:00+00', '2026-02-10 10:30+00'),
    (2,  2, 4, 2, 'converted', '2026-02-10 10:00+00', '2026-02-10 10:30+00'),
    (3,  3, 1, 1, 'converted', '2026-05-08 11:00+00', '2026-05-08 11:30+00'),
    (4,  4, 2, 2, 'converted', '2026-05-08 11:00+00', '2026-05-08 11:30+00'),
    (5,  5, 4, 1, 'converted', '2026-04-01 12:00+00', '2026-04-01 12:30+00'),
    (6,  6, 2, 3, 'converted', '2026-05-15 13:00+00', '2026-05-15 13:30+00'),
    (7,  7, 3, 2, 'active',    '2026-05-31 23:50+00', '2026-06-01 00:20+00'),
    (8,  8, 4, 3, 'expired',   '2026-05-20 10:00+00', '2026-05-20 10:30+00'),
    (9,  9, 2, 1, 'released',  '2026-05-22 09:00+00', '2026-05-22 09:30+00'),
    (10, 10, 4, 2, 'converted', '2026-05-04 15:00+00', '2026-05-04 15:30+00');

insert into payments (id, order_id, attempt_no, status, amount_minor, method, created_at, completed_at) values
    (1, 1, 1, 'failed',    193730,  'card', '2026-02-10 10:00+00', '2026-02-10 10:01+00'),
    (2, 1, 2, 'succeeded', 193730,  'upi',  '2026-02-10 10:10+00', '2026-02-10 10:11+00'),
    (3, 2, 1, 'succeeded', 2339820, 'card', '2026-05-08 11:00+00', '2026-05-08 11:01+00'),
    (4, 3, 1, 'succeeded', 39910,   'upi',  '2026-04-01 12:00+00', '2026-04-01 12:01+00'),
    (5, 4, 1, 'succeeded', 99700,   'upi',  '2026-05-15 13:00+00', '2026-05-15 13:01+00'),
    (6, 5, 1, 'initiated', 264800,  'card', '2026-05-31 23:50+00', null),
    (7, 6, 1, 'failed',    123700,  'card', '2026-05-20 10:00+00', '2026-05-20 10:02+00'),
    (8, 6, 2, 'failed',    123700,  'upi',  '2026-05-20 10:10+00', '2026-05-20 10:12+00'),
    (9, 8, 1, 'succeeded', 33800,   'upi',  '2026-05-04 15:00+00', '2026-05-04 15:01+00');

insert into shipments (id, order_id, status, carrier, tracking_no, shipped_at, delivered_at) values
    (1, 1, 'delivered', 'BlueDart', 'BD1001', '2026-02-12 09:00+00', '2026-02-15 12:00+00'),
    (2, 2, 'shipped',   'Delhivery', 'DL2002', '2026-05-10 10:00+00', null),
    (3, 2, 'preparing', 'Delhivery', null,     null,                  null),
    (4, 3, 'delivered', 'BlueDart', 'BD1004', '2026-04-02 09:00+00', '2026-04-05 12:00+00'),
    (5, 8, 'delivered', 'Delhivery', 'DL2005', '2026-05-05 09:00+00', '2026-05-08 12:00+00');

insert into shipment_items (shipment_id, order_item_id, order_id, quantity) values
    (1, 1, 1, 1),
    (1, 2, 1, 2),
    (2, 4, 2, 2),
    (3, 3, 2, 1),
    (4, 5, 3, 1),
    (5, 10, 8, 2);

insert into returns (id, order_item_id, quantity, reason, status, restocked_qty, created_at, received_at) values
    (1, 1,  1, 'changed mind', 'received',  1,    '2026-02-16 10:00+00', '2026-02-20 10:00+00'),
    (2, 2,  2, 'torn pages',   'received',  1,    '2026-02-16 10:00+00', '2026-02-20 10:00+00'),
    (3, 5,  1, 'wrong edition', 'approved', null, '2026-04-10 10:00+00', null),
    (4, 10, 1, 'duplicate',    'requested', null, '2026-05-12 10:00+00', null),
    (5, 10, 1, 'late request', 'rejected',  null, '2026-05-13 10:00+00', null);

-- refunds sit below the returned value (discount is pro-rated) and below the payment total
insert into refunds (id, payment_id, return_id, amount_minor, created_at) values
    (1, 2, 1, 116910, '2026-02-20 11:00+00'),
    (2, 2, 2,  71820, '2026-02-20 11:00+00');

-- Ids above were explicit; move each identity sequence past them.
select setval(pg_get_serial_sequence('users', 'id'), (select max(id) from users));
select setval(pg_get_serial_sequence('categories', 'id'), (select max(id) from categories));
select setval(pg_get_serial_sequence('products', 'id'), (select max(id) from products));
select setval(pg_get_serial_sequence('coupons', 'id'), (select max(id) from coupons));
select setval(pg_get_serial_sequence('orders', 'id'), (select max(id) from orders));
select setval(pg_get_serial_sequence('order_items', 'id'), (select max(id) from order_items));
select setval(pg_get_serial_sequence('coupon_redemptions', 'id'), (select max(id) from coupon_redemptions));
select setval(pg_get_serial_sequence('reservations', 'id'), (select max(id) from reservations));
select setval(pg_get_serial_sequence('payments', 'id'), (select max(id) from payments));
select setval(pg_get_serial_sequence('shipments', 'id'), (select max(id) from shipments));
select setval(pg_get_serial_sequence('returns', 'id'), (select max(id) from returns));
select setval(pg_get_serial_sequence('refunds', 'id'), (select max(id) from refunds));
