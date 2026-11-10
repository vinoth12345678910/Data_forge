update usage_daily set event_count = event_count + 1 where tenant_id = '11111111-1111-1111-1111-111111111111' and project_id = 1 and day = '2026-03-08' and kind = 'api_call';
