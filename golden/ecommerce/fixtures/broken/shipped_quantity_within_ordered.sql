update shipment_items set quantity = 3 where shipment_id = 1 and order_item_id = 1;
-- on_hand is derived from shipped quantities (product 3, two more shipped); keep it consistent so only the limit above is broken
update inventory set on_hand = on_hand - 2 where product_id = 3;
