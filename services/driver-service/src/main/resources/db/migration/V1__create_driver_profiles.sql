CREATE TABLE driver_profiles (
    user_id       BINARY(16)   NOT NULL,           -- the auth-service user id (uid claim): one profile per driver
    vehicle_type  VARCHAR(20)  NOT NULL,
    plate_number  VARCHAR(16)  NOT NULL,
    vehicle_model VARCHAR(60)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,           -- OFFLINE / ONLINE (the driver's intent)
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    -- a physical vehicle can belong to only one driver profile
    CONSTRAINT uk_driver_plate UNIQUE (plate_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
