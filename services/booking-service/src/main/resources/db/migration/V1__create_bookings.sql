CREATE TABLE bookings (
    id                BINARY(16)    NOT NULL,
    rider_id          BINARY(16)    NOT NULL,   -- the auth-service user id (uid claim); no FK across services
    driver_id         BINARY(16)    NULL,
    service_type      VARCHAR(20)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    pickup_lat        DOUBLE        NOT NULL,
    pickup_lng        DOUBLE        NOT NULL,
    pickup_address    VARCHAR(255)  NULL,
    drop_lat          DOUBLE        NOT NULL,
    drop_lng          DOUBLE        NOT NULL,
    drop_address      VARCHAR(255)  NULL,
    distance_km       DECIMAL(8,2)  NOT NULL,
    estimated_minutes INT           NOT NULL,
    estimated_fare    DECIMAL(10,2) NOT NULL,
    final_fare        DECIMAL(10,2) NULL,
    currency          VARCHAR(3)    NOT NULL,
    created_at        TIMESTAMP(6)  NOT NULL,
    updated_at        TIMESTAMP(6)  NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,   -- optimistic locking: concurrent updates cannot both win
    PRIMARY KEY (id),
    -- ride history: "my bookings, newest first" with keyset pagination (created_at, id)
    INDEX idx_bookings_rider_history (rider_id, created_at, id),
    INDEX idx_bookings_driver_history (driver_id, created_at, id),
    -- drivers poll for open requests
    INDEX idx_bookings_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Append-only audit trail of every state change (who moved a booking, when).
CREATE TABLE booking_status_history (
    id          BINARY(16)   NOT NULL,
    booking_id  BINARY(16)   NOT NULL,
    from_status VARCHAR(20)  NULL,
    to_status   VARCHAR(20)  NOT NULL,
    actor_id    BINARY(16)   NOT NULL,
    changed_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_history_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    INDEX idx_history_booking (booking_id, changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
