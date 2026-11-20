-- When an order was actually entered. Unlike the order date (created_at), it never changes, so the list can be sorted
-- by entry; the invoice and the courier keep using the order date.
ALTER TABLE orders ADD COLUMN entered_at TIMESTAMPTZ;

-- Existing orders: the order date, unless someone changed it. Then the first "Order date changed from …" note still
-- holds the original, written as "20 Oct 2026, 2:30 PM" in Bangladesh time.
UPDATE orders o
SET entered_at = coalesce(
        (SELECT to_timestamp(m.original, 'FMDD Mon YYYY, FMHH12:MI AM')::timestamp AT TIME ZONE 'Asia/Dhaka'
         FROM (SELECT substring(n.body FROM '^Order date changed from (.+?) to ') AS original
               FROM order_notes n
               WHERE n.order_id = o.id AND n.body LIKE 'Order date changed from %'
               ORDER BY n.created_at, n.id
               LIMIT 1) m
         WHERE m.original ~ '^[0-9]{1,2} [A-Z][a-z]{2} [0-9]{4}, [0-9]{1,2}:[0-9]{2} (AM|PM)$'),
        o.created_at);

ALTER TABLE orders ALTER COLUMN entered_at SET DEFAULT now();
ALTER TABLE orders ALTER COLUMN entered_at SET NOT NULL;
