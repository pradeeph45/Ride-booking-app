CREATE TABLE users (
    id             BINARY(16)   NOT NULL,
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(100) NULL,            -- NULL for social-login-only accounts
    role           VARCHAR(20)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins  INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
