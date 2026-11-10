update reservations set expires_at = created_at + interval '25 hours' where id = 1;
