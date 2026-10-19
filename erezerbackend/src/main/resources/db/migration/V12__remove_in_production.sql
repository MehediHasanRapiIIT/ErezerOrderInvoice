-- "In production" is no longer a step: orders in it continue as "Processing". Old timeline entries keep their
-- original value so the history stays true.
UPDATE orders SET status = 'PROCESSING' WHERE status = 'IN_PRODUCTION';
