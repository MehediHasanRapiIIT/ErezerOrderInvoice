-- Assets get one permission per action. Nobody loses anything: staff who could "Add and edit assets" keep adding,
-- editing, status changes, repairs and categories; everyone who could view assets keeps seeing costs, history and
-- the Excel export.
INSERT INTO user_permissions (user_id, permission)
SELECT DISTINCT up.user_id, p.permission
FROM user_permissions up
CROSS JOIN (VALUES ('ASSET_CREATE'), ('ASSET_STATUS'), ('ASSET_REPAIRS'), ('ASSET_CATEGORIES')) AS p(permission)
WHERE up.permission = 'ASSET_MANAGE'
ON CONFLICT DO NOTHING;

INSERT INTO user_permissions (user_id, permission)
SELECT DISTINCT up.user_id, p.permission
FROM user_permissions up
CROSS JOIN (VALUES ('ASSET_COSTS_VIEW'), ('ASSET_HISTORY_VIEW'), ('ASSET_EXPORT')) AS p(permission)
WHERE up.permission IN ('ASSET_VIEW', 'ASSET_MANAGE', 'ASSET_DELETE')
ON CONFLICT DO NOTHING;
