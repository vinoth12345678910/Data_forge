update payments set completed_at = created_at - interval '1 hour' where id = 1;
