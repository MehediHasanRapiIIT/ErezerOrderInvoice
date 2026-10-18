-- Steadfast signs every webhook (X-Signature = HMAC-SHA256 of the body keyed with the auth token), so the token
-- itself is needed to verify requests: store it encrypted (like the API keys) instead of as a hash.
ALTER TABLE courier_settings DROP COLUMN webhook_token_hash;
ALTER TABLE courier_settings ADD COLUMN webhook_token_enc TEXT;
