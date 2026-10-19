-- Internal notes and the courier cost get their own permissions. Staff who could do them before (anyone with
-- "Edit orders" or "Update order status") keep that ability; admins always have every permission.
INSERT INTO user_permissions (user_id, permission)
SELECT DISTINCT user_id, p.permission
FROM user_permissions up
CROSS JOIN (VALUES ('ORDER_NOTE_ADD'), ('ORDER_COURIER_COST')) AS p(permission)
WHERE up.permission IN ('ORDER_EDIT', 'ORDER_STATUS_UPDATE')
ON CONFLICT DO NOTHING;
