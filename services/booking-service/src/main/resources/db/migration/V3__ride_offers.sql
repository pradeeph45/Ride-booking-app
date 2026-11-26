-- Matching: each new ride is OFFERED to one nearby driver at a time. The driver has a short time to accept.

ALTER TABLE bookings ADD COLUMN dispatch_after TIMESTAMP(6) NULL;   -- do not try to match again before this time
UPDATE bookings SET dispatch_after = created_at;

CREATE TABLE ride_offers (
    id                 BINARY(16)   NOT NULL,
    booking_id         BINARY(16)   NOT NULL,
    driver_id          BINARY(16)   NOT NULL,
    status             VARCHAR(10)  NOT NULL,       -- PENDING / ACCEPTED / REJECTED / EXPIRED / CANCELLED
    pickup_distance_km DECIMAL(6,2) NOT NULL,       -- how far the driver was from the pickup when offered
    offered_at         TIMESTAMP(6) NOT NULL,
    expires_at         TIMESTAMP(6) NOT NULL,
    responded_at       TIMESTAMP(6) NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- no ON DELETE CASCADE: MySQL forbids it on a column that feeds a stored generated column (see below)
    CONSTRAINT fk_offer_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),

    -- A driver is offered a given ride at most once (so a rejected or expired offer is never repeated).
    CONSTRAINT uk_offer_booking_driver UNIQUE (booking_id, driver_id),

    -- DATABASE-LEVEL INVARIANTS, backing up the Redis reservation: MySQL has no partial unique index, so a
    -- generated column holds the id only while the offer is PENDING (NULL otherwise, and NULLs never collide).
    pending_booking_id BINARY(16) GENERATED ALWAYS AS (IF(status = 'PENDING', booking_id, NULL)) STORED,
    pending_driver_id  BINARY(16) GENERATED ALWAYS AS (IF(status = 'PENDING', driver_id, NULL)) STORED,
    CONSTRAINT uk_one_pending_per_booking UNIQUE (pending_booking_id),   -- a ride has at most one live offer
    CONSTRAINT uk_one_pending_per_driver  UNIQUE (pending_driver_id),    -- a driver holds at most one live offer

    INDEX idx_offer_driver_status (driver_id, status),
    INDEX idx_offer_status_expiry (status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
