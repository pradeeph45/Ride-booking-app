-- Makes "create booking" safe to retry. The client sends an Idempotency-Key; the same key from the same
-- rider can only ever produce one booking. The unique index is the real guarantee: it holds even when two
-- retries hit two different service instances at the same instant.
ALTER TABLE bookings
    ADD COLUMN idempotency_key    VARCHAR(64) NULL,
    ADD COLUMN request_fingerprint CHAR(64)   NULL,
    -- MySQL allows many NULLs in a unique index, so bookings made without a key are unaffected.
    ADD CONSTRAINT uk_bookings_rider_idempotency UNIQUE (rider_id, idempotency_key);
