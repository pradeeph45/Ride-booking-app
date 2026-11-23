CREATE TABLE user_sessions (
    id           BINARY(16)   NOT NULL,   -- equals the OAuth2 authorization id; exposed as the "sid" claim
    user_id      BINARY(16)   NOT NULL,
    device_name  VARCHAR(100) NULL,
    ip_address   VARCHAR(45)  NULL,
    user_agent   VARCHAR(255) NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    expires_at   TIMESTAMP(6) NOT NULL,   -- absolute lifetime
    revoked_at   TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_sessions_user (user_id, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
