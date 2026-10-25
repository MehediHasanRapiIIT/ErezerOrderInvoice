-- Products a customer sends back (returns and the old items of an exchange) no longer go back into stock by
-- themselves. Exchanges delivered before this change already put their old items back; this flag keeps that
-- history balanced so editing such an exchange later doesn't take the pieces out again.
ALTER TABLE orders
    ADD COLUMN exchange_restocked BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE orders
SET exchange_restocked = TRUE
WHERE order_type = 'EXCHANGE'
  AND delivered_at IS NOT NULL
  AND deleted_at IS NULL
  AND stock_tracked
  AND exchange_reason <> 'DEFECTIVE';
