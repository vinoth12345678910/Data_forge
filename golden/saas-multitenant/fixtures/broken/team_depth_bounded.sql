insert into teams (tenant_id, parent_id, name, created_at) values ('11111111-1111-1111-1111-111111111111', 3, 'Level4', '2026-05-01 00:00+00');
insert into teams (tenant_id, parent_id, name, created_at) select tenant_id, id, 'Level5', created_at from teams where name = 'Level4';
